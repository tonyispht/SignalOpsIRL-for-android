package com.example.signalopsirl

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a shareable report of the devices this session could not put in a category (no rule matched, or only the
 * maker is known), with the advertising clues needed to write new scanner_config.json rules.
 *
 * Privacy: no full MAC addresses (only the manufacturer prefix of public addresses), personal names in device names
 * ("Tony's AirPods") are replaced with [NAME], and name / address structures are removed from the raw packets.
 */
object UnknownExport {

    /** True for a device the classifier could not put in a category. */
    fun isUnidentified(d: SeenDevice): Boolean = d.cls == null || d.cls.category == null

    private val POSSESSIVE = Regex("^(.{1,30}?)(['’`]s|s['’])\\s+", RegexOption.IGNORE_CASE)

    /** "Tony's AirPods" -> "[NAME]'s AirPods". */
    internal fun scrubName(n: String?): String? {
        if (n.isNullOrBlank()) return null
        return POSSESSIVE.replace(n.trim()) { "[NAME]'s " }
    }

    /** Raw advert hex with names (0x08/0x09) and addresses (0x17/0x18/0x1B) taken out. */
    internal fun cleanPacket(pk: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pk.size) {
            val l = pk[i].toInt() and 0xFF
            if (l == 0 || i + 1 + l > pk.size) break
            val type = pk[i + 1].toInt() and 0xFF
            if (type !in setOf(0x08, 0x09, 0x17, 0x18, 0x1B)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(AdDecoder.hex(pk, i, i + 1 + l).replace(" ", ""))
            }
            i += l + 1
        }
        return sb.toString()
    }

    private fun hex4(v: Int) = "0x%04X".format(v)

    /** JSON report of the unidentified devices in [devices]. */
    fun build(ctx: Context, devices: Collection<SeenDevice>): JSONObject {
        val unknown = devices.filter { isUnidentified(it) }
        // Rotating-address devices send the same advert from many addresses: group by name + stable advert shape.
        val groups = LinkedHashMap<String, MutableList<SeenDevice>>()
        for (d in unknown.sortedByDescending { it.rssi }) {
            val tokens = ScanBus.rawAds[d.address]?.tokens?.sorted()?.joinToString("|")
                ?: (d.companyIds.sorted().joinToString(",") + "/" + d.services.sorted().joinToString(","))
            val key = (scrubName(d.name) ?: "") + "#" + tokens + "#" + d.cod
            groups.getOrPut(key) { ArrayList() }.add(d)
        }

        val list = JSONArray()
        for (g in groups.values) {
            val d = g.first()
            val o = JSONObject()
            scrubName(d.name)?.let { o.put("name", it) }
            o.put("count", g.size)
            o.put("best_rssi", g.maxOf { it.rssi })
            o.put("transport", when (d.transport) { 2 -> "classic"; 3 -> "ble+classic"; else -> "ble" })
            val local = Vendors.isLocal(d.address)
            o.put("address", if (local) "random" else "public")
            if (!local) {
                o.put("oui", d.address.take(8).uppercase(Locale.ROOT))
                Vendors.lookup(d.address)?.let { o.put("oui_vendor", it) }
            }
            if (d.companyIds.isNotEmpty()) {
                o.put("company_ids", JSONArray(d.companyIds.sorted().map { id ->
                    hex4(id) + (Classifier.companyName(id)?.let { " $it" } ?: "")
                }))
            }
            if (d.services.isNotEmpty()) {
                o.put("services", JSONArray(d.services.sorted().map { u ->
                    val s = BtDb.short16(u)
                    if (s >= 0) hex4(s) + (BtDb.uuid16(s)?.let { " ${it.name}" } ?: "") else u
                }))
            }
            if (d.appleTypes.isNotEmpty()) o.put("apple", AdDecoder.appleKinds(d.appleTypes))
            if (d.hints.isNotEmpty()) o.put("hints", JSONArray(d.hints.sorted()))
            ClassicBt.text(d.cod)?.let { o.put("device_class", it) }
            d.cls?.let { c ->
                c.label?.let { o.put("shown_as", it) }
                o.put("reason", c.reason)
            }
            ScanBus.rawAds[d.address]?.packets?.take(2)?.map { cleanPacket(it) }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.let { o.put("raw", JSONArray(it)) }
            list.put(o)
        }

        val root = JSONObject()
        root.put("report", "Signal OPS IRL unidentified devices")
        root.put("app_version", Updater.installedName(ctx))
        root.put("version_code", Updater.installedCode(ctx))
        root.put("android_sdk", Build.VERSION.SDK_INT)
        root.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        root.put("bt_db", BtDb.built ?: "?")
        root.put("config", Classifier.summary() ?: "?")
        root.put("devices_seen", devices.size)
        root.put("unidentified", unknown.size)
        root.put("groups", list.length())
        root.put("devices", list)
        return root
    }

    /** Writes the report to the app cache and opens the Android share sheet. Returns a status line. */
    fun share(ctx: Context, devices: Collection<SeenDevice>): String {
        val json = build(ctx, devices)
        val n = json.optInt("groups")
        if (n == 0) return "NOTHING TO EXPORT: EVERY DEVICE WAS IDENTIFIED"
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val f = File(dir, "signalops-unidentified-$stamp.json")
        f.writeText(json.toString(2))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Signal OPS IRL unidentified devices")
            putExtra(Intent.EXTRA_TEXT, "Signal OPS IRL: $n unidentified device types. Please add rules for these.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send unidentified devices").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "EXPORTED $n DEVICE TYPES"
    }
}
