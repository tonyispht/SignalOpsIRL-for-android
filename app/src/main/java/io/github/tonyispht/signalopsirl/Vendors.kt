package io.github.tonyispht.signalopsirl

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline MAC-address -> manufacturer lookup for Bluetooth devices that advertise a public (fixed) address.
 *
 * The full IEEE registry (MA-L, MA-M and MA-S blocks) comes pre-installed in assets/btdb/oui.tsv, built by
 * tools/build_bt_db.py, so it works from the first launch with no download. A newer list can still be fetched
 * or imported from Settings; those entries are kept in app storage and override the bundled ones.
 */
object Vendors {
    @Volatile private var table: Map<String, String> = emptyMap()

    /** Entries in use (bundled + downloaded/imported). */
    val count = MutableStateFlow(0)
    /** Entries that came pre-installed with the app. */
    val bundled = MutableStateFlow(0)
    /** Entries added on top by DOWNLOAD UPDATE or IMPORT FILE. */
    val extra = MutableStateFlow(0)
    @Volatile private var loaded = false
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    private val HEX = Regex("^[0-9A-F]+$")
    private val WS = Regex("\\s+")

    private fun savedFile(ctx: Context) = File(ctx.filesDir, "vendors.tsv")

    // ---- lookup -----------------------------------------------------------------------------

    /** Bit 1 of the first octet marks a locally administered (random/private) address. */
    fun isLocal(address: String): Boolean {
        val first = address.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    /** Longest-prefix match so MA-S (36-bit) and MA-M (28-bit) blocks beat the 24-bit parent. */
    fun lookup(address: String): String? {
        val t = table
        if (t.isEmpty()) return null
        val hex = address.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length < 6) return null
        return t[hex.take(9)] ?: t[hex.take(7)] ?: t[hex.take(6)]
    }

    // ---- load / save ------------------------------------------------------------------------

    /** Blocking; call off the main thread. Loads once; the activity and the scan service both call it. */
    @Synchronized
    fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        val m = HashMap<String, String>(80_000)
        readBundled(ctx, m)
        // Older builds could bundle a raw IEEE CSV here; still honoured if present.
        try {
            ctx.assets.open("oui.csv").use { parse(it, m) }
        } catch (_: Exception) {
        }
        bundled.value = m.size
        val saved = HashMap<String, String>()
        readSaved(ctx, saved)
        extra.value = saved.keys.count { it !in m }
        m.putAll(saved)
        publish(m)
        loaded = true
    }

    /** assets/btdb/oui.tsv: "PREFIX<TAB>Vendor", PREFIX = 6, 7 or 9 hex digits. */
    private fun readBundled(ctx: Context, into: MutableMap<String, String>) {
        runCatching {
            ctx.assets.open("btdb/oui.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (l in lines) {
                    val i = l.indexOf('\t')
                    if (i in 6..9 && i < l.length - 1) into[l.substring(0, i)] = l.substring(i + 1)
                }
            }
        }
    }

    private fun readSaved(ctx: Context, into: MutableMap<String, String>) {
        val f = savedFile(ctx)
        if (!f.exists()) return
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (l in lines) {
                    val i = l.indexOf('\t')
                    if (i > 0) into[l.substring(0, i)] = l.substring(i + 1)
                }
            }
        }
    }

    private fun publish(m: Map<String, String>) {
        table = m
        count.value = m.size
    }

    @Synchronized
    private fun merge(ctx: Context, add: Map<String, String>) {
        val saved = HashMap<String, String>()
        readSaved(ctx, saved)
        saved.putAll(add)
        val tmp = File(ctx.filesDir, "vendors.tsv.tmp")
        tmp.bufferedWriter().use { w ->
            for ((k, v) in saved) {
                w.write(k); w.write("\t"); w.write(v); w.write("\n")
            }
        }
        val dest = savedFile(ctx)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        val all = HashMap(table)
        val before = all.size
        all.putAll(add)
        extra.value += all.size - before
        publish(all)
    }

    /** Removes downloaded/imported updates; the pre-installed list stays. */
    @Synchronized
    fun clear(ctx: Context) {
        savedFile(ctx).delete()
        load(ctx, force = true)
        status.value = "UPDATES REMOVED \u00B7 BUILT-IN LIST KEPT"
    }

    // ---- download / import -------------------------------------------------------------------

    /** Blocking; call off the main thread. Returns a status line. */
    fun download(ctx: Context): String {
        val sources = listOf(
            Triple("https://standards-oui.ieee.org/oui/oui.csv", "MA-L", true),
            Triple("https://standards-oui.ieee.org/oui28/mam.csv", "MA-M", false),
            Triple("https://standards-oui.ieee.org/oui36/oui36.csv", "MA-S", false)
        )
        val got = HashMap<String, String>()
        for ((i, s) in sources.withIndex()) {
            status.value = "DOWNLOADING ${s.second} (${i + 1}/${sources.size})..."
            try {
                fetch(s.first, got)
            } catch (e: Exception) {
                if (s.third) return "DOWNLOAD FAILED: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (got.isEmpty()) return "NO DATA RECEIVED"
        merge(ctx, got)
        return "UPDATE DONE \u00B7 ${got.size} ENTRIES CHECKED"
    }

    private fun fetch(url: String, into: MutableMap<String, String>) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SignalOpsIRL")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { parse(it, into) }
        } finally {
            c.disconnect()
        }
    }

    /** Blocking; call off the main thread. */
    fun importStream(ctx: Context, input: InputStream): String {
        val got = HashMap<String, String>()
        parse(input, got)
        if (got.isEmpty()) return "NO VALID ENTRIES FOUND"
        merge(ctx, got)
        return "IMPORTED ${got.size} ENTRIES"
    }

    // ---- parsing -----------------------------------------------------------------------------

    private fun parse(input: InputStream, into: MutableMap<String, String>): Int {
        var n = 0
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            while (true) {
                var line = r.readLine() ?: break
                // A quoted CSV field can span lines: keep joining while a quote is still open.
                while (line.count { it == '"' } % 2 == 1) {
                    val next = r.readLine() ?: break
                    line += " $next"
                }
                val e = parseLine(line) ?: continue
                into[e.first] = e.second
                n++
            }
        }
        return n
    }

    private fun clean(s: String) = s.trim().replace(WS, " ")

    private fun parseLine(raw: String): Pair<String, String>? {
        val line = raw.trim().trimStart('\uFEFF')
        if (line.isEmpty() || line.startsWith("#")) return null

        // IEEE CSV: Registry,Assignment,Organization Name,Organization Address
        if (line.startsWith("MA-") || line.startsWith("IAB,")) {
            val f = csvFields(line)
            if (f.size < 3) return null
            val key = f[1].trim().uppercase()
            if (key.length !in 6..9 || !HEX.matches(key)) return null
            val name = clean(f[2])
            return if (name.isEmpty()) null else key to name
        }

        // Wireshark "manuf" (tab separated, optional /mask) or Nmap/plain ("286FB9 Name")
        val parts = line.split('\t')
        val tok: String
        val name: String
        if (parts.size >= 2) {
            tok = parts[0].trim()
            name = clean(parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: parts[1])
        } else {
            val i = line.indexOfFirst { it.isWhitespace() }
            if (i < 0) return null
            tok = line.substring(0, i)
            name = clean(line.substring(i))
        }
        val mask = tok.substringAfter('/', "").toIntOrNull()
        val hex = tok.substringBefore('/').filter { it != ':' && it != '-' && it != '.' }.uppercase()
        if (hex.length < 6 || !HEX.matches(hex)) return null
        val len = if (mask != null) mask / 4 else 6
        if (len !in 6..9 || hex.length < len) return null
        if (mask == null && hex.length != 6) return null
        return if (name.isEmpty()) null else hex.substring(0, len) to name
    }

    private fun csvFields(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
