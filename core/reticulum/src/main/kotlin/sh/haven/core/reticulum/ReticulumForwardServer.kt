package sh.haven.core.reticulum

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReticulumForward"

/**
 * Tunnels local TCP ports through an rnsh destination, giving Reticulum the
 * `ssh -L` / `ssh -D` surface SSH already has — but over the mesh. Each
 * accepted TCP connection opens its own exec Link running `nc <host> <port>`
 * on the remote and bridges the socket to that command's stdin/stdout.
 *
 * The remote only needs `nc` (busybox `nc <host> <port>` is outbound-capable,
 * which is all a forward requires). No listener-side software beyond a shell
 * and netcat.
 *
 * Supports:
 *   - Local forward (`-L`): localhost:bind → remote `nc target:port`.
 *   - Dynamic forward (`-D`): a SOCKS5 (CONNECT, no-auth) proxy whose every
 *     CONNECT spawns `nc <requested host:port>` on the remote.
 *
 * Not supported (v1): remote forward (`-R`), SOCKS BIND/UDP, SOCKS auth.
 */
@Singleton
class ReticulumForwardServer @Inject constructor(
    private val transport: ReticulumTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Forward(val bindPort: Int, val socket: ServerSocket, val job: Job)

    /** profileId → its active listeners. */
    private val forwards = ConcurrentHashMap<String, MutableList<Forward>>()

    /**
     * Start a local forward. Returns the actually-bound port (useful when
     * [bindPort] is 0). The remote target is reached via `nc`.
     */
    fun startLocalForward(
        profileId: String,
        destinationHash: String,
        bindAddress: String,
        bindPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Int = startListener(profileId, bindAddress, bindPort) { client ->
        bridge(destinationHash, targetHost, targetPort, client)
    }

    /**
     * Start a dynamic (SOCKS5) forward. Returns the actually-bound port.
     */
    fun startDynamicForward(
        profileId: String,
        destinationHash: String,
        bindAddress: String,
        bindPort: Int,
    ): Int = startListener(profileId, bindAddress, bindPort) { client ->
        val dest = socks5Handshake(client) ?: return@startListener
        bridge(destinationHash, dest.first, dest.second, client)
    }

    /** Stop a single forward by its bound port. */
    fun stopForward(profileId: String, bindPort: Int) {
        forwards[profileId]?.let { list ->
            list.removeAll { f ->
                if (f.bindPort == bindPort) {
                    f.job.cancel()
                    runCatching { f.socket.close() }
                    true
                } else false
            }
        }
    }

    /** Stop every forward for a profile (called on disconnect). */
    fun stopAllForProfile(profileId: String) {
        forwards.remove(profileId)?.forEach { f ->
            f.job.cancel()
            runCatching { f.socket.close() }
        }
    }

    /** Stop everything (called on Disconnect All / service shutdown). */
    fun stopAll() {
        forwards.keys.toList().forEach { stopAllForProfile(it) }
    }

    /** Active bound ports for a profile (for the live-tunnels surface). */
    fun activePorts(profileId: String): List<Int> =
        forwards[profileId]?.map { it.bindPort } ?: emptyList()

    // --- internals -------------------------------------------------------

    private fun startListener(
        profileId: String,
        bindAddress: String,
        bindPort: Int,
        handle: suspend (Socket) -> Unit,
    ): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, bindPort))
        val bound = ss.localPort
        Log.i(TAG, "forward listening on $bindAddress:$bound (profile=$profileId)")

        val job = scope.launch {
            while (isActive) {
                val client = try {
                    withContext(Dispatchers.IO) { ss.accept() }
                } catch (_: IOException) {
                    break // socket closed on shutdown
                }
                launch {
                    try { handle(client) }
                    catch (e: Exception) { Log.w(TAG, "connection handler failed", e) }
                    finally { runCatching { client.close() } }
                }
            }
            Log.i(TAG, "forward accept loop on $bound exited")
        }

        forwards.getOrPut(profileId) { mutableListOf() }.add(Forward(bound, ss, job))
        return bound
    }

    /**
     * Bridge a client socket to a remote `nc host port` over an exec Link,
     * with half-close semantics (#208): a client EOF sends stdin EOF to nc
     * but keeps reading the remote's response until nc closes.
     */
    private suspend fun bridge(destinationHash: String, host: String, port: Int, client: Socket) {
        val exec = transport.execCommand(destinationHash, listOf("nc", host, port.toString()))
        try {
            coroutineScope {
                val cout = client.getOutputStream()
                val cin = client.getInputStream()

                // Downstream: remote stdout → client.
                val down = launch {
                    try {
                        exec.stdout.collect { cout.write(it); cout.flush() }
                    } catch (_: Exception) { /* torn down */ }
                    finally { runCatching { client.shutdownOutput() } }
                }

                // Upstream: client → remote stdin, chunked under the link MDU.
                try {
                    val buf = ByteArray(STDIN_CHUNK)
                    while (true) {
                        val n = withContext(Dispatchers.IO) { cin.read(buf) }
                        if (n < 0) break
                        exec.writeStdin(buf.copyOf(n))
                    }
                } catch (_: Exception) { /* torn down */ }
                // Client half-closed. Ideally we'd send a stdin EOF to nc here,
                // but a stdin-EOF over rnsh currently stalls the exec — so we
                // skip it and let the link teardown (below) stop nc. Keeps the
                // tunnel working for interactive/keep-alive protocols; degrades
                // half-close-dependent ones (e.g. `nc -N`, HTTP/1.0-close).
                down.join()
            }
        } finally {
            runCatching { exec.close() }
            runCatching { client.close() }
        }
    }

    /**
     * Minimal SOCKS5 handshake (no-auth, CONNECT only). Returns the requested
     * destination (host, port), or null on any protocol error (socket is left
     * for the caller's `finally` to close).
     */
    private fun socks5Handshake(client: Socket): Pair<String, Int>? {
        client.soTimeout = 30_000
        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        val dest = parseSocks5Connect(input, output) ?: return null
        client.soTimeout = 0 // long-lived tunnels sit idle
        return dest
    }

    /**
     * Pure SOCKS5 (no-auth, CONNECT) byte handshake over the given streams.
     * Returns the requested (host, port) or null on any protocol error.
     * Extracted from the socket path so it is unit-testable.
     */
    internal fun parseSocks5Connect(
        input: DataInputStream,
        output: DataOutputStream,
    ): Pair<String, Int>? {
        if (input.readUnsignedByte() != 5) return null
        val nmethods = input.readUnsignedByte()
        val methods = ByteArray(nmethods); input.readFully(methods)
        if (methods.none { it.toInt() == 0x00 }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); return null
        }
        output.write(byteArrayOf(0x05, 0x00)); output.flush()

        val ver = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        if (ver != 5 || cmd != 1) { socksReply(output, 0x07); return null }

        val host = when (atyp) {
            0x01 -> { val a = ByteArray(4); input.readFully(a); InetAddress.getByAddress(a).hostAddress ?: "0.0.0.0" }
            0x03 -> { val len = input.readUnsignedByte(); val n = ByteArray(len); input.readFully(n); String(n, Charsets.US_ASCII) }
            0x04 -> { val a = ByteArray(16); input.readFully(a); InetAddress.getByAddress(a).hostAddress ?: "::" }
            else -> { socksReply(output, 0x08); return null }
        }
        val port = input.readUnsignedShort()

        // Optimistic success — we can't know nc connected until bytes flow,
        // but SOCKS clients tolerate this and it avoids a round-trip probe.
        socksReply(output, 0x00)
        return host to port
    }

    private fun socksReply(output: DataOutputStream, rep: Int) {
        output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    fun shutdown() {
        stopAll()
        scope.cancel()
    }

    companion object {
        /** Max stdin chunk per stream-data message (stays under the link MDU). */
        private const val STDIN_CHUNK = 400
    }
}
