package com.example.signalopsirl

import java.io.BufferedReader
import java.util.Locale

/**
 * The offline Bluetooth database that ships inside the app (assets/btdb/, built by tools/build_bt_db.py):
 *  - every Bluetooth SIG company identifier (the ID at the start of manufacturer data),
 *  - every 16-bit service UUID: SIG services, member UUIDs registered to companies, and standards-body UUIDs,
 *  - GAP appearance values (what kind of device it says it is).
 * The IEEE MAC-prefix list in the same folder is loaded by [Vendors].
 *
 * No Android dependencies, so it can be unit-tested on a plain JVM. Loading from assets is in ClassifierAssets.kt.
 */
object BtDb {
    class Uuid(val name: String, val kind: String) {
        /** True for a UUID registered to a company (0xFCxx-0xFExx); [name] is then the owner. */
        val member get() = kind == "member"
    }

    @Volatile private var companies: Map<Int, String> = emptyMap()
    @Volatile private var uuids: Map<Int, Uuid> = emptyMap()
    @Volatile private var appearance: Map<Int, Pair<String, String>> = emptyMap()
    @Volatile private var fastPair: Map<Int, Pair<String, String>> = emptyMap()

    /** "2026-11-06" from assets/btdb/VERSION, or null before loading. */
    @Volatile var built: String? = null
        private set

    val isLoaded get() = companies.isNotEmpty()
    val companyCount get() = companies.size
    val uuidCount get() = uuids.size
    val appearanceCount get() = appearance.size
    val fastPairCount get() = fastPair.size

    // ---- loading -------------------------------------------------------------------------------

    @Synchronized
    fun load(companiesTsv: BufferedReader?, uuidTsv: BufferedReader?, appearanceTsv: BufferedReader?, version: String?) {
        companiesTsv?.use { r ->
            val m = HashMap<Int, String>(4600)
            r.forEachLine { l -> row(l)?.let { (k, f) -> k.toIntOrNull(16)?.let { m[it] = f[0] } } }
            companies = m
        }
        uuidTsv?.use { r ->
            val m = HashMap<Int, Uuid>(1000)
            r.forEachLine { l -> row(l)?.let { (k, f) -> k.toIntOrNull(16)?.let { m[it] = Uuid(f[0], f.getOrElse(1) { "" }) } } }
            uuids = m
        }
        appearanceTsv?.use { r ->
            val m = HashMap<Int, Pair<String, String>>(400)
            r.forEachLine { l -> row(l)?.let { (k, f) -> k.toIntOrNull(16)?.let { m[it] = f[0] to f.getOrElse(1) { "" } } } }
            appearance = m
        }
        built = version?.substringBefore('\t')?.trim()?.ifEmpty { null }
    }

    /** assets/btdb/fastpair.tsv: Google Fast Pair model ID -> (name, kind: audio | watch | phone | spam). */
    @Synchronized
    fun loadFastPair(tsv: BufferedReader?) {
        tsv?.use { r ->
            val m = HashMap<Int, Pair<String, String>>(600)
            r.forEachLine { l -> row(l)?.let { (k, f) -> k.toIntOrNull(16)?.let { m[it] = f[0] to f.getOrElse(1) { "audio" } } } }
            fastPair = m
        }
    }

    /** (product name, kind) for a Fast Pair model ID, or null when the model is not in the list. */
    fun fastPair(model: Int): Pair<String, String>? = fastPair[model]

    private fun row(l: String): Pair<String, List<String>>? {
        val parts = l.split('\t')
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts.drop(1)
    }

    // ---- lookups -------------------------------------------------------------------------------

    fun company(id: Int): String? = companies[id]

    /** Company IDs whose SIG name contains [q] (case-insensitive). */
    fun companyIdsNamed(q: String): Set<Int> =
        if (q.isBlank()) emptySet() else companies.filterValues { it.contains(q, ignoreCase = true) }.keys

    fun uuid16(u: Int): Uuid? = uuids[u]

    /** 16-bit value of a full 32-hex-digit SIG-base UUID ("0000FEED00001000800000805F9B34FB" -> 0xFEED), else -1. */
    fun short16(full: String): Int {
        if (full.length != 32 || !full.startsWith("0000") || !full.endsWith(BASE_SUFFIX)) return -1
        return full.substring(4, 8).toIntOrNull(16) ?: -1
    }

    /** Name of a full service UUID (any format) when it is a SIG-base 16-bit UUID in the database. */
    fun serviceName(uuid: String): String? {
        val k = uuid.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        val s = if (k.length == 4) k.toIntOrNull(16) ?: -1 else short16(k)
        return if (s < 0) null else uuids[s]?.name
    }

    /** "Watch · Smartwatch", "Human Interface Device · Keyboard", or the category alone; null when unknown. */
    fun appearanceName(v: Int): String? {
        if (v < 0) return null
        appearance[v and 0xFFFF]?.let { (c, s) -> return if (s.isEmpty()) c else "$c · $s" }
        return appearance[v and 0xFFC0]?.first
    }

    const val BASE_SUFFIX = "00001000800000805F9B34FB"
}
