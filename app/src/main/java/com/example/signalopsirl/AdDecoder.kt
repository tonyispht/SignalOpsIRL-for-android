package com.example.signalopsirl

import java.util.Locale

/** One AD structure of an advertisement: its type, the raw bytes and a human-readable decoding. */
data class AdField(val type: Int, val typeName: String, val hex: String, val decoded: String?)

/** Values read from a sensor/beacon advertisement. [values] are label/value pairs, [summary] a one-line form. */
data class SensorReading(val kind: String, val values: List<Pair<String, String>>, val summary: String)

/** The latest raw advertisement data seen from one device (several packets when a USB adapter reports them separately). */
class RawAd(
    val packets: List<ByteArray>,
    val time: Long,
    val first: Long,
    val count: Int,
    val sensor: SensorReading?,
    /** The parts of the advertisement that stay the same when the device changes its address (see [AdDecoder.tokens]). */
    val tokens: Set<String> = emptySet()
)

/**
 * Breaks raw advertising data into AD structures and decodes the ones worth decoding: flags, UUID lists,
 * names, manufacturer data (iBeacon, Apple continuity types), Eddystone, and a handful of common sensors
 * (Ruuvi, ATC/pvvx Xiaomi thermometers, Govee, SwitchBot Meter, Eddystone TLM).
 */
object AdDecoder {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private val TYPE_NAMES = mapOf(
        0x01 to "Flags", 0x02 to "Incomplete 16-bit UUIDs", 0x03 to "Complete 16-bit UUIDs",
        0x04 to "Incomplete 32-bit UUIDs", 0x05 to "Complete 32-bit UUIDs",
        0x06 to "Incomplete 128-bit UUIDs", 0x07 to "Complete 128-bit UUIDs",
        0x08 to "Shortened Name", 0x09 to "Complete Name", 0x0A to "Tx Power", 0x0D to "Class of Device",
        0x12 to "Peripheral Conn Interval", 0x14 to "16-bit Solicitation", 0x15 to "128-bit Solicitation",
        0x16 to "Service Data (16-bit)", 0x17 to "Public Target Address", 0x18 to "Random Target Address",
        0x19 to "Appearance", 0x1A to "Advertising Interval", 0x1B to "LE Device Address", 0x1C to "LE Role",
        0x20 to "Service Data (32-bit)", 0x21 to "Service Data (128-bit)", 0x24 to "URI",
        0x2B to "Mesh Beacon", 0x2A to "Mesh Message", 0x3D to "3D Information", 0xFF to "Manufacturer Data"
    )

    private val APPLE_TYPES = mapOf(
        0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity Pairing (AirPods/Beats)", 0x09 to "AirPlay Target",
        0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to "Tethering Target", 0x0E to "Tethering Source",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My network"
    )

    private val APPLE_SHORT = mapOf(
        0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "AirPods/Beats", 0x09 to "AirPlay",
        0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to "Tethering target", 0x0E to "Tethering source",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My", 0x0215 to "iBeacon"
    )

    /** "NEARBY INFO \u00B7 HANDOFF": what an Apple device is advertising, from the message types seen. */
    fun appleKinds(types: Set<Int>): String =
        types.sorted().joinToString(" \u00B7 ") { (APPLE_SHORT[it] ?: "0x%02X".format(it)).uppercase() }

    /**
     * The Apple continuity messages packed into one manufacturer payload (company ID already removed):
     * (type, declared length) pairs. One payload can carry several messages, e.g. Nearby Info followed by Handoff.
     */
    fun appleMessages(p: ByteArray, from: Int = 0, to: Int = p.size): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(3)
        var i = from
        while (i + 2 <= to) {
            val t = u8(p, i)
            val l = u8(p, i + 1)
            out.add(t to l)
            if (i + 2 + l > to) break          // cut short or not in this format: keep what was readable
            i += 2 + l
        }
        return out
    }

    /** (type, offset of its data, length of its data) for every Apple message in [from, to). */
    private fun appleSpans(p: ByteArray, from: Int, to: Int): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>(3)
        var i = from
        while (i + 2 <= to) {
            val t = u8(p, i)
            val l = u8(p, i + 1)
            out.add(Triple(t, i + 2, minOf(l, to - (i + 2))))
            if (i + 2 + l > to) break
            i += 2 + l
        }
        return out
    }

    /** What the advertisements say about an Apple device: a model/type when one is given, and its activity. */
    class AppleInfo(val model: String?, val state: String?)

    private val AIRPODS_MODELS = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods 2", 0x1320 to "AirPods 3", 0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2", 0x0A20 to "AirPods Max", 0x0520 to "BeatsX", 0x0620 to "Beats Solo3",
        0x0320 to "Powerbeats3", 0x0B20 to "Powerbeats Pro", 0x0C20 to "Beats Solo Pro",
        0x1120 to "Beats Studio Buds", 0x1020 to "Beats Flex", 0x0920 to "Beats Studio3"
    )

    /** Nearby Info activity codes (low nibble of its first byte), as reverse-engineered by the community. */
    private val NEARBY_STATE = mapOf(
        0x01 to "REPORTING OFF", 0x03 to "IDLE", 0x05 to "AUDIO, SCREEN OFF", 0x07 to "SCREEN ON",
        0x09 to "VIDEO PLAYING", 0x0A to "WATCH ON WRIST", 0x0B to "RECENTLY USED", 0x0D to "DRIVING", 0x0E to "IN A CALL"
    )

    /** Best-effort model and activity of an Apple device from its packets; null when there are no Apple messages. */
    fun appleInfo(packets: List<ByteArray>): AppleInfo? {
        var model: String? = null
        var state: String? = null
        var any = false
        for (pk in packets) {
            var i = 0
            while (i < pk.size) {
                val l = u8(pk, i)
                if (l == 0 || i + 1 + l > pk.size) break
                if (l >= 3 && u8(pk, i + 1) == 0xFF && u16le(pk, i + 2) == 0x004C) {
                    for ((ty, off, ln) in appleSpans(pk, i + 4, i + 1 + l)) {
                        any = true
                        if (ty == 0x10 && ln >= 1) {
                            val a = u8(pk, off) and 0x0F
                            NEARBY_STATE[a]?.let { state = it }
                            if (a == 0x0A && model == null) model = "Apple Watch"
                        }
                        if (ty == 0x07 && ln >= 3) model = AIRPODS_MODELS[u16be(pk, off + 1)] ?: "AirPods/Beats"
                    }
                }
                i += l + 1
            }
        }
        return if (any) AppleInfo(model, state) else null
    }

    // ---- byte helpers ---------------------------------------------------------------------

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun s8(b: ByteArray, i: Int) = b[i].toInt()
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun s16be(b: ByteArray, i: Int) = u16be(b, i).toShort().toInt()
    private fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16le(b: ByteArray, i: Int) = u16le(b, i).toShort().toInt()
    private fun u32be(b: ByteArray, i: Int) =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun fmt(x: Double, dec: Int) = String.format(Locale.US, "%.${dec}f", x)

    fun hex(b: ByteArray, s: Int, e: Int): String {
        val sb = StringBuilder()
        for (i in s until e) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("%02X".format(u8(b, i)))
        }
        return sb.toString()
    }

    private fun uuid128(b: ByteArray, s: Int, bigEndian: Boolean): String {
        val bytes = (0 until 16).map { u8(b, if (bigEndian) s + it else s + 15 - it) }
        val h = bytes.joinToString("") { "%02x".format(it) }
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" +
            h.substring(16, 20) + "-" + h.substring(20)
    }

    /** Drops the zero padding after the last AD structure (Android pads scan records to a fixed size). */
    fun trim(raw: ByteArray): ByteArray {
        var i = 0
        while (i < raw.size) {
            val l = u8(raw, i)
            if (l == 0) break
            if (i + 1 + l > raw.size) {
                i = raw.size
                break
            }
            i += l + 1
        }
        return raw.copyOfRange(0, i)
    }

    /** The AD types present, used to tell an advertisement from a scan response. */
    fun signature(data: ByteArray): String {
        val types = ArrayList<Int>()
        var i = 0
        while (i < data.size) {
            val l = u8(data, i)
            if (l == 0 || i + 1 + l > data.size) break
            types.add(u8(data, i + 1))
            i += l + 1
        }
        return types.sorted().joinToString(",")
    }

    // ---- identification hints -------------------------------------------------------------

    /** Microsoft Connected Devices Platform device types (low 5 bits of the second payload byte): name, category. */
    val MS_CDP = mapOf(
        1 to ("Xbox" to "handhelds_gaming"), 6 to ("iPhone (Microsoft app)" to "smartphones"),
        7 to ("iPad (Microsoft app)" to "laptops_tablets"), 8 to ("Android phone (Phone Link)" to "smartphones"),
        9 to ("Windows PC" to "laptops_tablets"), 11 to ("Windows phone" to "smartphones"),
        12 to ("Linux device" to "laptops_tablets"), 13 to ("Windows IoT device" to "smart_iot"),
        14 to ("Surface Hub" to "laptops_tablets"), 15 to ("Windows laptop" to "laptops_tablets"),
        16 to ("Windows tablet" to "laptops_tablets")
    )

    /**
     * Stable identification clues that need the payload bytes, not just IDs: appearance (APPEAR:03C1), Microsoft
     * Swift Pair (SWIFTPAIR) and Connected Devices type (MSCDP:09), Google Find Hub tracker frames (FMDN) and
     * Fast Pair model IDs (FASTPAIR:XXXXXX).
     */
    fun hints(packets: List<ByteArray>): Set<String> {
        val out = HashSet<String>()
        for (pk in packets) {
            var i = 0
            while (i < pk.size) {
                val l = u8(pk, i)
                if (l == 0 || i + 1 + l > pk.size) break
                val type = u8(pk, i + 1)
                val s = i + 2
                val e = i + 1 + l
                when (type) {
                    0x19 -> if (e - s >= 2) out.add("APPEAR:%04X".format(u16le(pk, s)))
                    0xFF -> if (e - s >= 4 && u16le(pk, s) == 0x0006) {
                        when (u8(pk, s + 2)) {
                            0x03 -> out.add("SWIFTPAIR")
                            0x01 -> out.add("MSCDP:%02X".format(u8(pk, s + 3) and 0x1F))
                        }
                    }
                    0x16 -> if (e - s >= 3) {
                        val u = u16le(pk, s)
                        val f = u8(pk, s + 2)
                        if (u == 0xFEAA && (f == 0x40 || f == 0x41)) out.add("FMDN")
                        if (u == 0xFE2C && e - s == 5) out.add("FASTPAIR:%06X".format((f shl 16) or u16be(pk, s + 3)))
                    }
                }
                i += l + 1
            }
        }
        return out
    }

    /**
     * UUIDs of every service-data structure (AD types 0x16, 0x20, 0x21) as 32 uppercase hex digits. Android's UUID
     * list leaves these out, yet many devices (SmartTag, Fast Pair, Find Hub tags, drone Remote ID, Xiaomi) name
     * themselves only here.
     */
    fun serviceDataUuids(packets: List<ByteArray>): Set<String> {
        val out = HashSet<String>()
        for (pk in packets) {
            var i = 0
            while (i < pk.size) {
                val l = u8(pk, i)
                if (l == 0 || i + 1 + l > pk.size) break
                val type = u8(pk, i + 1)
                val s = i + 2
                val e = i + 1 + l
                when (type) {
                    0x16 -> if (e - s >= 2) out.add("0000%04X".format(u16le(pk, s)) + BtDb.BASE_SUFFIX)
                    0x20 -> if (e - s >= 4) {
                        val v = u8(pk, s).toLong() or (u8(pk, s + 1).toLong() shl 8) or
                            (u8(pk, s + 2).toLong() shl 16) or (u8(pk, s + 3).toLong() shl 24)
                        out.add("%08X".format(v) + BtDb.BASE_SUFFIX)
                    }
                    0x21 -> if (e - s >= 16) out.add(uuid128(pk, s, bigEndian = false).replace("-", "").uppercase())
                }
                i += l + 1
            }
        }
        return out
    }

    // ---- fingerprint ----------------------------------------------------------------------

    /**
     * The stable "shape" of an advertisement: AD structure types with the lengths and content that do not change
     * from one address to the next (flags, UUID lists, names, Tx power, company IDs and payload lengths, Apple
     * message types). Rolling payload bytes such as counters, keys and auth tags are left out on purpose.
     */
    fun tokens(packets: List<ByteArray>): Set<String> {
        val out = HashSet<String>()
        for (pk in packets) {
            var i = 0
            while (i < pk.size) {
                val l = u8(pk, i)
                if (l == 0 || i + 1 + l > pk.size) break
                val type = u8(pk, i + 1)
                val s = i + 2
                val e = i + 1 + l
                val t = "%02X".format(type)
                when (type) {
                    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x12, 0x14, 0x15, 0x19, 0x1A, 0x1C ->
                        out.add("$t:" + hex(pk, s, e))
                    0x16 -> if (e - s >= 2) out.add("$t:" + hex(pk, s, s + 2) + ":" + (e - s))
                    0x20 -> if (e - s >= 4) out.add("$t:" + hex(pk, s, s + 4) + ":" + (e - s))
                    0x21 -> if (e - s >= 16) out.add("$t:" + hex(pk, s, s + 16) + ":" + (e - s))
                    0xFF -> if (e - s >= 2) {
                        val id = u16le(pk, s)
                        if (id == 0x004C && e - s >= 4) {
                            // Apple: one token per message (type and length). A phone that sends Nearby Info + Handoff no
                            // longer looks like a device that sends only Nearby Info.
                            for ((ty, ln) in appleMessages(pk, s + 2, e)) out.add("$t:004C:%02X:%d".format(ty, ln))
                            // AirPods/Beats: the model stays the same while the address rotates.
                            for ((ty, off, ln) in appleSpans(pk, s + 2, e)) {
                                if (ty == 0x07 && ln >= 3) out.add("AM:%04X".format(u16be(pk, off + 1)))
                            }
                        } else {
                            val apple = if (id == 0x004C && e - s >= 3) ":" + "%02X".format(u8(pk, s + 2)) else ""
                            out.add("$t:%04X".format(id) + apple + ":" + (e - s))
                        }
                    }
                    else -> out.add("$t:" + (e - s))
                }
                i += l + 1
            }
        }
        return out
    }

    // ---- structures -----------------------------------------------------------------------

    fun fields(data: ByteArray): List<AdField> {
        val out = ArrayList<AdField>()
        var i = 0
        while (i < data.size) {
            val l = u8(data, i)
            if (l == 0 || i + 1 + l > data.size) break
            val type = u8(data, i + 1)
            val s = i + 2
            val e = i + 1 + l
            out.add(
                AdField(type, TYPE_NAMES[type] ?: "Type 0x%02X".format(type), hex(data, s, e), decode(type, data, s, e))
            )
            i += l + 1
        }
        return out
    }

    private fun decode(type: Int, d: ByteArray, s: Int, e: Int): String? = when (type) {
        0x01 -> if (e - s >= 1) {
            val f = u8(d, s)
            listOfNotNull(
                if ((f and 0x01) != 0) "LE limited discoverable" else null,
                if ((f and 0x02) != 0) "LE general discoverable" else null,
                if ((f and 0x04) != 0) "no BR/EDR" else null,
                if ((f and 0x08) != 0) "LE+BR/EDR controller" else null,
                if ((f and 0x10) != 0) "LE+BR/EDR host" else null
            ).joinToString(", ").ifEmpty { "none" }
        } else null
        0x02, 0x03 -> {
            val l = ArrayList<String>()
            var p = s
            while (p + 2 <= e) {
                val u = u16le(d, p)
                val n = GattProbe.serviceName("0000%04x$BASE_SUFFIX".format(u))
                l.add("0x%04X".format(u) + (n?.let { " ($it)" } ?: ""))
                p += 2
            }
            l.joinToString(", ")
        }
        0x04, 0x05 -> {
            val l = ArrayList<String>()
            var p = s
            while (p + 4 <= e) {
                // little-endian on the wire
                val v = u8(d, p) or (u8(d, p + 1) shl 8) or (u8(d, p + 2) shl 16) or (u8(d, p + 3) shl 24)
                l.add("0x%08X".format(v))
                p += 4
            }
            l.joinToString(", ")
        }
        0x06, 0x07 -> {
            val l = ArrayList<String>()
            var p = s
            while (p + 16 <= e) {
                l.add(uuid128(d, p, bigEndian = false))
                p += 16
            }
            l.joinToString("\n")
        }
        0x08, 0x09 -> "\"" + String(d, s, e - s, Charsets.UTF_8).filter { !it.isISOControl() } + "\""
        0x0A -> if (e - s >= 1) "${s8(d, s)} dBm" else null
        0x12 -> if (e - s >= 4) "${fmt(u16le(d, s) * 1.25, 2)} - ${fmt(u16le(d, s + 2) * 1.25, 2)} ms" else null
        0x19 -> if (e - s >= 2) {
            val v = u16le(d, s)
            "0x%04X".format(v) + (BtDb.appearanceName(v)?.let { " ($it)" } ?: "")
        } else null
        0x1A -> if (e - s >= 2) "${fmt(u16le(d, s) * 0.625, 1)} ms" else null
        0x16 -> if (e - s >= 2) serviceData16(u16le(d, s), d.copyOfRange(s + 2, e)) else null
        0x24 -> if (e - s >= 2) String(d, s + 1, e - s - 1, Charsets.UTF_8).filter { !it.isISOControl() } else null
        0xFF -> if (e - s >= 2) manufacturer(u16le(d, s), d.copyOfRange(s + 2, e)) else null
        else -> null
    }

    private fun manufacturer(id: Int, p: ByteArray): String {
        val name = Classifier.companyName(id)
        val head = (name ?: "Unknown company") + " (0x%04X)".format(id)
        val detail: String? = when {
            id == 0x004C && p.size >= 23 && u8(p, 0) == 0x02 && u8(p, 1) == 0x15 ->
                "iBeacon  UUID ${uuid128(p, 2, bigEndian = true)}  major ${u16be(p, 18)}  minor ${u16be(p, 20)}  " +
                    "tx ${s8(p, 22)} dBm @1m"
            id == 0x004C && p.isNotEmpty() ->
                "Apple " + (APPLE_TYPES[u8(p, 0)] ?: "message type 0x%02X".format(u8(p, 0)))
            id == 0x0006 && p.size >= 2 && u8(p, 0) == 0x03 -> "Microsoft Swift Pair (accessory ready to pair)"
            id == 0x0006 && p.size >= 2 && u8(p, 0) == 0x01 ->
                "Microsoft Connected Devices: " + (MS_CDP[u8(p, 1) and 0x1F]?.first ?: "device type %d".format(u8(p, 1) and 0x1F))
            else -> sensorMfg(id, p, null)?.let { it.kind + ": " + it.summary }
        }
        return if (detail != null) "$head\n$detail" else head
    }

    private fun serviceData16(uuid: Int, p: ByteArray): String {
        val svc = GattProbe.serviceName("0000%04x$BASE_SUFFIX".format(uuid))
        val head = "0x%04X".format(uuid) + (svc?.let { " ($it)" } ?: "")
        val detail: String? = if (uuid == 0xFEAA) eddystone(p) else sensorSvc(uuid, p)?.let { it.kind + ": " + it.summary }
        return if (detail != null) "$head\n$detail" else head + if (p.isNotEmpty()) "\n${p.size} byte(s)" else ""
    }

    // ---- Eddystone ------------------------------------------------------------------------

    private val URL_SCHEMES = arrayOf("http://www.", "https://www.", "http://", "https://")
    private val URL_EXPAND = arrayOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
        ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov"
    )

    private fun eddystone(p: ByteArray): String? {
        if (p.isEmpty()) return null
        return when (u8(p, 0)) {
            0x00 -> if (p.size >= 18)
                "Eddystone-UID  namespace ${hex(p, 2, 12).replace(" ", "")}  instance ${hex(p, 12, 18).replace(" ", "")}  " +
                    "tx ${s8(p, 1)} dBm @0m" else "Eddystone-UID"
            0x10 -> if (p.size >= 3) {
                val sb = StringBuilder()
                val scheme = u8(p, 2)
                if (scheme < URL_SCHEMES.size) sb.append(URL_SCHEMES[scheme])
                for (i in 3 until p.size) {
                    val c = u8(p, i)
                    if (c < URL_EXPAND.size) sb.append(URL_EXPAND[c]) else if (c in 0x21..0x7E) sb.append(c.toChar())
                }
                "Eddystone-URL  $sb  tx ${s8(p, 1)} dBm @0m"
            } else "Eddystone-URL"
            0x20 -> sensorSvc(0xFEAA, p)?.let { "Eddystone-TLM: " + it.summary } ?: "Eddystone-TLM"
            0x30 -> "Eddystone-EID"
            else -> "Eddystone frame 0x%02X".format(u8(p, 0))
        }
    }

    // ---- sensors --------------------------------------------------------------------------

    private fun mk(
        kind: String, temp: Double? = null, hum: Double? = null, pres: Double? = null,
        batMv: Int? = null, batPct: Int? = null, extra: List<Pair<String, String>> = emptyList()
    ): SensorReading {
        val v = ArrayList<Pair<String, String>>()
        val sum = ArrayList<String>()
        if (temp != null) {
            val f = temp * 9.0 / 5.0 + 32.0
            v.add("TEMP" to fmt(f, 1) + "\u00B0F")
            sum.add(fmt(f, 1) + "\u00B0F")
        }
        if (hum != null) {
            v.add("HUM" to fmt(hum, 1) + "%")
            sum.add(fmt(hum, 0) + "%RH")
        }
        if (pres != null) {
            v.add("PRESS" to fmt(pres, 1) + " hPa")
            sum.add(fmt(pres, 0) + "hPa")
        }
        if (batPct != null) {
            v.add("BATT" to "$batPct%")
            sum.add("BAT $batPct%")
        }
        if (batMv != null) {
            v.add((if (batPct != null) "VOLT" else "BATT") to fmt(batMv / 1000.0, 2) + " V")
            if (batPct == null) sum.add(fmt(batMv / 1000.0, 2) + "V")
        }
        v.addAll(extra)
        if (sum.isEmpty()) extra.firstOrNull()?.let { sum.add(it.first + " " + it.second) }
        return SensorReading(kind, v, sum.joinToString("  "))
    }

    /** Looks through every packet for a sensor/beacon payload this decoder understands. */
    fun sensor(packets: List<ByteArray>, name: String?): SensorReading? {
        for (pk in packets) {
            var i = 0
            while (i < pk.size) {
                val l = u8(pk, i)
                if (l == 0 || i + 1 + l > pk.size) break
                val type = u8(pk, i + 1)
                val s = i + 2
                val e = i + 1 + l
                val r = when (type) {
                    0xFF -> if (e - s >= 2) sensorMfg(u16le(pk, s), pk.copyOfRange(s + 2, e), name) else null
                    0x16 -> if (e - s >= 2) sensorSvc(u16le(pk, s), pk.copyOfRange(s + 2, e)) else null
                    else -> null
                }
                if (r != null) return r
                i += l + 1
            }
        }
        return null
    }

    /** Manufacturer-data sensors. [p] excludes the two company-ID bytes. */
    private fun sensorMfg(id: Int, p: ByteArray, name: String?): SensorReading? {
        when (id) {
            0x0499 -> { // Ruuvi
                if (p.isEmpty()) return null
                when (u8(p, 0)) {
                    5 -> if (p.size >= 18) {
                        val t = s16be(p, 1)
                        val h = u16be(p, 3)
                        val pr = u16be(p, 5)
                        val power = u16be(p, 13)
                        val batRaw = power ushr 5
                        val txRaw = power and 0x1F
                        val extra = ArrayList<Pair<String, String>>()
                        extra.add("ACCEL" to "${s16be(p, 7)}/${s16be(p, 9)}/${s16be(p, 11)} mG")
                        if (txRaw != 0x1F) extra.add("TX" to "${txRaw * 2 - 40} dBm")
                        extra.add("MOVES" to "${u8(p, 15)}")
                        extra.add("SEQ" to "${u16be(p, 16)}")
                        return mk(
                            "RUUVI TAG (FORMAT 5)",
                            temp = if (t == -32768) null else t * 0.005,
                            hum = if (h == 0xFFFF) null else h * 0.0025,
                            pres = if (pr == 0xFFFF) null else (pr + 50000) / 100.0,
                            batMv = if (batRaw == 0x7FF) null else batRaw + 1600,
                            extra = extra
                        )
                    }
                    3 -> if (p.size >= 14) {
                        val ti = u8(p, 2)
                        val temp = (if ((ti and 0x80) != 0) -1 else 1) * ((ti and 0x7F) + u8(p, 3) / 100.0)
                        return mk(
                            "RUUVI TAG (FORMAT 3)",
                            temp = temp, hum = u8(p, 1) * 0.5, pres = (u16be(p, 4) + 50000) / 100.0,
                            batMv = u16be(p, 12),
                            extra = listOf("ACCEL" to "${s16be(p, 6)}/${s16be(p, 8)}/${s16be(p, 10)} mG")
                        )
                    }
                }
            }
            0xEC88 -> { // Govee thermometers (names look like GVH5075_1234 / Govee_H5074_ABCD)
                val n = name ?: return null
                if (!(n.startsWith("GVH", true) || n.startsWith("Govee", true))) return null
                if (n.contains("5074")) {
                    if (p.size >= 6) {
                        return mk(
                            "GOVEE H5074", temp = s16le(p, 1) / 100.0, hum = u16le(p, 3) / 100.0, batPct = u8(p, 5)
                        )
                    }
                } else if (p.size >= 5) {
                    val num = (u8(p, 1) shl 16) or (u8(p, 2) shl 8) or u8(p, 3)
                    val neg = (num and 0x800000) != 0
                    val v = num and 0x7FFFFF
                    val temp = (v / 1000) / 10.0
                    return mk(
                        "GOVEE (H5075 STYLE)", temp = if (neg) -temp else temp, hum = (v % 1000) / 10.0,
                        batPct = u8(p, 4)
                    )
                }
            }
        }
        return null
    }

    /** Service-data sensors. [p] excludes the two UUID bytes. */
    private fun sensorSvc(uuid: Int, p: ByteArray): SensorReading? {
        when (uuid) {
            0x181A -> {
                // Xiaomi LYWSD03MMC / similar thermometers running custom (ATC) firmware
                if (p.size == 13) { // ATC1441
                    return mk(
                        "XIAOMI THERMOMETER (ATC1441)",
                        temp = s16be(p, 6) / 10.0, hum = u8(p, 8).toDouble(), batPct = u8(p, 9), batMv = u16be(p, 10)
                    )
                }
                if (p.size == 15) { // pvvx custom format
                    return mk(
                        "XIAOMI THERMOMETER (PVVX)",
                        temp = s16le(p, 6) / 100.0, hum = u16le(p, 8) / 100.0, batMv = u16le(p, 10), batPct = u8(p, 12)
                    )
                }
            }
            0xFEAA -> {
                if (p.size >= 14 && u8(p, 0) == 0x20) { // Eddystone-TLM
                    val mv = u16be(p, 2)
                    val t = s16be(p, 4)
                    return mk(
                        "EDDYSTONE TLM",
                        temp = if (t == -32768) null else t / 256.0,
                        batMv = if (mv == 0) null else mv,
                        extra = listOf(
                            "UPTIME" to fmt(u32be(p, 10) / 10.0 / 3600.0, 1) + " h",
                            "ADV COUNT" to "${u32be(p, 6)}"
                        )
                    )
                }
            }
            0xFD3D -> { // SwitchBot Meter / Meter Plus / Outdoor Meter
                val model = if (p.isNotEmpty()) u8(p, 0) and 0x7F else 0
                if ((model == 0x54 || model == 0x69 || model == 0x77) && p.size >= 6) {
                    val mag = (u8(p, 4) and 0x7F) + (u8(p, 3) and 0x0F) / 10.0
                    return mk(
                        "SWITCHBOT METER",
                        temp = if ((u8(p, 4) and 0x80) != 0) mag else -mag,
                        hum = (u8(p, 5) and 0x7F).toDouble(), batPct = u8(p, 2) and 0x7F
                    )
                }
            }
        }
        return null
    }
}
