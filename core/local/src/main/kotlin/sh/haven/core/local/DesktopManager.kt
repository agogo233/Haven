package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.haven.core.local.proot.LaunchSpec
import sh.haven.core.wayland.WaylandBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DesktopManager"

/**
 * Manages multiple desktop environment processes running simultaneously.
 * Each desktop gets its own X11 display number and VNC port.
 * Native Wayland is limited to one instance (WaylandBridge is singleton).
 *
 * X11/VNC desktops use software rendering to avoid virgl contention
 * with the native Wayland compositor.
 */
@Singleton
class DesktopManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class DesktopState { STOPPED, STARTING, RUNNING, ERROR }

    data class DesktopInstance(
        val de: ProotManager.DesktopEnvironment,
        val displayNumber: Int,
        val vncPort: Int,
        val state: DesktopState,
        val errorMessage: String? = null,
    )

    private val _desktops = MutableStateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>>(emptyMap())
    val desktops: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>> = _desktops.asStateFlow()

    // Internal process tracking (not exposed in DesktopInstance)
    private val processes = mutableMapOf<ProotManager.DesktopEnvironment, Process>()

    // Display number allocation for X11 desktops
    private val usedDisplays = mutableSetOf<Int>()

    // Per-(distro, DE) VNC port preference. Empty when the user accepted
    // the default at install time; non-zero when the install dialog's
    // port field was edited. Reads/writes go through getPortPreference /
    // setPortPreference so the SharedPreferences instance is consulted
    // once per startDesktop rather than once per accessor.
    private val portPrefs by lazy {
        context.getSharedPreferences("desktop-port-prefs", Context.MODE_PRIVATE)
    }

    init {
        // Kill orphaned Xvnc processes from previous app instances
        killAllOrphanedXvnc()
    }

    private fun allocateDisplay(): Int {
        var display = 1
        while (display in usedDisplays) display++
        usedDisplays.add(display)
        return display
    }

    private fun releaseDisplay(display: Int) {
        usedDisplays.remove(display)
    }

    /**
     * Per-(distro, DE) port preference. Returns 0 when no preference is
     * stored — caller falls back to [allocateDisplay] + 5900. Stored
     * keys are `<distroId>_<deId>` so the same DE on different distros
     * (Alpine xfce4 vs Debian xfce4) keeps independent ports.
     */
    fun getPortPreference(distroId: String, deId: String): Int {
        return portPrefs.getInt("${distroId}_${deId}", 0)
    }

    fun setPortPreference(distroId: String, deId: String, port: Int) {
        portPrefs.edit().putInt("${distroId}_${deId}", port).apply()
    }

    /**
     * Suggest the next free VNC port for a new install on [distroId].
     * Considers ports currently in use by running DEs and ports already
     * pinned by other installed DEs' preferences. Returns the first
     * unused 5900+N. Defaults to 5901 when nothing is in play.
     */
    fun suggestNextVncPort(distroId: String): Int {
        val takenByRunning = _desktops.value.values.map { it.vncPort }.toSet()
        val takenByPrefs = portPrefs.all.entries
            .asSequence()
            .filter { it.key.startsWith("${distroId}_") }
            .mapNotNull { (it.value as? Int)?.takeIf { v -> v > 0 } }
            .toSet()
        val taken = takenByRunning + takenByPrefs
        var port = 5901
        while (port in taken) port++
        return port
    }

    /**
     * Start a desktop environment.
     * X11/VNC desktops use software rendering (no virgl).
     * Native Wayland uses the JNI compositor + virgl for GPU acceleration.
     */
    fun startDesktop(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        // Stop any existing instance first (handles stale state from crashes)
        if (_desktops.value.containsKey(de)) {
            stopDesktop(de)
        }

        when (de.spec.launch) {
            is LaunchSpec.NativeCompositor -> {
                if (WaylandBridge.nativeIsRunning()) {
                    _desktops.update { it + (de to DesktopInstance(
                        de, 0, 0, DesktopState.ERROR,
                        errorMessage = "Native compositor already running",
                    )) }
                    return
                }
                startNativeCompositor(de, shellCommand)
                return
            }
            is LaunchSpec.NestedWayland -> {
                // Phase 4: launch is structurally identical to X11Vnc up to
                // the per-DE process-spawn step. Allocate display + port
                // through the same code, then call launchNestedWayland().
                // Fall through to the shared X11/Nested branch below; the
                // per-DE process spawner is dispatched on launch type.
            }
            is LaunchSpec.X11Vnc -> {
                // Falls through to the X11/VNC launch below.
            }
        }

        // Honour the user's per-DE port preference (set at install time
        // in the Manage view) when present. The preference is stored as
        // an absolute port (5900-base); display = port - 5900 maps it
        // back into the X11 display-number space the Xvnc command line
        // expects. Falls back to allocateDisplay when unset OR when the
        // preferred display is already in use (multiple DEs pinned to
        // the same port — Manage view should prevent this but defend).
        val preferredPort = getPortPreference(prootManager.activeDistroId, de.spec.id)
        val display: Int
        val port: Int
        if (preferredPort in 5901..5999) {
            val candidateDisplay = preferredPort - 5900
            if (candidateDisplay !in usedDisplays) {
                display = candidateDisplay
                port = preferredPort
                usedDisplays.add(display)
            } else {
                Log.w(TAG, "preferred port $preferredPort for ${de.spec.id} already in use; falling back")
                display = allocateDisplay()
                port = 5900 + display
            }
        } else {
            display = allocateDisplay()
            port = 5900 + display
        }
        _desktops.update { it + (de to DesktopInstance(de, display, port, DesktopState.STARTING)) }

        try {
            val process = when (de.spec.launch) {
                is LaunchSpec.NestedWayland -> launchNestedWayland(de, display, port)
                is LaunchSpec.X11Vnc -> launchX11Desktop(de, display, shellCommand)
                is LaunchSpec.NativeCompositor ->
                    error("NativeCompositor handled above; unreachable")
            }
            processes[de] = process
            _desktops.update { it + (de to DesktopInstance(de, display, port, DesktopState.RUNNING)) }

            // Log output on a background thread
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "${de.label}[:$display]: $line")
                    }
                } catch (_: Exception) {}
                Log.d(TAG, "${de.label}[:$display] exited: ${process.waitFor()}")
                _desktops.update { current ->
                    val instance = current[de] ?: return@update current
                    if (instance.state == DesktopState.RUNNING) {
                        releaseDisplay(display)
                        processes.remove(de)
                        current - de
                    } else current
                }
            }, "desktop-${de.name}-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${de.label}", e)
            releaseDisplay(display)
            _desktops.update { it + (de to DesktopInstance(
                de, display, port, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    /**
     * Stop a running desktop environment.
     */
    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        val instance = _desktops.value[de] ?: return
        val launch = de.spec.launch
        if (launch is LaunchSpec.NativeCompositor) {
            if (WaylandBridge.nativeIsRunning()) {
                WaylandBridge.nativeStop()
            }
            WaylandBridge.nativeStopVirglServer()
            WaylandSocketHelper.tryRemoveSymlink()
        }
        processes[de]?.destroyForcibly()
        processes.remove(de)
        if (launch !is LaunchSpec.NativeCompositor) {
            // X11 launches leak Xvnc when the parent proot dies; nested
            // wlroots launches use the headless backend (no Xvnc) and the
            // compositor+wayvnc tree exits cleanly with destroyForcibly,
            // so only run the orphan-Xvnc sweep on X11.
            if (launch is LaunchSpec.X11Vnc) {
                killOrphanedXvnc(instance.displayNumber)
            }
            releaseDisplay(instance.displayNumber)
        }
        _desktops.update { it - de }
    }

    /**
     * Stop all running desktops.
     */
    fun stopAll() {
        _desktops.value.keys.toList().forEach { stopDesktop(it) }
    }

    /**
     * Get the VNC port for a running desktop, or null if not running.
     */
    fun getVncPort(de: ProotManager.DesktopEnvironment): Int? =
        _desktops.value[de]?.takeIf { it.state == DesktopState.RUNNING }?.vncPort

    // ---- X11/VNC desktop launch (software rendering, no virgl) ----

    private fun launchX11Desktop(
        de: ProotManager.DesktopEnvironment,
        display: Int,
        shellCommand: String,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }

        val launch = de.spec.launch as LaunchSpec.X11Vnc

        // Clean lock files for this display
        File(context.cacheDir, ".X${display}-lock").delete()
        File(rootHome, ".ICEauthority").apply { if (!exists()) createNewFile() }
        File(rootHome, ".Xauthority").apply { if (!exists()) createNewFile() }

        val passwdFile = File(rootfsDir, "root/.vnc/passwd")
        val useAuth = passwdFile.exists() && passwdFile.length() >= 8
        val securityArg = if (useAuth) {
            "-SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd"
        } else {
            "-SecurityTypes None"
        }
        Log.d(TAG, "Starting Xvnc :$display: useAuth=$useAuth")

        val shellCmd =
            "rm -f /tmp/.X${display}-lock /tmp/.X11-unix/X${display} && " +
                "Xvnc :${display} -geometry 1280x720 " +
                "$securityArg " +
                "-BlacklistThreshold 10000 " +
                "-localhost 0 & " +
                "sleep 3; " +
                "export DISPLAY=:${display}; " +
                "export HOME=/root; " +
                // NO virgl — software rendering for VNC desktops
                "${launch.startCommands} " +
                "wait"

        val prootArgs = mutableListOf(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
        )
        // /bin/sh works on both Alpine (symlink to busybox) and Debian
        // (symlink to dash). See ProotManager.runCommandInProot.
        prootArgs.addAll(listOf("-w", "/root", "/bin/sh", "-c", shellCmd))

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- Nested Wayland (wlroots headless backend + wayvnc) ----

    /**
     * Launch a wlroots-based compositor (Sway / Hyprland / niri) on
     * the headless backend inside the proot rootfs, exposed to the
     * outside world via `wayvnc` listening on [port]. The in-app VNC
     * client connects to localhost:port through the same path it uses
     * for Xvnc desktops, so the connect-side UI is unchanged.
     *
     * Auth model: this initial Phase 4 launches wayvnc with no
     * password (matches the existing X11 "-SecurityTypes None" fallback
     * when no .vnc/passwd exists). wayvnc's username/password auth
     * landed in 0.7+; supported distro packages range from 0.5 to 0.9,
     * so wiring auth is deferred until the version floor lifts.
     *
     * Singleton-friendly: each running NestedWayland DE gets its own
     * XDG_RUNTIME_DIR under `/tmp/xdg-runtime-<display>`. Multiple
     * nested compositors can run concurrently as long as their
     * displays differ.
     */
    private fun launchNestedWayland(
        de: ProotManager.DesktopEnvironment,
        display: Int,
        port: Int,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        File(rootfsDir, "root").mkdirs()

        val launch = de.spec.launch as LaunchSpec.NestedWayland
        val xdgInProot = "/tmp/xdg-runtime-$display"

        // Wlroots headless setup:
        //  - WLR_BACKENDS=headless: skip DRM/libinput entirely.
        //  - WLR_HEADLESS_OUTPUTS=1: create one virtual output at boot.
        //    Without this, wlroots starts with zero outputs and the
        //    compositor's config-time `output HEADLESS-1 ...` directive
        //    has nothing to apply to.
        //  - WLR_LIBINPUT_NO_DEVICES=1: belt-and-braces for the input
        //    side; headless backend already provides virtual seat.
        //  - XKB_DEFAULT_LAYOUT=us: avoid xkbcommon falling back to
        //    /etc/default/keyboard which proot environments lack.
        //
        // niri uses smithay rather than wlroots and reads only some of
        // the above; passing them is harmless and the niri-specific
        // setup (auto-detect headless when no Wayland/DRM is found)
        // does the rest.
        val shellCmd =
            "mkdir -p $xdgInProot && chmod 700 $xdgInProot && " +
                "export XDG_RUNTIME_DIR=$xdgInProot && " +
                "export HOME=/root && " +
                "export WLR_BACKENDS=headless && " +
                "export WLR_HEADLESS_OUTPUTS=1 && " +
                "export WLR_LIBINPUT_NO_DEVICES=1 && " +
                "export XKB_DEFAULT_LAYOUT=us && " +
                "export XKB_DEFAULT_RULES=evdev && " +
                "export XDG_SESSION_TYPE=wayland && " +
                // Start the compositor in the background. Stderr is
                // captured so a failure surfaces in logcat alongside
                // wayvnc's own diagnostics; the parent process is wayvnc
                // (foreground) so destroyForcibly() of the proot tree
                // tears down both halves.
                "(${launch.compositorCmd}) > $xdgInProot/compositor.log 2>&1 & " +
                "comp_pid=\$! ; " +
                // Wait up to 10 s for the wayland socket to appear. The
                // headless backend creates `wayland-1` as the first free
                // socket; we let the compositor pick the name and read
                // it back here.
                "i=0 ; while [ ! -e $xdgInProot/wayland-1 ] && [ \$i -lt 20 ]; do sleep 0.5; i=\$((i+1)); done ; " +
                "if [ ! -e $xdgInProot/wayland-1 ]; then " +
                    "echo '[haven] compositor did not create $xdgInProot/wayland-1 — log tail:' ; " +
                    "tail -n 50 $xdgInProot/compositor.log 2>&1 ; " +
                    "kill \$comp_pid 2>/dev/null ; " +
                    "exit 1 ; " +
                "fi ; " +
                "export WAYLAND_DISPLAY=wayland-1 ; " +
                // wayvnc 0.5 (Debian) doesn't accept --max-fps; pass only
                // the universal args. `--gpu-rendering=off` and disable
                // hardware encoding to avoid pulling on a non-existent
                // GPU under proot. wayvnc exits when the compositor dies.
                "exec wayvnc --render-cursor 0.0.0.0 $port"

        val prootArgs = mutableListOf(
            prootBin, "-0", "--link2symlink",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
        )
        prootArgs.addAll(listOf("-w", "/root", "/bin/sh", "-c", shellCmd))

        Log.d(TAG, "Starting ${de.label} (nested wayland) on port $port (display $display)")

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- Native Wayland compositor (uses JNI + virgl) ----

    private fun startNativeCompositor(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.STARTING)) }

        try {
            val bridge = WaylandBridge

            // Prepare XDG runtime dir (must be mode 0700, owned by app)
            val xdgDir = File(context.cacheDir, "wayland-xdg").apply {
                mkdirs()
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
            // Clean stale sockets
            File(xdgDir, "wayland-0").delete()
            File(xdgDir, "wayland-0.lock").delete()

            // Extract XKB data from assets on first use
            val xkbDir = File(context.filesDir, "xkb")
            if (!File(xkbDir, "rules/evdev").exists()) {
                Log.d(TAG, "Extracting XKB data...")
                extractAssetsDir(context, "xkb", xkbDir)
            }

            // Fontconfig pointing to system fonts
            val fontconfFile = File(context.cacheDir, "fonts.conf")
            if (!fontconfFile.exists()) {
                fontconfFile.writeText("""
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
                    <fontconfig>
                      <dir>/system/fonts</dir>
                      <cachedir>${context.cacheDir.absolutePath}/fontconfig-cache</cachedir>
                    </fontconfig>
                """.trimIndent())
                File(context.cacheDir, "fontconfig-cache").mkdirs()
            }

            // Set up native XWayland wrapper binary
            val xwaylandWrapper = File(
                context.applicationInfo.nativeLibraryDir, "libxwayland_wrapper.so",
            )
            val loaderPathXw = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootfsDir = prootManager.activeRootfsDir
            android.system.Os.setenv("WLR_XWAYLAND", xwaylandWrapper.absolutePath, true)
            android.system.Os.setenv("HAVEN_PROOT_BIN", prootManager.prootBinary ?: "", true)
            android.system.Os.setenv("HAVEN_PROOT_LOADER", loaderPathXw, true)
            android.system.Os.setenv("HAVEN_PROOT_ROOTFS", rootfsDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_CACHE_DIR", context.cacheDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_XDG_DIR", xdgDir.absolutePath, true)
            // Clean stale X11 sockets before compositor starts XWayland
            val x11UnixDir = File(context.cacheDir, ".X11-unix")
            x11UnixDir.deleteRecursively()
            x11UnixDir.mkdirs()
            Log.d(TAG, "Starting native compositor: XDG_RUNTIME_DIR=${xdgDir.absolutePath}")
            bridge.nativeStart(
                xdgRuntimeDir = xdgDir.absolutePath,
                xkbConfigRoot = xkbDir.absolutePath,
                fontconfigFile = fontconfFile.absolutePath,
            )

            // Wait for socket to appear
            val socket = File(xdgDir, "wayland-0")
            var waited = 0
            while (!socket.exists() && waited < 10) {
                Thread.sleep(500)
                waited++
            }
            if (socket.exists()) {
                Log.d(TAG, "Native compositor started, socket: ${socket.absolutePath}")
                WaylandSocketHelper.tryCreateSymlink(socket.absolutePath)
            } else {
                Log.e(TAG, "Native compositor socket not created after ${waited * 500}ms")
            }

            // Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps
            val virglBin = File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so")
            val virglSocket = File(context.cacheDir, ".virgl_test")
            virglSocket.delete()
            if (virglBin.canExecute()) {
                Log.d(TAG, "Starting virgl_test_server...")
                bridge.nativeStartVirglServer(virglBin.absolutePath, virglSocket.absolutePath)
            }

            // Start PRoot with Wayland clients, bind-mounting the native socket
            val prootBin = prootManager.prootBinary ?: run {
                _desktops.update { it + (de to DesktopInstance(
                    de, 0, 0, DesktopState.ERROR,
                    errorMessage = "PRoot not available",
                )) }
                return
            }
            val loaderPath = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootHome = File(rootfsDir, "root").apply { mkdirs() }

            val process = ProcessBuilder(
                prootBin, "-0", "--link2symlink",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${context.cacheDir.absolutePath}:/tmp",
                "-b", "${xdgDir.absolutePath}:/tmp/xdg-runtime",
                "-w", "/root",
                "/bin/sh", "-c",
                "export HOME=/root; " +
                    "export XDG_RUNTIME_DIR=/tmp/xdg-runtime; " +
                    "export XDG_DATA_HOME=/root/.local/share; " +
                    "export XDG_DATA_DIRS=/usr/local/share:/usr/share; " +
                    "export GDK_BACKEND=wayland,x11; " +
                    "export WAYLAND_DISPLAY=wayland-0; " +
                    "unset FONTCONFIG_FILE; " +
                    "unset XKB_CONFIG_ROOT; " +
                    "export TERM=xterm-256color; " +
                    "export SHELL=${shellCommand.split(" ").first()}; " +
                    // virgl GPU passthrough env vars
                    "export GALLIUM_DRIVER=virpipe; " +
                    "export VTEST_SOCKET=/tmp/.virgl_test; " +
                    // App launcher wrapper
                    "mkdir -p /usr/local/bin; printf '#!/bin/sh\\n\"\\$@\" &\\n' > /usr/local/bin/launch && chmod +x /usr/local/bin/launch; " +
                    // Set up XWayland for X11 app compatibility
                    "mkdir -p /tmp/.X11-unix; " +
                    "i=0; while ! ls /tmp/.X11-unix/X* >/dev/null 2>&1 && [ \$i -lt 5 ]; do sleep 1; i=\$((i+1)); done; " +
                    "if ls /tmp/.X11-unix/X* >/dev/null 2>&1; then " +
                        "XDISP=\$(ls /tmp/.X11-unix/ | sort -r | head -1 | sed 's/X//'); " +
                        "export DISPLAY=:\$XDISP; " +
                        "mkdir -p /etc/profile.d; echo \"export DISPLAY=:\$XDISP\" > /etc/profile.d/display.sh; " +
                    "fi; " +
                    // Auto-start desktop components if installed
                    "if [ -x /usr/bin/waybar ]; then " +
                        "dbus-run-session waybar >/tmp/waybar.log 2>&1 & sleep 2; " +
                    "fi; " +
                    "[ -x /usr/bin/thunar ] && thunar --daemon & " +
                    "foot -e $shellCommand 2>&1; " +
                    "wait",
            ).apply {
                environment().apply {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PROOT_LOADER", loaderPath)
                    remove("FONTCONFIG_FILE")
                    remove("XKB_CONFIG_ROOT")
                }
                redirectErrorStream(true)
            }.start()

            processes[de] = process
            _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.RUNNING)) }

            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "NativeWayland: $line")
                    }
                } catch (_: Exception) {}
                Log.d(TAG, "NativeWayland PRoot exited: ${process.waitFor()}")
                processes.remove(de)
                _desktops.update { it - de }
            }, "native-wayland-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native compositor", e)
            _desktops.update { it + (de to DesktopInstance(
                de, 0, 0, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    // ---- Helpers ----

    /** Kill orphaned Xvnc process for a specific display number. */
    private fun killOrphanedXvnc(display: Int) {
        try {
            val proc = ProcessBuilder("sh", "-c",
                "ps -A 2>/dev/null | grep 'Xvnc' | grep ':$display' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing orphaned Xvnc[:$display] PIDs: $pids")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedXvnc($display) failed: ${e.message}")
        }
    }

    /** Kill all orphaned Xvnc processes. */
    fun killAllOrphanedXvnc() {
        try {
            val proc = ProcessBuilder("sh", "-c",
                "ps -A 2>/dev/null | grep 'Xvnc' | grep -v grep | awk '{print \$2}'"
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing all orphaned Xvnc PIDs: $pids")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killAllOrphanedXvnc failed: ${e.message}")
        }
    }

    /** Recursively extract an assets directory to the filesystem. */
    private fun extractAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val assets = ctx.assets
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in list) {
                extractAssetsDir(ctx, "$assetPath/$child", File(destDir, child))
            }
        }
    }
}
