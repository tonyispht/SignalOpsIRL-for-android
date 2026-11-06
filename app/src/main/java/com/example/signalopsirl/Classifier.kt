package com.example.signalopsirl

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Device identification driven by assets/scanner_config.json (the same file the desktop scanner
 * script reads). It labels each advertising device with a category from whatever evidence the
 * advertisement carries: name, MAC prefix, GATT service UUIDs, manufacturer (company) IDs, and the
 * IEEE vendor that owns the MAC prefix.
 *
 * This file has no Android dependencies on purpose, so the logic can be unit-tested on a plain JVM.
 * Loading from assets lives in ClassifierAssets.kt.
 */

enum class Tier { ALERT, POSSIBLE, INFO }

data class Classification(
    /** Canonical category key (aliases already resolved), or null when only a company/label is known. */
    val category: String?,
    val title: String,
    val icon: String,
    val tier: Tier,
    /** Specific device or vendor name when the evidence gives one (OUI owner, service rule, company). */
    val label: String?,
    /** Short evidence string, e.g. name "ring doorbell" or OUI 00:25:DF. */
    val reason: String,
    val civilian: Boolean = true,
    /**
     * True when the evidence only says "some device of this kind" (Apple Find My, a bare company ID, a
     * generic service). Such devices usually rotate their random MAC address, so the UI groups them
     * into one counted row instead of listing every address separately.
     */
    val anon: Boolean = false
)

/** One kind of device the classifier knows (an entry of the Signal Dex TYPES page). */
data class CategoryInfo(val key: String, val title: String, val icon: String, val tier: Tier)

/** Everything one device has advertised so far. */
class Evidence(
    val address: String,
    val name: String?,
    val companyIds: Set<Int> = emptySet(),
    /** Service UUIDs as 32 uppercase hex digits without dashes. */
    val services: Set<String> = emptySet(),
    /** Apple continuity message types seen (0x0215 = iBeacon). */
    val appleTypes: Set<Int> = emptySet(),
    /** Owner of the MAC prefix from the IEEE database (null for random addresses). */
    val vendor: String? = null
)

object Classifier {
    @Volatile private var db: Db? = null

    /** Bumps every time a config is loaded, so cached results can be refreshed. */
    @Volatile var version = 0
        private set

    val isReady: Boolean get() = db != null

    /** Message from the last failed config load (null = none), so the UI can show it. */
    @Volatile var loadError: String? = null

    @Synchronized
    fun loadFrom(json: String) {
        db = Db(JSONObject(json))
        loadError = null
        version++
        Dex.refresh()   // the Signal Dex totals depend on the category list
    }

    fun classify(e: Evidence): Classification? = db?.classify(e)

    /** Company ID of the hardware maker found in Flock battery and camera modules (used for the screen-off filters). */
    const val XUNTONG_ID = 0x09C8

    /** Bluetooth SIG company name for a manufacturer-data ID, if the loaded config knows it. */
    fun companyName(id: Int): String? = db?.companyName(id)

    /** Company IDs whose name contains [q] (case-insensitive), e.g. "espressif" -> 0x02E5. */
    fun companyIdsNamed(q: String): Set<Int> = db?.companyIdsNamed(q) ?: emptySet()

    /** Every category of the loaded config (plus the built-in ones), sorted by title. Empty until a config is loaded. */
    fun categories(): List<CategoryInfo> = db?.categoryList() ?: emptyList()

    /** One-line description of what is loaded, for the UI. */
    fun summary(): String? = db?.summary

    /** Short text for a Signals row, e.g. "ALERT · 🔐 SECURITY & ACCESS CONTROL · name \"ring doorbell\"". */
    fun tag(c: Classification): String {
        val head = if (c.category != null) listOf(c.icon, c.title).filter { it.isNotEmpty() }.joinToString(" ") else ""
        val tier = when (c.tier) {
            Tier.ALERT -> "ALERT"
            Tier.POSSIBLE -> "POSSIBLE"
            Tier.INFO -> ""
        }
        return listOf(tier, head, c.label.orEmpty(), c.reason).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

// ---- name helpers (internal so tests can reach them) ---------------------------------------------

/** Lowercase, underscores to spaces, whitespace collapsed. Terms and names are both run through this. */
internal fun normName(s: String): String {
    val sb = StringBuilder(s.length)
    var space = false
    for (ch in s.lowercase(Locale.ROOT)) {
        val c = if (ch == '_') ' ' else ch
        if (c.isWhitespace()) {
            space = true
            continue
        }
        if (space && sb.isNotEmpty()) sb.append(' ')
        space = false
        sb.append(c)
    }
    return sb.toString()
}

/**
 * Whole-word containment: the term may not touch a letter on either side (digits are fine), so "ring"
 * does not match "string" but "m585" matches "m5850". The check is only applied on an edge where the
 * term itself starts/ends with a letter or digit, so terms such as "brother hl-" still match "HL-L2270DW".
 */
internal fun hasWord(hay: String, term: String): Boolean {
    if (term.isEmpty() || hay.length < term.length) return false
    var from = 0
    while (true) {
        val i = hay.indexOf(term, from)
        if (i < 0) return false
        val end = i + term.length
        val before = if (i > 0 && term.first().isLetterOrDigit()) hay[i - 1] else ' '
        val after = if (end < hay.length && term.last().isLetterOrDigit()) hay[end] else ' '
        if (!before.isLetter() && !after.isLetter()) return true
        from = i + 1
    }
}

// ---- database ------------------------------------------------------------------------------------

private val MAC_ANY = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

/** AABBCCDDEEFF: uppercase hex only, so addresses compare regardless of separators. */
private fun macKey(s: String) = s.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)

private class Cat(
    val key: String,
    val tier: Tier,
    val title: String,
    val icon: String,
    val civilian: Boolean
)

/** A plain term (whole-word) or a "re:" regex. */
private class NameMatcher(spec: String) {
    val term: String?
    val rx: Regex?

    init {
        if (spec.startsWith("re:")) {
            term = null
            rx = runCatching { Regex(spec.substring(3), RegexOption.IGNORE_CASE) }.getOrNull()
        } else {
            term = normName(spec)
            rx = null
        }
    }

    val valid get() = rx != null || !term.isNullOrEmpty()

    fun matches(normalized: String): Boolean =
        if (rx != null) rx.containsMatchIn(normalized) else hasWord(normalized, term!!)

    val text get() = term ?: rx?.pattern.orEmpty()
}

private class Rule(val cat: Cat, val m: NameMatcher)

private class ServiceRule(val key: String, val cat: Cat, val label: String, val score: Int) {
    fun matches(services: Set<String>): Boolean =
        if (key.length <= 4) ("0000" + key + BASE_SUFFIX) in services
        else services.any { it.startsWith(key) }

    companion object {
        const val BASE_SUFFIX = "00001000800000805F9B34FB"
    }
}

private val DEFAULT_TITLES = mapOf(
    "smartphones" to ("📱" to "SMARTPHONES"),
    "laptops_tablets" to ("💻" to "LAPTOPS · TABLETS"),
    "wearables_audio" to ("⌚" to "WATCHES · RINGS · AUDIO"),
    "handhelds_gaming" to ("🎮" to "GAMING · VR · HANDHELDS"),
    "hacker_pentest" to ("🛠" to "DEV BOARDS · HACKER GADGETS"),
    "smart_iot" to ("🏠" to "SMART HOME · IOT"),
    "networking_mesh" to ("📡" to "NETWORKING · MESH"),
    "auto_aerial" to ("🚗" to "VEHICLES · OBD · DASHCAMS"),
    "trackers_valuables" to ("📍" to "TRACKERS · KEY FINDERS"),
    "proximity_beacons" to ("🔔" to "BEACONS · ASSET TAGS"),
    "medical_health" to ("🩺" to "MEDICAL · HEALTH"),
    "industrial_logistics" to ("📦" to "INDUSTRIAL · LOGISTICS"),
    "fitness_gym" to ("🏋" to "FITNESS · SPORTS SENSORS"),
    "pet_trackers" to ("🐾" to "PET TRACKERS"),
    "smart_appliances" to ("🍳" to "SMART APPLIANCES"),
    "law_enforcement_tactical" to ("🚨" to "LAW ENFORCEMENT GEAR"),
    "law_enforcement_possible" to ("🚔" to "POSSIBLE LAW ENFORCEMENT / PUBLIC SAFETY")
)

/**
 * Small built-in rules that are not in the config because they need more than a name or a service:
 * Apple continuity message types, a few well-known company IDs.
 */
private const val APPLE = 0x004C

private class Db(root: JSONObject) {
    private val aliases = HashMap<String, String>()
    private val meta: JSONObject = root.optJSONObject("category_meta") ?: JSONObject()
    private val tactical = HashSet<String>()
    private val cats = HashMap<String, Cat>()

    private val companyNames = HashMap<Int, String>()
    private val ouiLabel = HashMap<String, String>()
    private val ouiConf = HashMap<String, String>()
    private val nameTerms = ArrayList<Pair<Cat, List<String>>>()
    private val regexRules = ArrayList<Rule>()
    private val alertNames = ArrayList<NameMatcher>()
    private val possibleNames = ArrayList<NameMatcher>()
    private val serviceRules = ArrayList<ServiceRule>()
    private val vendorRules = ArrayList<Rule>()
    private val customNames = HashMap<String, String>()
    private val ignoreAddr = HashSet<String>()
    private val ignoreName = HashSet<String>()
    val summary: String

    init {
        eachKey(root.optJSONObject("category_aliases")) { k, v -> aliases[k] = v.toString() }
        strings(root.optJSONArray("tactical_categories")).forEach { tactical.add(aliases[it] ?: it) }

        eachKey(root.optJSONObject("company_ids")) { k, v ->
            val id = k.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
            if (id != null) companyNames[id] = v.toString()
        }
        eachKey(root.optJSONObject("target_ouis")) { k, v -> ouiLabel[ouiKey(k)] = v.toString() }
        eachKey(root.optJSONObject("oui_confidence")) { k, v -> ouiConf[ouiKey(k)] = v.toString() }

        // JSONObject keeps insertion order on Android, and category order is the first-match priority.
        var termCount = 0
        eachKey(root.optJSONObject("categories")) { k, v ->
            val terms = strings(v as? JSONArray).map { normName(it) }.filter { it.isNotEmpty() }.distinct()
            termCount += terms.size
            nameTerms.add(cat(k) to terms)
        }
        eachKey(root.optJSONObject("category_regex")) { k, v ->
            val c = cat(k)
            strings(v as? JSONArray).forEach { rx ->
                val m = NameMatcher("re:$rx")
                if (m.valid) regexRules.add(Rule(c, m))
            }
        }
        strings(root.optJSONArray("alert_names")).map { NameMatcher(it) }.filter { it.valid }.forEach { alertNames.add(it) }
        strings(root.optJSONArray("possible_names")).map { NameMatcher(it) }.filter { it.valid }.forEach { possibleNames.add(it) }

        eachKey(root.optJSONObject("service_rules")) { k, v ->
            val a = v as? JSONArray ?: return@eachKey
            val key = k.replace("…", "").replace("...", "").replace("-", "").uppercase(Locale.ROOT)
            if (key.isNotEmpty() && a.length() >= 2) {
                serviceRules.add(ServiceRule(key, cat(a.optString(0)), a.optString(1), a.optInt(2, 50)))
            }
        }
        serviceRules.sortByDescending { it.score }

        eachKey(root.optJSONObject("vendor_categories")) { k, v ->
            val c = cat(k)
            strings(v as? JSONArray).map { NameMatcher(it) }.filter { it.valid }.forEach { vendorRules.add(Rule(c, it)) }
        }

        eachKey(root.optJSONObject("custom_names")) { k, v -> customNames[macKey(k)] = v.toString() }
        strings(root.optJSONArray("ignore_list")).forEach {
            if (MAC_ANY.matches(it.trim())) ignoreAddr.add(macKey(it)) else ignoreName.add(normName(it))
        }

        // The built-in rules create these on first use; make them exist now so the category list is complete from the start.
        listOf(
            "proximity_beacons", "trackers_valuables", "wearables_audio",
            "law_enforcement_tactical", "law_enforcement_possible"
        ).forEach { cat(it) }

        summary = "$termCount name terms · ${ouiLabel.size} OUIs · ${nameTerms.size} categories"
    }

    // ---- classification ------------------------------------------------------------------------

    fun classify(e: Evidence): Classification? {
        val addr = macKey(e.address)
        if (addr in ignoreAddr) return null
        val nm = e.name?.let { normName(it) }.orEmpty()
        if (nm.isNotEmpty() && nm in ignoreName) return null

        val base = core(e, addr, nm)
        val custom = customNames[addr] ?: return base
        return base?.copy(label = custom)
            ?: Classification(null, "", "", Tier.INFO, custom, "custom name")
    }

    private fun core(e: Evidence, addr: String, nm: String): Classification? {
        // 1-3. law-enforcement evidence (names, MAC prefix, company ID, service UUIDs), fused into one verdict
        fuseLawEnforcement(e, addr, nm)?.let { return it }
        val prefix = addr.take(6)
        val conf = ouiConf[prefix]
        val ouiText = if (prefix.length == 6) prefix.chunked(2).joinToString(":") else prefix

        // 4. category name terms, first category in file order wins
        if (nm.isNotEmpty()) {
            for ((c, terms) in nameTerms) {
                for (t in terms) {
                    if (hasWord(nm, t)) return make(c, c.tier, null, "name ${q(t)}")
                }
            }
            for (r in regexRules) {
                if (r.m.matches(nm)) return make(r.cat, r.cat.tier, null, "name pattern")
            }
        }

        // 5. GATT service UUIDs from the config, strongest first
        if (e.services.isNotEmpty()) {
            for (r in serviceRules) {
                if (r.matches(e.services)) {
                    return make(r.cat, r.cat.tier, r.label, "service ${r.key}", anon = true)
                }
            }
        }

        // 6. a few built-in rules (Apple continuity, Tile, Ruuvi, Eddystone)
        builtin(e)?.let { return it }

        // 7. weak MAC prefix hints (Raspberry Pi, Espressif)
        if (conf != null && conf.startsWith("info:")) {
            return make(cat(conf.substring(5)), Tier.INFO, ouiLabel[prefix], "OUI $ouiText")
        }

        // 8. vendor name -> category (IEEE owner, then SIG company names)
        val vendors = ArrayList<Pair<String, String>>(3)   // (display, normalized)
        e.vendor?.let { vendors.add(it to normName(it)) }
        e.companyIds.forEach { id -> companyNames[id]?.let { vendors.add(it to normName(it)) } }
        if (vendors.isNotEmpty()) {
            for (r in vendorRules) {
                val v = vendors.firstOrNull { r.m.matches(it.second) } ?: continue
                return make(r.cat, r.cat.tier, v.first, "vendor ${q(r.m.text)}")
            }
        }

        // 9. nothing categorised it; at least say who made it
        e.companyIds.firstNotNullOfOrNull { id -> companyNames[id]?.let { id to it } }?.let { (id, n) ->
            return Classification(null, "", "", Tier.INFO, n, "company ${hex4(id)}", anon = true)
        }
        return null
    }

    // ---- law-enforcement evidence fusion --------------------------------------------------------

    /** One independent piece of evidence. [kind] says what sort it is, so the same sort never counts twice. */
    private class Part(val kind: String, val weight: Int, val reason: String, val label: String? = null)

    /**
     * Collects every law-enforcement clue the advertisement carries instead of stopping at the first one:
     *  - a strong clue (an Axon / Motorola public-safety name, a verified Axon / WatchGuard / Flock OUI) is an ALERT on its own;
     *  - weak clues (shared component-vendor prefixes, the Flock battery maker's company ID, a few of the Raven service
     *    UUIDs, a generic radio or holster name) are POSSIBLE on their own, and become an ALERT when two *different kinds*
     *    of clue agree (for example a weak OUI plus the Flock battery company ID).
     * The reason line lists every clue, so the Signals row and the tap menu show why the device was flagged.
     */
    private fun fuseLawEnforcement(e: Evidence, addr: String, nm: String): Classification? {
        val parts = ArrayList<Part>(4)

        if (nm.isNotEmpty()) {
            val a = alertNames.firstOrNull { it.matches(nm) }
            val p = possibleNames.firstOrNull { it.matches(nm) }
            if (a != null) parts.add(Part("name", STRONG, "name ${q(a.text)}"))
            else if (p != null) parts.add(Part("name", WEAK_NAME, "name ${q(p.text)}"))
            else {
                // a name term from the law-enforcement categories (checked here so it can combine with other clues)
                loop@ for ((c, terms) in nameTerms) {
                    if (c.key != "law_enforcement_tactical" && c.key != "law_enforcement_possible") continue
                    for (t in terms) {
                        if (hasWord(nm, t)) {
                            parts.add(Part("name", if (c.key == "law_enforcement_tactical") STRONG else WEAK_NAME, "name ${q(t)}"))
                            break@loop
                        }
                    }
                }
            }
        }

        val prefix = addr.take(6)
        val ouiText = if (prefix.length == 6) prefix.chunked(2).joinToString(":") else prefix
        when (ouiConf[prefix]) {
            "alert" -> parts.add(Part("oui", STRONG, "OUI $ouiText", ouiLabel[prefix]))
            "possible" -> parts.add(Part("oui", WEAK_OUI, "OUI $ouiText", ouiLabel[prefix]))
        }

        if (XUNTONG in e.companyIds) {
            parts.add(Part("company", WEAK_COMPANY, "company 0x09C8", companyNames[XUNTONG] ?: "XUNTONG"))
        }

        // Flock Raven gunshot detectors advertise a set of custom 0x3x00 services (from public research, not verified on every unit).
        val raven = RAVEN_SERVICES.count { (it + ServiceRule.BASE_SUFFIX) in e.services }
        if (raven >= 3) parts.add(Part("service", 60, "$raven Raven services", "Flock Raven (probable)"))
        else if (raven == 2) parts.add(Part("service", 45, "2 Raven services", "Flock Raven (possible)"))
        else if (raven == 1) parts.add(Part("service", 10, "1 Raven service"))

        if (parts.isEmpty()) return null
        val strongest = parts.maxOf { it.weight }
        val total = parts.groupBy { it.kind }.values.sumOf { g -> g.maxOf { it.weight } }
        val tier = when {
            strongest >= STRONG || total >= 60 -> Tier.ALERT
            total >= 25 -> Tier.POSSIBLE
            else -> return null
        }
        val kinds = parts.map { it.kind }.distinct().size
        val reason = (if (kinds > 1) "$kinds CLUES: " else "") + parts.joinToString(" + ") { it.reason }
        val label = parts.firstNotNullOfOrNull { it.label }
        val c = cat(if (tier == Tier.ALERT) "law_enforcement_tactical" else "law_enforcement_possible")
        return make(c, tier, label, reason)
    }

    private fun builtin(e: Evidence): Classification? {
        if (APPLE in e.companyIds) {
            when {
                0x0215 in e.appleTypes -> return make(cat("proximity_beacons"), Tier.INFO, "iBeacon", "Apple type 02")
                0x12 in e.appleTypes -> return make(cat("trackers_valuables"), Tier.INFO, "Apple Find My accessory", "Apple type 12", anon = true)
                0x07 in e.appleTypes -> return make(cat("wearables_audio"), Tier.INFO, "Apple/Beats earbuds", "Apple type 07", anon = true)
            }
        }
        if (TILE in e.companyIds) return make(cat("trackers_valuables"), Tier.INFO, "Tile tracker", "company 0x067C")
        if (RUUVI in e.companyIds) return make(cat("proximity_beacons"), Tier.INFO, "RuuviTag", "company 0x0499")
        if (("0000FEAA" + ServiceRule.BASE_SUFFIX) in e.services) {
            return make(cat("proximity_beacons"), Tier.INFO, "Eddystone beacon", "service FEAA")
        }
        return null
    }

    // ---- helpers -------------------------------------------------------------------------------

    fun companyName(id: Int): String? = companyNames[id]

    fun companyIdsNamed(q: String): Set<Int> =
        companyNames.filterValues { it.contains(q, ignoreCase = true) }.keys

    private fun make(c: Cat, tier: Tier, label: String?, reason: String, anon: Boolean = false) =
        Classification(c.key, c.title, c.icon, tier, label, reason, c.civilian, anon)

    fun categoryList(): List<CategoryInfo> =
        cats.values.map { CategoryInfo(it.key, it.title, it.icon, it.tier) }.sortedBy { it.title }

    private fun cat(rawKey: String): Cat {
        val k = aliases[rawKey] ?: rawKey
        return cats.getOrPut(k) {
            val m = meta.optJSONObject(k)
            val d = DEFAULT_TITLES[k]
            val metaTier = m?.optString("tier").orEmpty()
            val tier = when {
                k in tactical || metaTier == "alert" -> Tier.ALERT
                metaTier == "possible" -> Tier.POSSIBLE
                else -> Tier.INFO
            }
            Cat(
                k, tier,
                m?.optString("title").orEmpty().ifEmpty { d?.second ?: k.replace('_', ' ').uppercase(Locale.ROOT) },
                m?.optString("icon").orEmpty().ifEmpty { d?.first ?: "" },
                m?.optBoolean("civilian", true) ?: true
            )
        }
    }

    private fun q(s: String) = "\"$s\""

    private fun hex4(id: Int) = "0x" + id.toString(16).uppercase(Locale.ROOT).padStart(4, '0')

    private fun ouiKey(s: String) = s.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }

    private inline fun eachKey(o: JSONObject?, f: (String, Any) -> Unit) {
        if (o == null) return
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            f(k, o.get(k))
        }
    }

    companion object {
        const val XUNTONG = 0x09C8
        const val TILE = 0x067C
        const val RUUVI = 0x0499

        // evidence weights for law-enforcement fusion
        const val STRONG = 100
        const val WEAK_NAME = 40
        const val WEAK_OUI = 30
        const val WEAK_COMPANY = 30
        val RAVEN_SERVICES = listOf("00003100", "00003200", "00003300", "00003400", "00003500")
    }
}
