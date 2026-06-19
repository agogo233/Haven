package sh.haven.feature.connections.usb

import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-forwards a phone-attached USB device to a connected SSH host over USB/IP
 * (Slice 1 of the USB/IP feature). On connect: resolve the profile's VID:PID to
 * the live device, open it, start the userspace [UsbIpServer] on loopback,
 * register a remote port-forward for the usbip port, and best-effort `usbip
 * attach` on the remote so the device appears there as a real node — the touch
 * stays on the phone. On teardown: drop the forward (which detaches the remote
 * device as its usbip socket closes), stop the server, and close the device.
 *
 * The `-R` is registered through [SshSessionManager.applyPortForwards] (not a raw
 * `setPortForwardingR`) so it lands in the session's `activeForwards` and a silent
 * SSH reconnect re-binds it automatically — important for the remote-over-4G use
 * case where the tunnel drops often. It's non-critical: a failed re-bind never
 * fails the user's SSH (re)connect, the export is just reported down. A
 * per-session reconnect hook re-runs the host `usbip attach` each time the tunnel
 * comes back, since the device node on the host vanishes when the tunnel drops.
 *
 * One forwarded device at a time (the YubiKey case). [server] is the shared DI
 * singleton, so this never fights the MCP `start_usbip_export` path for the port.
 */
@Singleton
class UsbipConnectionForwarder @Inject constructor(
    private val broker: UsbBroker,
    private val server: UsbIpServer,
    private val sessions: SshSessionManager,
) {
    /** A live forward, returned by [attach] and handed back to [teardown]. */
    data class Handle(val deviceName: String, val busid: String, val remotePort: Int)

    /**
     * Attached phone USB devices as `(vidPid, label)` for the connection-settings
     * picker. Product names need USB permission, so an un-permissioned device
     * shows its VID:PID alone.
     */
    fun availableDevices(): List<Pair<String, String>> =
        broker.listDevices().map { d ->
            d.vidPid to (d.productName?.let { "$it (${d.vidPid})" } ?: d.vidPid)
        }

    /**
     * Resolve [vidPid] (e.g. "1050:0406") to a live phone device and forward it to
     * SSH session [sessionId]. Returns the handle once the export + remote tunnel
     * are up (the remote `usbip attach` is best-effort — it needs passwordless
     * sudo for usbip on the host), or null if no such device is attached /
     * permission is denied. [log] receives human-readable progress for the
     * connection log.
     */
    suspend fun attach(sessionId: String, vidPid: String, log: suspend (String) -> Unit): Handle? {
        val dev = broker.listDevices().firstOrNull { it.vidPid.equals(vidPid, ignoreCase = true) }
        val label = dev?.productName ?: vidPid
        if (dev == null) {
            log("USB forward: no $vidPid device attached to the phone — skipped")
            return null
        }
        if (!dev.hasPermission && !broker.requestPermission(dev.deviceName)) {
            log("USB forward: USB permission denied for $label")
            return null
        }
        return try {
            broker.openDevice(dev.deviceName)
            val port = server.start(dev.deviceName, bindAddress = LOOPBACK)
            val busid = busidOf(dev.deviceName)
            // Register the -R via the session manager so it lands in activeForwards
            // and a silent reconnect re-binds it. Non-critical: never fail the SSH
            // (re)connect on a USB bind failure.
            sessions.applyPortForwards(sessionId, listOf(usbForward(port)))
            // Re-run the host-side attach on every reconnect (the host's device
            // node went away when the tunnel dropped).
            sessions.setOnReconnected(sessionId) { reattachOnHost(sessionId, dev.deviceName, busid, label, log) }
            reattachOnHost(sessionId, dev.deviceName, busid, label, log)
            Handle(dev.deviceName, busid, port)
        } catch (e: Exception) {
            log("USB forward: failed for $label — ${e.message}")
            sessions.setOnReconnected(sessionId, null)
            runCatching { server.stop() }
            runCatching { broker.closeDevice(dev.deviceName) }
            null
        }
    }

    /** Tear down a forward established by [attach]. Best-effort; never throws. */
    suspend fun teardown(sessionId: String, handle: Handle, log: suspend (String) -> Unit) {
        // Drop the reconnect hook first so a reconnect racing teardown can't re-attach.
        sessions.setOnReconnected(sessionId, null)
        // Removing the remote forward closes the usbip socket, which makes the
        // remote vhci detach the device on its own — no host sudo needed, and we
        // don't touch any other usbip devices the host may have. Routed through
        // the session manager so it uses the live (post-reconnect) client and
        // drops the rule from activeForwards.
        runCatching { sessions.removePortForward(sessionId, usbForward(handle.remotePort)) }
        runCatching { server.stop() }
        runCatching { broker.closeDevice(handle.deviceName) }
        log("USB forward: ${handle.busid} torn down")
    }

    /**
     * Best-effort `usbip attach` on the host for [busid]. Re-resolves the live
     * session client (so it works on the post-reconnect client too) and no-ops if
     * the session is gone or the device was detached during a tunnel outage.
     */
    private suspend fun reattachOnHost(
        sessionId: String,
        deviceName: String,
        busid: String,
        label: String,
        log: suspend (String) -> Unit,
    ) {
        val client = sessions.getSession(sessionId)?.client ?: run {
            log("USB forward: no live session for $label — host attach skipped")
            return
        }
        if (!broker.isOpen(deviceName)) {
            log("USB forward: $label no longer attached to the phone — host attach skipped")
            return
        }
        val attach = runCatching {
            client.execCommand(
                "(sudo -n modprobe vhci_hcd 2>/dev/null; " +
                    "sudo -n usbip attach -r $LOOPBACK -b $busid) >/dev/null 2>&1; echo rc=$?",
            )
        }.getOrNull()
        if (attach?.stdout?.contains("rc=0") == true) {
            log("USB forward: $label attached on the remote (busid $busid)")
        } else {
            log(
                "USB forward: export + tunnel up for $label; run " +
                    "`sudo usbip attach -r 127.0.0.1 -b $busid` on the host to bind it " +
                    "(passwordless sudo for usbip unavailable)",
            )
        }
    }

    /** The non-critical REMOTE forward rule for the usbip [port] (loopback both ends). */
    private fun usbForward(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = USB_RULE_ID,
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = LOOPBACK,
        bindPort = port,
        targetHost = LOOPBACK,
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = false,
    )

    /** "/dev/bus/usb/001/002" -> "1-2" (the usbip busid; leading zeros stripped). */
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val USB_RULE_ID = "usbip-export"
    }
}
