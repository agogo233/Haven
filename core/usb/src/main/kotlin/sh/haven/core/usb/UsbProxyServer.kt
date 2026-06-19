package sh.haven.core.usb

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Serves the [UsbProxyProtocol] over an abstract-namespace `LocalSocket` so a
 * guest shim inside the proot can drive a brokered USB device.
 *
 * The socket lives in the abstract namespace (`\0haven-usb`), which is part of
 * the network namespace. proot does not isolate the network namespace, so the
 * proot tracee shares it with the Android app and can `connect()` to the same
 * abstract name — that reachability is the Slice-2 gate. (If it ever fails on
 * some device, the fallback is a filesystem-path socket under the cacheDir
 * `/tmp` bind; the protocol is transport-agnostic.)
 *
 * One server instance proxies one opened device, identified by [deviceName].
 * Every accepted connection issues requests against that device through
 * [UsbBroker]; concurrent guest opens (e.g. udev probe + the app) all multiplex
 * onto the same cached [android.hardware.usb.UsbDeviceConnection].
 */
@Singleton
class UsbProxyServer @Inject constructor(
    private val broker: UsbBroker,
) {
    @Volatile private var serverSocket: LocalServerSocket? = null
    @Volatile private var deviceName: String? = null
    private var acceptThread: Thread? = null

    /** True while a proxy socket is bound. */
    val isRunning: Boolean get() = serverSocket != null

    /** The abstract socket name a guest connects to. */
    val socketName: String get() = SOCKET_NAME

    /** The device currently proxied to the guest (`/dev/bus/usb/...`), or null when stopped. */
    val proxyDeviceName: String? get() = deviceName

    /**
     * Bind the proxy for [deviceName] (which must already be opened via
     * [UsbBroker.openDevice]). Idempotent for the same device; rebinds for a
     * different one. Returns the abstract socket name to hand the guest.
     */
    @Synchronized
    fun start(deviceName: String): String {
        if (serverSocket != null && this.deviceName == deviceName) return SOCKET_NAME
        stop()
        val socket = LocalServerSocket(SOCKET_NAME)
        serverSocket = socket
        this.deviceName = deviceName
        acceptThread = thread(name = "haven-usb-proxy", isDaemon = true) {
            Log.i(TAG, "USB proxy listening on \\0$SOCKET_NAME for $deviceName")
            while (serverSocket === socket) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (serverSocket === socket) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                thread(name = "haven-usb-conn", isDaemon = true) { serve(client, deviceName) }
            }
        }
        return SOCKET_NAME
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        deviceName = null
        acceptThread = null
    }

    private fun serve(client: LocalSocket, device: String) {
        client.use { sock ->
            val input = DataInputStream(sock.inputStream)
            val output = DataOutputStream(sock.outputStream)
            while (true) {
                val req = try {
                    UsbProxyProtocol.readRequest(input)
                } catch (e: Exception) {
                    Log.w(TAG, "read request failed: ${e.message}")
                    break
                } ?: break
                val resp = handle(device, req)
                if (resp == null) break // CLOSE
                try {
                    output.write(UsbProxyProtocol.encodeResponse(resp))
                    output.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "write response failed: ${e.message}")
                    break
                }
            }
        }
    }

    /** @return the response, or null for CLOSE (ends the connection). */
    private fun handle(device: String, req: UsbProxyProtocol.Request): UsbProxyProtocol.Response? =
        try {
            when (req) {
                UsbProxyProtocol.Request.GetDescriptors -> {
                    val raw = broker.rawDescriptors(device)
                    UsbProxyProtocol.Response(raw.size, raw)
                }
                is UsbProxyProtocol.Request.Control -> {
                    val r = broker.controlTransfer(
                        device, req.requestType, req.request, req.value, req.index,
                        req.data, req.length, req.timeoutMs,
                    )
                    UsbProxyProtocol.Response(r.bytesTransferred, r.data)
                }
                is UsbProxyProtocol.Request.Bulk -> {
                    val r = broker.bulkTransfer(device, req.endpoint, req.data, req.length, req.timeoutMs)
                    UsbProxyProtocol.Response(r.bytesTransferred, r.data)
                }
                UsbProxyProtocol.Request.Close -> null
            }
        } catch (e: Exception) {
            // Surface as a negative status the shim maps to errno; -1 is generic.
            Log.w(TAG, "request $req failed: ${e.message}")
            UsbProxyProtocol.Response(-1, ByteArray(0))
        }

    companion object {
        private const val TAG = "UsbProxyServer"
        /** Abstract-namespace socket name (LocalSocket prepends the NUL). */
        const val SOCKET_NAME: String = "haven-usb"
    }
}
