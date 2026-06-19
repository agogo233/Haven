package sh.haven.core.usb

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Userspace USB/IP server. Exports one [UsbBroker]-owned device over TCP so a
 * stock `usbip attach` client on a remote Linux host imports it as a real USB
 * device node — no per-app shim, every guest app (ssh, libfido2, browsers) sees
 * it, touch happens on the phone.
 *
 * This is the remote-host counterpart to [UsbProxyServer] (which serves the
 * proot guest over a LocalSocket): the proot guest can't be a usbip *client*
 * because the Android kernel ships no `vhci-hcd`, so usbip is the over-the-wire
 * path and the bespoke proxy stays the local-guest path.
 *
 * One server instance exports one device. URBs are serialized **per (endpoint,
 * direction)** lane: same endpoint runs in submission order (a multi-frame
 * CTAPHID write — init + continuation frames on interrupt-OUT — must reach the
 * device in order or it rejects the whole message as INVALID_LENGTH), while
 * different endpoints run concurrently so a blocking interrupt-IN (e.g. the
 * composite key's idle CCID status poll, or a FIDO key awaiting touch) never
 * starves the FIDO traffic on another endpoint.
 *
 * UNLINK: Android has no per-transfer cancel, so an unlinked transfer runs to
 * completion in its pool thread — but its late RET_SUBMIT MUST be dropped. A
 * RET_SUBMIT for a seqnum the client already unlinked makes the vhci client
 * tear down the *whole* device ("cannot find a urb of seqnum N"), which is what
 * killed forwarding of a composite key (idle CCID/FIDO interrupt-INs get
 * unlinked, then their stale completion desynced the stream). We track unlinked
 * seqnums and suppress their reply; the thread just exits quietly.
 */
@Singleton
class UsbIpServer @Inject constructor(
    private val broker: UsbBroker,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var deviceName: String? = null

    val isRunning: Boolean get() = serverSocket != null
    val boundPort: Int? get() = serverSocket?.localPort

    /** The exported device's `/dev/bus/usb/...` name, or null when stopped. */
    val exportedDeviceName: String? get() = deviceName

    private val clients = java.util.concurrent.atomic.AtomicInteger(0)

    /** Remote usbip clients currently connected (>0 ⇒ a host has attached). */
    val clientCount: Int get() = clients.get()

    /**
     * Bind the usbip server for [deviceName] (already opened via
     * [UsbBroker.openDevice]). [bindAddress] null binds all interfaces (LAN +
     * loopback); pass "127.0.0.1" to keep it loopback-only behind a tunnel.
     * Returns the bound port.
     */
    @Synchronized
    fun start(deviceName: String, port: Int = USBIP_PORT, bindAddress: String? = null): Int {
        if (serverSocket != null && this.deviceName == deviceName) return serverSocket!!.localPort
        stop()
        val addr = bindAddress?.let { InetAddress.getByName(it) }
        val socket = ServerSocket(port, BACKLOG, addr)
        serverSocket = socket
        this.deviceName = deviceName
        thread(name = "haven-usbip-accept", isDaemon = true) {
            Log.i(TAG, "usbip server listening on ${addr ?: "0.0.0.0"}:${socket.localPort} for $deviceName")
            while (serverSocket === socket) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (serverSocket === socket) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                thread(name = "haven-usbip-conn", isDaemon = true) { serve(client, deviceName) }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        deviceName = null
    }

    private fun serve(client: Socket, device: String) {
        clients.incrementAndGet()
        try {
            serveConnection(client, device)
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun serveConnection(client: Socket, device: String) {
        // One single-thread lane per (endpoint, direction): in-order within a lane,
        // concurrent across lanes. Keyed by ep<<1|direction (DIR_IN=1, DIR_OUT=0).
        val lanes = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ExecutorService>()
        fun laneFor(ep: Int, direction: Int): java.util.concurrent.ExecutorService =
            lanes.getOrPut((ep shl 1) or direction) { Executors.newSingleThreadExecutor() }
        // Seqnums the client has unlinked: their (late) RET_SUBMIT must be dropped.
        val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        client.use { sock ->
            sock.tcpNoDelay = true
            val input = DataInputStream(sock.getInputStream().buffered())
            val output = DataOutputStream(sock.getOutputStream())
            val writeLock = Any()

            // Phase 1: op handshake (expect a single IMPORT).
            val op = try {
                UsbIpProtocol.readOpRequest(input)
            } catch (e: Exception) {
                Log.w(TAG, "op handshake failed: ${e.message}"); return
            } ?: return
            when (op) {
                is UsbIpProtocol.OpRequest.Import -> {
                    val dev = runCatching { describeForImport(device) }.getOrNull()
                    synchronized(writeLock) {
                        output.write(UsbIpProtocol.encodeImportReply(dev)); output.flush()
                    }
                    if (dev == null) {
                        Log.w(TAG, "IMPORT of $device failed — no descriptors"); return
                    }
                    Log.i(TAG, "IMPORT ${op.busid} -> $device ok; entering URB loop")
                }
            }

            // Phase 2: URB stream.
            val backend = BrokerBackend(broker, device)
            while (true) {
                // Device unplugged / re-enumerated (e.g. esptool resetting an ESP32-S3):
                // stop rather than spin replying EPIPE on a dead handle. Exiting the
                // client.use block closes the socket and drains the lanes below.
                if (!broker.isOpen(device)) {
                    Log.i(TAG, "device $device no longer open — ending export connection"); break
                }
                val urb = try {
                    UsbIpProtocol.readUrb(input)
                } catch (e: Exception) {
                    Log.w(TAG, "URB read failed: ${e.message}"); break
                } ?: break
                when (urb) {
                    is UsbIpProtocol.Urb.Submit -> laneFor(urb.ep, urb.direction).execute {
                        // Interrupt/bulk IN stays pending (short-poll) until data or unlink;
                        // OUT/control is a single bounded transfer.
                        val reply = if (urb.direction == UsbIpProtocol.DIR_IN && urb.ep != 0) {
                            pollIn(urb, backend, cancelled, IN_POLL_MS)
                        } else {
                            bridgeSubmit(urb, backend, TRANSFER_TIMEOUT_MS)
                        }
                        synchronized(writeLock) {
                            // Drop the reply if the client unlinked this URB while it ran —
                            // a stale RET_SUBMIT desyncs vhci and disconnects the device.
                            val wasCancelled = cancelled.remove(urb.seqnum)
                            if (reply != null && !wasCancelled) {
                                runCatching { output.write(reply); output.flush() }
                            }
                        }
                    }
                    is UsbIpProtocol.Urb.Unlink -> synchronized(writeLock) {
                        cancelled.add(urb.unlinkSeqnum)
                        runCatching {
                            output.write(UsbIpProtocol.encodeUnlinkReply(urb.seqnum, status = 0)); output.flush()
                        }
                    }
                }
            }
        }
        lanes.values.forEach { it.shutdownNow() }
    }

    /** Build the OP_REP_IMPORT device record from the brokered descriptors. */
    private fun describeForImport(device: String): UsbIpProtocol.Device {
        val raw = broker.rawDescriptors(device)
        require(raw.size >= 18) { "short device descriptor (${raw.size} bytes)" }
        fun u8(i: Int) = raw[i].toInt() and 0xFF
        fun u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
        // busnum/devnum from "/dev/bus/usb/BBB/DDD".
        val parts = device.trimEnd('/').split('/')
        val busnum = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val devnum = parts.lastOrNull()?.toIntOrNull() ?: 1
        // Active-config descriptor follows the 18-byte device descriptor.
        val configValue = if (raw.size >= 18 + 6) u8(18 + 5) else 1
        val config = if (raw.size > 18) raw.copyOfRange(18, raw.size) else ByteArray(0)
        return UsbIpProtocol.Device(
            busid = "$busnum-$devnum",
            busnum = busnum,
            devnum = devnum,
            speed = USB_SPEED_FULL, // FIDO keys are full-speed; cosmetic to function
            idVendor = u16le(8),
            idProduct = u16le(10),
            bcdDevice = u16le(12),
            deviceClass = u8(4),
            deviceSubClass = u8(5),
            deviceProtocol = u8(6),
            configurationValue = configValue,
            numConfigurations = u8(17),
            // FIDO keys export interface 0 only (CCID stripped, see filterToFidoInterface);
            // every other device keeps its real count so multi-interface classes
            // (e.g. CDC serial: control + data) fully enumerate on the host.
            numInterfaces = if (interface0IsHid(config)) 1
                else if (config.size >= 5) config[4].toInt() and 0xFF else 1,
        )
    }

    companion object {
        private const val TAG = "UsbIpServer"
        const val USBIP_PORT = 3240
        private const val BACKLOG = 4
        private const val USB_SPEED_FULL = 2
        /** Upper bound for a single OUT/control transfer; real ones complete in ms. */
        private const val TRANSFER_TIMEOUT_MS = 5_000
        /** Slice for polling an interrupt/bulk IN so an idle read never wedges its lane. */
        private const val IN_POLL_MS = 250

        /**
         * Translate one SUBMIT into a backend transfer and encode the
         * RET_SUBMIT reply. Pure but for [backend]; unit-tested with a fake.
         */
        fun bridgeSubmit(urb: UsbIpProtocol.Urb.Submit, backend: UsbIpBackend, timeoutMs: Int): ByteArray = try {
            val isIn = urb.direction == UsbIpProtocol.DIR_IN
            val inData = if (urb.ep == 0) {
                // Control: the 8-byte setup packet is USB-native little-endian.
                val s = urb.setup
                fun u8(i: Int) = s[i].toInt() and 0xFF
                val rt = u8(0); val req = u8(1)
                val wValue = u8(2) or (u8(3) shl 8)
                val wIndex = u8(4) or (u8(5) shl 8)
                val wLength = u8(6) or (u8(7) shl 8)
                if (rt == 0x80 && req == 0x06 && (wValue shr 8) == 0x02) {
                    // GET_DESCRIPTOR(CONFIGURATION): export only interface 0 (FIDO).
                    // Fetch the real descriptor in full, strip the CCID/other
                    // interfaces, then honour the client's requested length.
                    val full = backend.control(0x80, 0x06, wValue, wIndex, ByteArray(0), 0xFF, timeoutMs)
                    filterToFidoInterface(full).let { it.copyOf(minOf(it.size, wLength)) }
                } else {
                    backend.control(rt, req, wValue, wIndex, urb.out, wLength, timeoutMs)
                }
            } else {
                val address = urb.ep or if (isIn) 0x80 else 0x00
                backend.bulk(address, urb.out, urb.transferBufferLength, timeoutMs)
            }
            val actualLength = if (isIn) inData.size else urb.transferBufferLength
            UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = 0, actualLength = actualLength, inData = if (isIn) inData else ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "URB seq=${urb.seqnum} ep=${urb.ep} failed: ${e.message}")
            UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = -EPIPE, actualLength = 0, inData = ByteArray(0))
        }

        /**
         * Service an interrupt/bulk IN URB by polling in [pollMs] slices, keeping
         * it pending (no reply) until data arrives or the client unlinks it. This
         * is the correct interrupt-endpoint semantic and, given Android serializes
         * transfers per connection, stops an idle standing read (e.g. a composite
         * key's CCID status poll, or a FIDO key between commands) from wedging its
         * lane or disrupting the endpoint with a long timeout-failure. Returns the
         * RET_SUBMIT bytes once data arrives, or null if unlinked/interrupted first.
         */
        fun pollIn(urb: UsbIpProtocol.Urb.Submit, backend: UsbIpBackend, cancelled: Set<Int>, pollMs: Int): ByteArray? {
            val address = urb.ep or 0x80
            // backend.isAlive() guard: a standing interrupt-IN poll on an idle
            // connection must exit when the device is unplugged, not spin on
            // failing transfers until the client happens to send another URB.
            while (urb.seqnum !in cancelled && !Thread.currentThread().isInterrupted && backend.isAlive()) {
                val data = try {
                    backend.bulk(address, urb.out, urb.transferBufferLength, pollMs)
                } catch (e: Exception) {
                    ByteArray(0) // NAK / no data this slice — keep the URB pending
                }
                if (data.isNotEmpty()) {
                    return UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = 0, actualLength = data.size, inData = data)
                }
            }
            return null
        }

        /**
         * Reduce a full CONFIGURATION descriptor to just interface 0 (the FIDO
         * HID function), dropping a composite key's CCID/other interfaces. The
         * remote host then never binds or polls them — their traffic (e.g. pcscd
         * hammering the smartcard interface) would otherwise starve FIDO on
         * Android's serialized [UsbDeviceConnection]. Left unchanged if [config]
         * isn't a configuration descriptor or is too short to parse.
         */
        fun filterToFidoInterface(config: ByteArray): ByteArray {
            if (config.size < 9 || (config[1].toInt() and 0xFF) != 0x02) return config
            // Interface-0-only is the FIDO case (a composite key whose CCID interface
            // would starve FIDO on Android's serialized connection). Any other device
            // keeps its whole config — e.g. a CDC serial adapter's data interface is
            // #1, and stripping it drops the bulk endpoints the host's cdc_acm needs.
            if (!interface0IsHid(config)) return config
            val body = ArrayList<Byte>()
            var i = 9
            var keep = false
            while (i + 2 <= config.size) {
                val len = config[i].toInt() and 0xFF
                if (len < 2 || i + len > config.size) break
                if ((config[i + 1].toInt() and 0xFF) == 0x04) { // INTERFACE descriptor
                    keep = (config[i + 2].toInt() and 0xFF) == 0 // keep only interface 0
                }
                if (keep) for (j in i until i + len) body.add(config[j])
                i += len
            }
            if (body.isEmpty()) return config // no interface 0 found — don't break enumeration
            val total = 9 + body.size
            val out = ByteArray(total)
            System.arraycopy(config, 0, out, 0, 9)
            for (k in body.indices) out[9 + k] = body[k]
            out[2] = (total and 0xFF).toByte()         // wTotalLength lo
            out[3] = ((total shr 8) and 0xFF).toByte() // wTotalLength hi
            out[4] = 1                                  // bNumInterfaces
            return out
        }

        /**
         * True when the CONFIGURATION descriptor's interface 0 is HID (class 0x03) —
         * the only case we strip to interface-0-only (a FIDO key). Walks to the first
         * INTERFACE descriptor with bInterfaceNumber 0 and reads its bInterfaceClass.
         */
        fun interface0IsHid(config: ByteArray): Boolean {
            if (config.size < 9 || (config[1].toInt() and 0xFF) != 0x02) return false
            var i = 9
            while (i + 2 <= config.size) {
                val len = config[i].toInt() and 0xFF
                if (len < 2 || i + len > config.size) break
                if ((config[i + 1].toInt() and 0xFF) == 0x04 && len >= 6 &&
                    (config[i + 2].toInt() and 0xFF) == 0
                ) {
                    return (config[i + 5].toInt() and 0xFF) == 0x03
                }
                i += len
            }
            return false
        }

        /** -EPIPE: the closest generic "transfer failed" errno the vhci client maps. */
        private const val EPIPE = 32
    }
}

/** Transfer backend the URB bridge drives — [BrokerBackend] in prod, a fake in tests. */
interface UsbIpBackend {
    /** Endpoint-0 control transfer; returns IN data (empty for OUT). Throws on failure. */
    fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray

    /** Bulk/interrupt on the full endpoint address; returns IN data (empty for OUT). Throws on failure. */
    fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray

    /** False once the device handle is gone (unplug / re-enumeration), so standing polls bail. */
    fun isAlive(): Boolean
}

/** [UsbIpBackend] backed by the Android [UsbBroker]. */
class BrokerBackend(private val broker: UsbBroker, private val deviceName: String) : UsbIpBackend {
    override fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray =
        broker.controlTransfer(deviceName, requestType, request, value, index, out, length, timeoutMs).data

    override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray =
        broker.bulkTransfer(deviceName, endpointAddress, out, length, timeoutMs).data

    override fun isAlive(): Boolean = broker.isOpen(deviceName)
}
