package sh.haven.feature.sftp.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.feature.sftp.SftpEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "ReticulumFileBackend"

/**
 * [FileBackend] over an rnsh destination, using the Reticulum command-exec
 * substrate ([ReticulumTransport.execCommand]). Each operation runs one
 * POSIX command on the remote shell over its own Link — `ls`/`cat`/`mkdir`/
 * `mv`/`rm` — mirroring how SSH layers SFTP/SCP on remote exec, but carried
 * over the mesh.
 *
 * Two constraints shape the implementation, both verified against real rnsh
 * listeners (a full GNU host and a busybox OpenWRT router):
 *
 *  1. **busybox-portable commands only.** The router has no `stat` and no
 *     GNU `find -printf`, so directory listing parses `ls -la` output rather
 *     than per-entry `stat`.
 *  2. **rnsh exit codes are unreliable** (current markqvist/rnsh masks the
 *     wait status with `& 0xff`, reporting 0 for every non-signal exit). So
 *     every command is wrapped to print the remote shell's own `$?` as an
 *     `HRC=<code>` sentinel on stderr, and the real status is parsed from
 *     there — never from [ReticulumExecSession.exitCode].
 *
 * Known v1 limitations (named, not hidden): modified-time is reported as 0
 * (no portable per-entry time source without `stat`); symlinks are treated
 * as files; filenames containing newlines are unsupported; `openInputStream`
 * supports only `offset == 0` (no seek over the exec stream).
 */
class ReticulumFileBackend(
    private val transport: ReticulumTransport,
    private val destinationHash: String,
) : FileBackend {

    override val label: String = "Reticulum"

    /** Scope for the background pump behind [openInputStream]. */
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun list(path: String): List<SftpEntry> {
        val dir = if (path.isBlank()) "/" else path
        val res = run("ls -la ${q(dir)}")
        val out = res.stdout.decodeToString()
        val entries = ArrayList<SftpEntry>()
        for (line in out.lineSequence()) {
            if (line.isBlank() || line.startsWith("total ")) continue
            val e = parseLsLine(line, dir) ?: continue
            if (e.name == "." || e.name == "..") continue
            entries.add(e)
        }
        return entries
    }

    override suspend fun delete(path: String, isDirectory: Boolean) {
        // rm -rf covers both files and (recursively) directories — matches
        // the FileBackend contract; isDirectory is unused here.
        run("rm -rf ${q(path)}")
    }

    override suspend fun mkdir(path: String) {
        run("mkdir -p ${q(path)}")
    }

    override suspend fun rename(from: String, to: String) {
        run("mv ${q(from)} ${q(to)}")
    }

    override suspend fun readBytes(path: String): ByteArray =
        run("cat ${q(path)}", timeoutMs = TRANSFER_TIMEOUT_MS).stdout

    override suspend fun writeBytes(path: String, data: ByteArray) {
        // Upload needs a stdin-EOF (so the remote `cat` finishes), but sending
        // a stdin EOF over rnsh currently stalls the command's exit reporting
        // (the read path works precisely because it never closes stdin). Fail
        // fast rather than hang the upload. Read/download/mkdir/rename/delete
        // all work. See project_reticulum_channel_deadlock.md follow-up.
        throw IOException(
            "Reticulum file upload is not yet supported (stdin-EOF over rnsh " +
                "stalls the command exit). Read, download, mkdir, rename, and " +
                "delete work."
        )
    }

    override suspend fun stat(path: String): SftpEntry {
        val res = run("ls -lad ${q(path)}", allowFailure = true)
        if (res.rc != 0) {
            throw FileNotFoundException("stat $path: ${res.stderr.ifBlank { "rc=${res.rc}" }}")
        }
        val line = res.stdout.decodeToString().lineSequence()
            .firstOrNull { it.isNotBlank() && !it.startsWith("total ") }
            ?: throw FileNotFoundException(path)
        val parent = path.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" }
        val parsed = parseLsLine(line, parent)
            ?: throw IOException("could not parse stat for $path")
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        return parsed.copy(name = name, path = path)
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream {
        if (offset > 0L) {
            // No seek over the exec stream in v1; callers that only need
            // offset 0 (the documented universal case) are unaffected.
            throw UnsupportedOperationException(
                "Reticulum backend does not support non-zero read offset"
            )
        }
        // True streaming: pump remote stdout into a pipe so arbitrarily large
        // files don't materialise in memory. No sentinel wrapper here — the
        // stream's success is the bytes arriving; the reader sees EOF on close.
        val exec = transport.execCommand(destinationHash, listOf("sh", "-c", "cat ${q(path)}"))
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        streamScope.launch {
            try {
                exec.stdout.collect { pipeOut.write(it) }
                pipeOut.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "openInputStream pump failed for $path", e)
            } finally {
                try { pipeOut.close() } catch (_: Exception) {}
                exec.close()
            }
        }
        return pipeIn
    }

    // --- exec plumbing ---------------------------------------------------

    private data class ExecResult(val stdout: ByteArray, val stderr: String, val rc: Int)

    /**
     * Run [script] on the remote shell, optionally feeding [stdin], and
     * return its stdout/stderr/real-exit-code. Throws [IOException] on a
     * non-zero exit unless [allowFailure] is set (used by `stat`, which maps
     * failure to [FileNotFoundException] itself).
     */
    private suspend fun run(
        script: String,
        stdin: ByteArray? = null,
        timeoutMs: Long = METADATA_TIMEOUT_MS,
        allowFailure: Boolean = false,
    ): ExecResult {
        val res = runRaw(script, stdin, timeoutMs)
        if (res.rc != 0 && !allowFailure) {
            throw IOException("reticulum: '$script' failed (rc=${res.rc}): ${res.stderr.trim()}")
        }
        return res
    }

    private suspend fun runRaw(
        script: String,
        stdin: ByteArray?,
        timeoutMs: Long,
    ): ExecResult = withTimeout(timeoutMs) {
        // Append the real exit status as a sentinel on stderr (rnsh's own
        // exit code is unreliable — see class kdoc). stdout stays pristine.
        val wrapped = listOf("sh", "-c", "$script; printf '${SENTINEL_PRE}%s${SENTINEL_POST}' \"\$?\" 1>&2")
        val exec = transport.execCommand(destinationHash, wrapped)
        try {
            coroutineScope {
                val outD = async {
                    val buf = ByteArrayOutputStream()
                    exec.stdout.collect { buf.write(it) }
                    buf.toByteArray()
                }
                val errD = async {
                    val buf = ByteArrayOutputStream()
                    exec.stderr.collect { buf.write(it) }
                    buf.toByteArray()
                }

                if (stdin != null) {
                    var off = 0
                    while (off < stdin.size) {
                        val end = minOf(off + STDIN_CHUNK, stdin.size)
                        exec.writeStdin(stdin.copyOfRange(off, end))
                        off = end
                    }
                    // Only signal stdin EOF when we actually fed stdin. Sending
                    // a stdin-EOF to a command that never reads stdin stalls the
                    // exit reporting over rnsh (the read/list/etc ops produce no
                    // stdin, so they must NOT close it).
                    exec.closeStdin()
                }

                exec.exitCode.await() // completes when the command exits (channels close)
                val out = outD.await()
                val errStr = errD.await().decodeToString()

                val match = SENTINEL_RE.findAll(errStr).lastOrNull()
                    ?: throw IOException(
                        "reticulum exec: no result sentinel (command truncated or link dropped); " +
                            "stdout=${out.size}B stderr='${errStr.take(120)}'"
                    )
                val rc = match.groupValues[1].toIntOrNull()
                    ?: throw IOException("reticulum exec: malformed result sentinel")
                val errClean = errStr.substring(0, match.range.first).trimEnd()
                ExecResult(out, errClean, rc)
            }
        } finally {
            exec.close()
        }
    }

    /** Single-quote a path for POSIX `sh`, escaping embedded single quotes. */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Parse one `ls -la` line into an [SftpEntry]. Layout (GNU and busybox
     * agree): `mode links owner group size month day time/year name…`. The
     * name is everything after the 8th column (preserving internal spaces).
     */
    private fun parseLsLine(line: String, dir: String): SftpEntry? {
        val parts = line.trim().split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val mode = parts[0]
        val typeChar = mode.firstOrNull() ?: return null
        val isDir = typeChar == 'd'
        val isLink = typeChar == 'l'
        val size = parts[4].toLongOrNull() ?: 0L
        var name = parts[8]
        if (isLink) name = name.substringBefore(" -> ")
        if (name.isEmpty()) return null
        val full = if (dir == "/" || dir.isEmpty()) "/$name" else "${dir.trimEnd('/')}/$name"
        return SftpEntry(
            name = name,
            path = full,
            isDirectory = isDir, // symlink-to-dir treated as file in v1
            size = size,
            modifiedTime = 0L,   // no portable per-entry mtime without stat
            permissions = mode,
            owner = parts[2],
            group = parts[3],
        )
    }

    companion object {
        /** Max stdin chunk per stream-data message (stays under the link MDU). */
        private const val STDIN_CHUNK = 400

        private const val METADATA_TIMEOUT_MS = 120_000L
        private const val TRANSFER_TIMEOUT_MS = 300_000L

        // Exit sentinel printed on stderr only (stdout stays binary-pristine).
        // The last "HRC=<code>" in stderr is the real remote `$?`.
        private const val SENTINEL_PRE = "HRC="
        private const val SENTINEL_POST = ""
        private val SENTINEL_RE = Regex("HRC=(-?\\d+)")
    }
}
