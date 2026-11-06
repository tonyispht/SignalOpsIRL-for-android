package com.example.signalopsirl

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Drives an external USB Bluetooth adapter directly, because Android only exposes its own built-in
 * radio to apps. The adapter is opened through the USB host API and spoken to in raw HCI: reset,
 * (Realtek only) firmware download, then LE active scanning. Advertising reports come back as [Advert]s
 * and go through the same pipeline as the phone's own scans.
 */

// ---- advertisement parsing ----------------------------------------------------------------------

object AdParser {
    private const val BASE = "00001000800000805F9B34FB"

    /** Splits raw advertising data (AD structures) into the pieces the classifier uses. */
    fun parse(address: String, rssi: Int, data: ByteArray): Advert {
        var name: String? = null
        var shortName: String? = null
        val mfg = LinkedHashMap<Int, ByteArray>()
        val svcs = LinkedHashSet<String>()
        var i = 0
        while (i < data.size) {
            val l = data[i].toInt() and 0xFF
            if (l == 0 || i + 1 + l > data.size) break
            val type = data[i + 1].toInt() and 0xFF
            val s = i + 2
            val e = i + 1 + l   // payload is [s, e)
            when (type) {
                0x08 -> shortName = String(data, s, e - s, Charsets.UTF_8)
                0x09 -> name = String(data, s, e - s, Charsets.UTF_8)
                0x02, 0x03 -> {
                    var p = s
                    while (p + 2 <= e) {
                        val u = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                        svcs.add("0000" + "%04X".format(u) + BASE)
                        p += 2
                    }
                }
                0x04, 0x05 -> {
                    var p = s
                    while (p + 4 <= e) {
                        svcs.add(hexReversed(data, p, 4) + BASE)
                        p += 4
                    }
                }
                0x06, 0x07 -> {
                    var p = s
                    while (p + 16 <= e) {
                        svcs.add(hexReversed(data, p, 16))
                        p += 16
                    }
                }
                0xFF -> if (e - s >= 2) {
                    val id = (data[s].toInt() and 0xFF) or ((data[s + 1].toInt() and 0xFF) shl 8)
                    mfg[id] = data.copyOfRange(s + 2, e)
                }
            }
            i += l + 1
        }
        return Advert(address, rssi, name ?: shortName, mfg, svcs.toList(), raw = data, rawPart = true)
    }

    /** Bytes are little-endian on the air; UUID text is big-endian. */
    private fun hexReversed(d: ByteArray, off: Int, n: Int): String {
        val sb = StringBuilder(n * 2)
        for (k in n - 1 downTo 0) sb.append("%02X".format(d[off + k].toInt() and 0xFF))
        return sb.toString()
    }
}

// ---- Realtek firmware -----------------------------------------------------------------------------

/** Imported firmware for Realtek adapters (e.g. TP-Link UB500 = RTL8761B), stored in app storage. */
object RtlFw {
    private fun fwFile(ctx: Context) = File(ctx.filesDir, "rtl_fw.bin")
    private fun cfgFile(ctx: Context) = File(ctx.filesDir, "rtl_cfg.bin")

    fun load(ctx: Context): Pair<ByteArray, ByteArray?>? {
        val f = fwFile(ctx)
        if (!f.exists()) return null
        val c = cfgFile(ctx)
        return f.readBytes() to (if (c.exists()) c.readBytes() else null)
    }

    fun summary(ctx: Context): String {
        val f = fwFile(ctx)
        if (!f.exists()) return "FIRMWARE: NONE IMPORTED"
        val c = cfgFile(ctx)
        return "FIRMWARE: ${f.length() / 1024} KB" + if (c.exists()) " + CONFIG ${c.length()} B" else ", NO CONFIG"
    }

    fun clear(ctx: Context) {
        fwFile(ctx).delete()
        cfgFile(ctx).delete()
    }

    /** Blocking; call off the main thread. Returns a status line. */
    fun import(ctx: Context, input: InputStream, config: Boolean): String {
        val bytes = input.readBytes()
        if (bytes.size < 4) return "FILE TOO SMALL"
        if (bytes[0] == 0x28.toByte() && bytes[1] == 0xB5.toByte() && bytes[2] == 0x2F.toByte() && bytes[3] == 0xFD.toByte()) {
            return "FILE IS ZSTD-COMPRESSED. RUN zstd -d ON IT FIRST"
        }
        if (!config) {
            val head = String(bytes, 0, minOf(8, bytes.size), Charsets.ISO_8859_1)
            if (head == "RTBTCore") return "NEWER (V2) FIRMWARE FORMAT IS NOT SUPPORTED"
            if (head != "Realtech") return "NOT A REALTEK FIRMWARE FILE"
        }
        (if (config) cfgFile(ctx) else fwFile(ctx)).writeBytes(bytes)
        return (if (config) "CONFIG" else "FIRMWARE") + " IMPORTED (${bytes.size} BYTES)"
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, o: Int) =
        (u16(d, o).toLong()) or (u16(d, o + 2).toLong() shl 16)

    /** Picks the patch for this chip's ROM version out of a "Realtech" epatch file and appends the config. */
    fun build(fw: ByteArray, cfg: ByteArray?, romVersion: Int): ByteArray {
        require(fw.size >= 14 && String(fw, 0, 8, Charsets.ISO_8859_1) == "Realtech") { "not a Realtek v1 firmware" }
        val n = u16(fw, 12)
        val chipOff = 14
        val lenOff = chipOff + 2 * n
        val offOff = lenOff + 2 * n
        require(offOff + 4 * n <= fw.size) { "firmware file is truncated" }
        for (i in 0 until n) {
            if (u16(fw, chipOff + 2 * i) == romVersion + 1) {
                val len = u16(fw, lenOff + 2 * i)
                val off = u32(fw, offOff + 4 * i).toInt()
                require(len >= 4 && off >= 0 && off + len <= fw.size) { "bad patch entry" }
                val out = ByteArrayOutputStream()
                out.write(fw, off, len - 4)
                out.write(fw, 8, 4)          // firmware version replaces the last 4 bytes of the patch
                if (cfg != null) out.write(cfg)
                return out.toByteArray()
            }
        }
        throw IllegalArgumentException("no patch for ROM version $romVersion (wrong firmware file?)")
    }
}

// ---- adapter discovery ------------------------------------------------------------------------------

object UsbAdapters {
    private const val ACTION_PERMISSION = "com.example.signalopsirl.USB_PERMISSION"

    fun manager(ctx: Context) = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun id(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    fun label(d: UsbDevice) = (d.productName ?: "USB BLUETOOTH") + " " + id(d)

    /** True when the device has a standard Bluetooth HCI interface (class E0, subclass 01, protocol 01). */
    fun hasHci(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == 0xE0 && f.interfaceSubclass == 1 && f.interfaceProtocol == 1) return true
        }
        return false
    }

    fun list(ctx: Context): List<UsbDevice> =
        manager(ctx).deviceList.values.filter { hasHci(it) }.sortedBy { id(it) }

    fun find(ctx: Context, id: String): UsbDevice? =
        manager(ctx).deviceList.values.firstOrNull { id(it) == id }

    fun hasPermission(ctx: Context, d: UsbDevice) = manager(ctx).hasPermission(d)

    /** Shows Android's "Allow access to the USB device?" dialog. */
    fun requestPermission(ctx: Context, d: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags
        )
        manager(ctx).requestPermission(d, pi)
    }
}

// ---- the USB HCI scanner ----------------------------------------------------------------------------

class UsbHciScanner(
    private val ctx: Context,
    private val device: UsbDevice,
    /** Try BLE 5 extended scanning (1M + Coded PHY); falls back to legacy scanning if the adapter can't. */
    private val extended: Boolean,
    private val onAdvert: (Advert) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val usb = UsbAdapters.manager(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val cmdExec = Executors.newSingleThreadExecutor()

    private var conn: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var reader: Thread? = null

    @Volatile private var running = false
    @Volatile private var closed = false

    /** True once the adapter accepted the extended scan commands (then 0x2042 starts/stops scanning, not 0x200C). */
    @Volatile private var extScan = false

    /** What the adapter ended up scanning with, for the status line. */
    @Volatile var modeText: String = "USB: LEGACY ONLY"
        private set

    /** Extended adverts can arrive in pieces; the pieces wait here, keyed by address / set id / scan-response flag. */
    private class Frag(val data: ByteArrayOutputStream, var t: Long)
    private val frags = HashMap<String, Frag>()

    // HCI events arrive over a small interrupt endpoint in pieces; this reassembles them.
    private val rxBuf = ByteArray(2048)
    private var rxLen = 0
    private val pkt = ByteArray(512)

    // ---- public API -------------------------------------------------------------------------------

    /** Blocking (firmware download can take seconds); call off the main thread. Returns an error or null. */
    fun open(): String? {
        if (!usb.hasPermission(device)) return "USB permission not granted"
        val c = usb.openDevice(device) ?: return "Could not open the USB device"
        conn = c

        var hci: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            if (f.interfaceClass == 0xE0 && f.interfaceSubclass == 1 && f.interfaceProtocol == 1) {
                hci = f
                break
            }
        }
        val f = hci ?: return fail("No Bluetooth HCI interface found on this USB device")
        for (j in 0 until f.endpointCount) {
            val e = f.getEndpoint(j)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN) epIn = e
        }
        if (epIn == null) return fail("No HCI event endpoint found")
        if (!c.claimInterface(f, true)) return fail("Could not claim the USB interface")
        intf = f

        onStatus("USB: resetting adapter")
        cmd(0x0C03) ?: return fail("Adapter did not answer (HCI reset)")

        val ver = cmd(0x1001)
        if (ver == null || ver.size < 9) return fail("Could not read the adapter's version")
        val mfg = u16(ver, 5)
        val subver = u16(ver, 7)

        // A Realtek RTL8761B still in ROM state reports LMP subversion 0x8761 and needs firmware.
        if (mfg == 0x005D && subver == 0x8761) {
            val fw = RtlFw.load(ctx)
            if (fw == null) {
                onStatus("USB: Realtek adapter without firmware (import it in Settings > Adapter); trying anyway")
            } else {
                val err = loadRealtekFirmware(fw.first, fw.second)
                if (err != null) return fail(err)
            }
        }

        onStatus("USB: configuring LE scan")
        cmd(0x0C01, byteArrayOf(-1, -1, -1, -1, -1, -1, -1, 0x3F))      // event mask incl. LE Meta
        cmd(0x0C6D, byteArrayOf(1, 0))                                  // LE supported (host); result ignored
        // LE event mask: the default (bits 0-4) plus bit 12 = LE Extended Advertising Report, which is off by default.
        cmd(0x2001, byteArrayOf(0x1F, 0x10, 0, 0, 0, 0, 0, 0))
        extScan = false
        if (extended) {
            // Prefer 1M + Coded PHY (long range), then 1M only. Both are active scans (scan responses give names).
            if (cmdOk(0x2041, extScanParams(0x05))) {
                extScan = true
                modeText = "USB: LEGACY + EXTENDED, 1M + CODED"
            } else if (cmdOk(0x2041, extScanParams(0x01))) {
                extScan = true
                modeText = "USB: LEGACY + EXTENDED, 1M"
            }
        }
        if (!extScan) {
            modeText = "USB: LEGACY ONLY"
            val sp = cmd(0x200B, byteArrayOf(1, 0x30, 0, 0x30, 0, 0, 0))    // active, 30 ms interval/window
            if (sp == null || sp.isEmpty() || sp[0] != 0.toByte()) {
                return fail("Adapter rejected the LE scan settings (status ${sp?.getOrNull(0)?.toInt()?.and(0xFF) ?: -1})")
            }
        }

        running = true
        reader = Thread { readLoop() }.also { it.isDaemon = true; it.start() }
        onStatus("USB adapter ready")
        return null
    }

    /** Starts or stops LE scanning (fire-and-forget; the reader thread reports a refusal). */
    fun setScanning(on: Boolean) {
        if (closed) return
        cmdExec.execute { if (!closed) scanEnable(on) }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try { if (conn != null && intf != null) scanEnable(false) } catch (_: Exception) {}
        running = false
        try { reader?.join(700) } catch (_: Exception) {}
        cmdExec.shutdown()
        try { intf?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        conn = null
    }

    // ---- scan control -------------------------------------------------------------------------------

    /** LE Set Extended Scan Parameters for the PHYs in [phys] (bit 0 = 1M, bit 2 = Coded), active scanning. */
    private fun extScanParams(phys: Int): ByteArray {
        val both = phys == 0x05
        val interval = if (both) 0x0060 else 0x0030      // 0.625 ms units: 60 ms when sharing the radio between two PHYs
        val window = 0x0030                              // 30 ms per PHY
        val out = ByteArrayOutputStream()
        out.write(0)                                     // own address type: public
        out.write(0)                                     // accept every advertiser
        out.write(phys)
        val count = if (both) 2 else 1
        repeat(count) {
            out.write(1)                                 // active scan
            out.write(interval and 0xFF); out.write(interval shr 8)
            out.write(window and 0xFF); out.write(window shr 8)
        }
        return out.toByteArray()
    }

    private fun cmdOk(opcode: Int, params: ByteArray): Boolean {
        val r = cmd(opcode, params)
        return r != null && r.isNotEmpty() && r[0] == 0.toByte()
    }

    /** Starts or stops scanning with the command set the adapter accepted. Duplicates are not filtered (RSSI updates). */
    private fun scanEnable(on: Boolean): Boolean =
        if (extScan) send(0x2042, byteArrayOf(if (on) 1 else 0, 0, 0, 0, 0, 0))   // enable, no dup filter, no duration/period
        else send(0x200C, byteArrayOf(if (on) 1 else 0, 0))

    // ---- Realtek firmware ---------------------------------------------------------------------------

    private fun loadRealtekFirmware(fw: ByteArray, cfg: ByteArray?): String? {
        onStatus("USB: loading Realtek firmware")
        val rv = cmd(0xFC6D)
        if (rv == null || rv.size < 2 || rv[0] != 0.toByte()) return "Could not read the Realtek ROM version"
        val rom = rv[1].toInt() and 0xFF
        val payload = try {
            RtlFw.build(fw, cfg, rom)
        } catch (e: Exception) {
            return "Firmware: ${e.message}"
        }
        val frags = (payload.size + 251) / 252
        for (i in 0 until frags) {
            var idx = if (i > 0x7F) (i and 0x7F) + 1 else i
            if (i == frags - 1) idx = idx or 0x80           // marks the last fragment
            val start = i * 252
            val len = minOf(252, payload.size - start)
            val p = ByteArray(1 + len)
            p[0] = idx.toByte()
            System.arraycopy(payload, start, p, 1, len)
            val r = cmd(0xFC20, p, 3000) ?: return "Firmware download stalled at part ${i + 1} of $frags"
            if (r.isEmpty() || r[0] != 0.toByte()) return "Firmware rejected at part ${i + 1} of $frags"
        }
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        cmd(0x0C03) ?: return "Adapter did not answer after loading the firmware"
        val v2 = cmd(0x1001)
        if (v2 != null && v2.size >= 9) onStatus("USB: firmware loaded (subversion 0x%04X)".format(u16(v2, 7)))
        return null
    }

    // ---- HCI plumbing -------------------------------------------------------------------------------

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun fail(msg: String): String {
        close()
        return msg
    }

    private fun send(opcode: Int, params: ByteArray = ByteArray(0)): Boolean {
        val c = conn ?: return false
        val b = ByteArray(3 + params.size)
        b[0] = (opcode and 0xFF).toByte()
        b[1] = ((opcode shr 8) and 0xFF).toByte()
        b[2] = params.size.toByte()
        System.arraycopy(params, 0, b, 3, params.size)
        return c.controlTransfer(0x20, 0, 0, intf?.id ?: 0, b, b.size, 1000) >= 0
    }

    /** Sends a command and waits for its Command Complete/Status. Returns the return parameters (status first). */
    private fun cmd(opcode: Int, params: ByteArray = ByteArray(0), timeoutMs: Int = 2000): ByteArray? {
        if (!send(opcode, params)) return null
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = (end - SystemClock.elapsedRealtime()).toInt()
            if (left <= 0) return null
            val ev = readEvent(left) ?: return null
            val code = ev[0].toInt() and 0xFF
            if (code == 0x0E && ev.size >= 6 && u16(ev, 3) == opcode) return ev.copyOfRange(5, ev.size)
            if (code == 0x0F && ev.size >= 6 && u16(ev, 4) == opcode) return byteArrayOf(ev[2])
        }
    }

    /** Returns one complete HCI event (code, length, parameters) or null on timeout. */
    private fun readEvent(timeoutMs: Int): ByteArray? {
        val c = conn ?: return null
        val ep = epIn ?: return null
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            takeFrame()?.let { return it }
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            val n = c.bulkTransfer(ep, pkt, pkt.size, minOf(left, 100L).toInt())
            if (n > 0) {
                if (rxLen + n > rxBuf.size) rxLen = 0       // lost sync; start over
                System.arraycopy(pkt, 0, rxBuf, rxLen, n)
                rxLen += n
            }
        }
    }

    private fun takeFrame(): ByteArray? {
        if (rxLen < 2) return null
        val total = 2 + (rxBuf[1].toInt() and 0xFF)
        if (rxLen < total) return null
        val ev = rxBuf.copyOfRange(0, total)
        System.arraycopy(rxBuf, total, rxBuf, 0, rxLen - total)
        rxLen -= total
        return ev
    }

    private fun alive(): Boolean = usb.deviceList.values.any { it.deviceId == device.deviceId }

    private fun postError(msg: String) {
        main.post { if (!closed) onError(msg) }
    }

    private fun readLoop() {
        var idle = 0
        while (running) {
            val ev = readEvent(300)
            if (ev == null) {
                if (++idle >= 10) {
                    idle = 0
                    if (!alive()) {
                        postError("USB adapter was unplugged")
                        return
                    }
                }
                continue
            }
            idle = 0
            val code = ev[0].toInt() and 0xFF
            if (code == 0x3E && ev.size >= 4 && ev[2].toInt() == 0x02) {
                parseReports(ev)
            } else if (code == 0x3E && ev.size >= 4 && ev[2].toInt() == 0x0D) {
                parseExtReports(ev)
            } else if (code == 0x0E && ev.size >= 6 && (u16(ev, 3) == 0x200C || u16(ev, 3) == 0x2042) &&
                ev[5] != 0.toByte()
            ) {
                postError("Adapter refused to change scanning (status 0x%02X)".format(ev[5].toInt() and 0xFF))
            }
        }
    }

    /** LE Advertising Report: subevent, count, then per report type, address type, address, data, RSSI. */
    private fun parseReports(ev: ByteArray) {
        val n = ev[3].toInt() and 0xFF
        var p = 4
        repeat(n) {
            if (p + 9 > ev.size) return
            val dl = ev[p + 8].toInt() and 0xFF
            if (p + 10 + dl > ev.size) return
            val sb = StringBuilder(17)
            for (k in 5 downTo 0) {
                if (sb.isNotEmpty()) sb.append(':')
                sb.append("%02X".format(ev[p + 2 + k].toInt() and 0xFF))
            }
            val data = ev.copyOfRange(p + 9, p + 9 + dl)
            var rssi = ev[p + 9 + dl].toInt()                  // signed byte
            if (rssi == 127) rssi = -100                        // "not available"
            val adv = AdParser.parse(sb.toString(), rssi, data)
            main.post { if (!closed) onAdvert(adv) }
            p += 10 + dl
        }
    }

    /**
     * LE Extended Advertising Report: subevent, count, then per report a 24-byte header
     * (event type 2, address type 1, address 6, primary PHY 1, secondary PHY 1, SID 1, Tx power 1, RSSI 1,
     * periodic interval 2, direct address type 1, direct address 6, data length 1) followed by the data.
     * Legacy adverts also arrive this way once extended scanning is on (event type bit 4 set).
     */
    private fun parseExtReports(ev: ByteArray) {
        val n = ev[3].toInt() and 0xFF
        var p = 4
        val now = SystemClock.elapsedRealtime()
        if (frags.isNotEmpty()) frags.values.removeAll { now - it.t > 3000 }
        repeat(n) {
            if (p + 24 > ev.size) return
            val dl = ev[p + 23].toInt() and 0xFF
            if (p + 24 + dl > ev.size) return
            val type = u16(ev, p)
            val sb = StringBuilder(17)
            for (k in 5 downTo 0) {
                if (sb.isNotEmpty()) sb.append(':')
                sb.append("%02X".format(ev[p + 3 + k].toInt() and 0xFF))
            }
            val addr = sb.toString()
            val prim = ev[p + 9].toInt() and 0xFF
            val sec = ev[p + 10].toInt() and 0xFF
            val sid = ev[p + 11].toInt() and 0xFF
            val tx = ev[p + 12].toInt()                        // signed; 127 = not available
            var rssi = ev[p + 13].toInt()
            if (rssi == 127) rssi = -100
            val status = (type shr 5) and 0x03                 // 0 complete, 1 more data coming, 2 truncated
            val legacy = (type and 0x10) != 0
            var data = ev.copyOfRange(p + 24, p + 24 + dl)
            p += 24 + dl

            val key = addr + "/" + sid + "/" + (type and 0x08)  // scan responses stay apart from adverts
            if (status == 1) {
                val f = frags.getOrPut(key) { Frag(ByteArrayOutputStream(), now) }
                f.t = now
                if (f.data.size() + data.size <= 1650) f.data.write(data)
                if (frags.size > 32) frags.clear()
                return@repeat
            }
            frags.remove(key)?.let { f ->
                if (f.data.size() + data.size <= 1650) f.data.write(data)
                data = f.data.toByteArray()
            }
            val base = AdParser.parse(addr, rssi, data)
            val phy = if (prim == 3 || sec == 3) 3 else if (sec != 0) sec else prim
            val adv = Advert(
                base.address, base.rssi, base.name, base.mfg, base.services,
                raw = data, rawPart = true,
                ext = !legacy, phy = if (legacy) 0 else phy, sid = if (legacy) -1 else sid,
                txPower = tx, truncated = status == 2
            )
            main.post { if (!closed) onAdvert(adv) }
        }
    }
}
