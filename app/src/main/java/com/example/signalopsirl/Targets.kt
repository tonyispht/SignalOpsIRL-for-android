package com.example.signalopsirl

import java.util.concurrent.ConcurrentHashMap

/**
 * Everything a target rule can look at for one device: its name and address, signal strength, the company IDs,
 * service UUIDs and Apple message types heard so far, what the config rules identified it as, and the raw
 * advertising packets (parsed lazily, only when a rule actually needs the bytes).
 */
class MatchInput(
    val name: String?,
    val address: String,
    val rssi: Int,
    val companyIds: Set<Int> = emptySet(),
    val services: Set<String> = emptySet(),
    val appleTypes: Set<Int> = emptySet(),
    val cls: Classification? = null,
    val packets: List<ByteArray> = emptyList(),
    val advert: Advert? = null,
    /** Bit 0 = heard as BLE, bit 1 = heard by classic Bluetooth discovery. */
    val transport: Int = 1,
    /** Classic class of device (major + minor), -1 = none reported. */
    val cod: Int = -1
) {
    class Mfg(val cid: Int, val data: ByteArray)
    class SvcData(val uuid: String, val data: ByteArray)
    class Parsed(
        val mfg: List<Mfg>,
        val svcData: List<SvcData>,
        /** Every service UUID the device advertises or sends service data for, as 32 upper-case hex digits. */
        val uuids: Set<String>,
        val appearance: Set<Int>,
        val raws: List<ByteArray>
    )

    val parsed: Parsed by lazy { Targets.parseAds(this) }
    val vendor: String? by lazy { if (Vendors.isLocal(address)) null else Vendors.lookup(address) }
}

/** A hex byte pattern. `?` (or `.` / `X`) is a wildcard for one hex digit, so `4?` matches 0x40..0x4F and `??` any byte. */
class BytePattern(private val v: IntArray, private val mask: IntArray, val anywhere: Boolean) {
    fun matches(d: ByteArray): Boolean {
        val n = v.size
        if (n == 0 || d.size < n) return false
        val last = if (anywhere) d.size - n else 0
        for (off in 0..last) {
            var ok = true
            for (i in 0 until n) {
                if ((d[off + i].toInt() and mask[i]) != v[i]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }
}

class Term(
    val negate: Boolean,
    /** True when the answer depends on the one packet being looked at (bytes), so it can flicker between packets. */
    val bound: Boolean,
    val test: (MatchInput) -> Boolean
)

class TargetRule(
    val terms: List<Term>,
    /** Minimum signal strength (dBm) from an `@-60` suffix, or null. */
    val gate: Int?,
    /** Short label for the target list: MAC, PFX, NAME, CID, MFG, UUID... */
    val kind: String,
    /** A plain name / MAC / prefix entry with no extras. */
    val plain: Boolean
) {
    /** 0 = no match, 1 = match, 2 = match that depends on the current packet's bytes (see [Matcher.eval]). */
    fun eval(m: MatchInput): Int {
        if (gate != null && m.rssi < gate) return 0
        for (t in terms) if (t.test(m) == t.negate) return 0
        return if (gate == null && terms.any { it.bound && !it.negate }) 2 else 1
    }
}

class Compiled(val rule: TargetRule?, val error: String?)

object Targets {
    private const val TAIL = "00001000800000805F9B34FB"

    private val KEYS = setOf(
        "CID", "COMPANY", "MFG", "UUID", "SVC", "SVCDATA", "ADV", "APPLE", "APPEARANCE",
        "NAME", "EXACT", "STARTS", "ENDS", "REGEX", "MAC", "VENDOR", "CLASS", "TRANSPORT", "COD"
    )

    private val KEYED = Regex("^(!)?([A-Za-z]+):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val GATE_TAIL = Regex("""\s+@\s*(-?\d{1,3})\s*$""")
    private val GATE_ONLY = Regex("""^@\s*(-?\d{1,3})$""")
    private val AND = Regex("""\s+&\s+""")
    private val MAC_WILD = Regex("^([0-9A-Fa-f?]{2}[:-]){1,5}[0-9A-Fa-f?]{2}$")

    private val APPLE_NAMES = mapOf(
        "AIRDROP" to 0x05, "HOMEKIT" to 0x06, "AIRPODS" to 0x07, "PAIRING" to 0x07, "BEATS" to 0x07,
        "AIRPLAY" to 0x09, "MAGICSWITCH" to 0x0B, "HANDOFF" to 0x0C, "TETHERINGTARGET" to 0x0D,
        "TETHERINGSOURCE" to 0x0E, "NEARBYACTION" to 0x0F, "NEARBY" to 0x10, "NEARBYINFO" to 0x10,
        "FINDMY" to 0x12, "IBEACON" to 0x0215
    )

    private val cache = ConcurrentHashMap<String, Compiled>()

    fun compile(entry: String): Compiled = cache.getOrPut(entry) {
        runCatching { build(entry) }.getOrElse { Compiled(null, "BAD ENTRY") }
    }

    // ---- reading the advertising bytes --------------------------------------------------------

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun hex(v: Long, n: Int) = v.toString(16).uppercase().padStart(n, '0')

    fun uuid16(v: Int) = "0000" + hex(v.toLong(), 4) + TAIL
    fun uuid32(v: Long) = hex(v, 8) + TAIL
    private fun uuid128(b: ByteArray, s: Int) =
        (15 downTo 0).joinToString("") { hex(u8(b, s + it).toLong(), 2) }

    fun parseAds(m: MatchInput): MatchInput.Parsed {
        val mfg = ArrayList<MatchInput.Mfg>()
        val svc = ArrayList<MatchInput.SvcData>()
        val uuids = HashSet<String>(m.services)
        val app = HashSet<Int>()
        val raws = ArrayList<ByteArray>(m.packets)
        m.advert?.let { a ->
            for ((id, b) in a.mfg) mfg.add(MatchInput.Mfg(id, b))
            a.services.forEach { uuids.add(it.uppercase()) }
            a.raw?.let { r -> AdDecoder.trim(r).takeIf { it.isNotEmpty() }?.let { raws.add(it) } }
        }
        for (p in raws) {
            var i = 0
            while (i < p.size) {
                val l = u8(p, i)
                if (l == 0 || i + 1 + l > p.size) break
                val type = u8(p, i + 1)
                val s = i + 2
                val e = i + 1 + l
                val n = e - s
                when (type) {
                    0x02, 0x03, 0x14 -> { var k = s; while (k + 2 <= e) { uuids.add(uuid16(u8(p, k) or (u8(p, k + 1) shl 8))); k += 2 } }
                    0x04, 0x05, 0x1F -> {
                        var k = s
                        while (k + 4 <= e) {
                            val v = (u8(p, k).toLong()) or (u8(p, k + 1).toLong() shl 8) or
                                (u8(p, k + 2).toLong() shl 16) or (u8(p, k + 3).toLong() shl 24)
                            uuids.add(uuid32(v)); k += 4
                        }
                    }
                    0x06, 0x07, 0x15 -> { var k = s; while (k + 16 <= e) { uuids.add(uuid128(p, k)); k += 16 } }
                    0x16 -> if (n >= 2) {
                        val u = uuid16(u8(p, s) or (u8(p, s + 1) shl 8))
                        svc.add(MatchInput.SvcData(u, p.copyOfRange(s + 2, e))); uuids.add(u)
                    }
                    0x20 -> if (n >= 4) {
                        val v = (u8(p, s).toLong()) or (u8(p, s + 1).toLong() shl 8) or
                            (u8(p, s + 2).toLong() shl 16) or (u8(p, s + 3).toLong() shl 24)
                        val u = uuid32(v)
                        svc.add(MatchInput.SvcData(u, p.copyOfRange(s + 4, e))); uuids.add(u)
                    }
                    0x21 -> if (n >= 16) {
                        val u = uuid128(p, s)
                        svc.add(MatchInput.SvcData(u, p.copyOfRange(s + 16, e))); uuids.add(u)
                    }
                    0x19 -> if (n >= 2) app.add(u8(p, s) or (u8(p, s + 1) shl 8))
                    0xFF -> if (n >= 2) {
                        val cid = u8(p, s) or (u8(p, s + 1) shl 8)
                        val data = p.copyOfRange(s + 2, e)
                        if (mfg.none { it.cid == cid && it.data.contentEquals(data) }) mfg.add(MatchInput.Mfg(cid, data))
                    }
                }
                i += l + 1
            }
        }
        return MatchInput.Parsed(mfg, svc, uuids, app, raws)
    }

    // ---- pattern helpers ------------------------------------------------------------------------

    fun parsePattern(s: String, defaultAnywhere: Boolean): BytePattern? {
        var t = s.trim()
        var anywhere = defaultAnywhere
        if (t.startsWith("*")) { anywhere = true; t = t.drop(1) }
        else if (t.startsWith("^")) { anywhere = false; t = t.drop(1) }
        if (t.startsWith("0x", ignoreCase = true)) t = t.drop(2)
        val c = t.filter { it != ' ' && it != ':' && it != '-' && it != '_' }
            .uppercase().replace('.', '?').replace('X', '?')
        if (c.isEmpty() || c.length % 2 != 0 || c.any { !(it in '0'..'9' || it in 'A'..'F' || it == '?') }) return null
        val n = c.length / 2
        val v = IntArray(n)
        val mask = IntArray(n)
        for (i in 0 until n) {
            val hi = c[2 * i]
            val lo = c[2 * i + 1]
            if (hi != '?') { v[i] = v[i] or (hi.digitToInt(16) shl 4); mask[i] = mask[i] or 0xF0 }
            if (lo != '?') { v[i] = v[i] or lo.digitToInt(16); mask[i] = mask[i] or 0x0F }
        }
        return BytePattern(v, mask, anywhere)
    }

    /** Does [pat] (hex digits, `?` = any digit) match [hex] starting at [from]? */
    fun nibbleMatch(pat: String, hex: String, from: Int): Boolean {
        if (from < 0 || from + pat.length > hex.length) return false
        for (i in pat.indices) if (pat[i] != '?' && pat[i] != hex[from + i]) return false
        return true
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    private class UuidSpec(val full: String, val wild: Boolean) {
        fun matches(u: String) = if (wild) u.startsWith(full) else u == full
    }

    private fun parseUuid(s: String): UuidSpec? {
        var t = s.trim().removePrefix("0x").removePrefix("0X").replace("-", "").uppercase()
        val wild = t.endsWith("*")
        if (wild) t = t.dropLast(1)
        if (!isHex(t)) return null
        if (wild) return UuidSpec(t, true)
        return when (t.length) {
            4 -> UuidSpec(uuid16(t.toInt(16)), false)
            8 -> UuidSpec(uuid32(t.toLong(16)), false)
            32 -> UuidSpec(t, false)
            else -> null
        }
    }

    private class CompanySpec(val any: Boolean, val ids: Set<Int>, val name: String?) {
        private var ver = -1
        private var cached: Set<Int> = emptySet()
        fun resolve(): Set<Int> {
            if (name == null) return ids
            if (ver != Classifier.version) { cached = Classifier.companyIdsNamed(name); ver = Classifier.version }
            return cached
        }
    }

    private fun parseCompany(s: String, nameMode: Boolean): CompanySpec? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t == "*" || t.equals("ANY", true)) return CompanySpec(true, emptySet(), null)
        val hasPrefix = t.startsWith("0x", true)
        val h = if (hasPrefix) t.drop(2) else t
        if ((hasPrefix || !nameMode) && isHex(h) && h.length <= 4) return CompanySpec(false, setOf(h.toInt(16)), null)
        return if (nameMode) CompanySpec(false, emptySet(), t) else null
    }

    private fun splitSpec(v: String): Pair<String, String> {
        val i = v.indexOf(':')
        return if (i < 0) v.trim() to "" else v.substring(0, i).trim() to v.substring(i + 1).trim()
    }

    // ---- building a rule ------------------------------------------------------------------------

    private fun globToRegex(g: String): Regex =
        Regex("^" + g.split("*").joinToString(".*") { part ->
            part.map { if (it == '?') "." else Regex.escape(it.toString()) }.joinToString("")
        } + "$", RegexOption.IGNORE_CASE)

    private fun macTerm(pat: String): Pair<Term?, String?> {
        val suffix = pat.trim().startsWith("*")
        val c = pat.filter { it.isLetterOrDigit() || it == '?' }.uppercase()
        if (c.isEmpty() || c.length > 12 || c.any { !(it in '0'..'9' || it in 'A'..'F' || it == '?') }) {
            return null to "BAD MAC PATTERN"
        }
        return Term(false, false) { m ->
            val hexAddr = m.address.filter { it.isLetterOrDigit() }.uppercase()
            if (suffix) nibbleMatch(c, hexAddr, hexAddr.length - c.length) else nibbleMatch(c, hexAddr, 0)
        } to null
    }

    private fun plainTerm(t: String): Pair<Term?, String?> = when {
        Matcher.isMac(t) -> {
            val want = Matcher.normalizeMac(t)
            Term(false, false) { m -> m.address.uppercase() == want } to null
        }
        Matcher.isMacPrefix(t) -> {
            val want = Matcher.normalizeMac(t)
            Term(false, false) { m -> m.address.uppercase().startsWith(want) } to null
        }
        t.contains('?') && MAC_WILD.matches(t) -> macTerm(t)
        t.contains('*') || t.contains('?') -> {
            val re = globToRegex(t)
            Term(false, false) { m -> m.name?.let { re.matches(it) } == true } to null
        }
        else -> Term(false, false) { m -> m.name?.contains(t, ignoreCase = true) == true } to null
    }

    private fun keyedTerm(neg: Boolean, key: String, value: String): Pair<Term?, String?> {
        val v = value.trim()
        if (v.isEmpty()) return null to "$key: EMPTY VALUE"
        fun t(bound: Boolean = false, f: (MatchInput) -> Boolean) = Term(neg, bound && !neg, f)
        when (key) {
            "CID", "MFG", "COMPANY" -> {
                val (specStr, rest) = splitSpec(v)
                val spec = parseCompany(specStr, key == "COMPANY")
                    ?: return null to "$key: BAD COMPANY (HEX ID LIKE 004C, OR A NAME WITH COMPANY:)"
                val pat = if (rest.isBlank()) null else
                    parsePattern(rest, false) ?: return null to "$key: BAD HEX PATTERN"
                return t(bound = pat != null) { m ->
                    val ids = spec.resolve()
                    if (pat == null) {
                        if (spec.any) m.companyIds.isNotEmpty() || m.parsed.mfg.isNotEmpty()
                        else ids.any { it in m.companyIds } || m.parsed.mfg.any { it.cid in ids }
                    } else {
                        m.parsed.mfg.any { (spec.any || it.cid in ids) && pat.matches(it.data) }
                    }
                } to null
            }
            "UUID", "SVC" -> {
                val u = parseUuid(v) ?: return null to "$key: BAD UUID (4, 8 OR 32 HEX DIGITS)"
                return t { m -> m.parsed.uuids.any { u.matches(it) } } to null
            }
            "SVCDATA" -> {
                val (us, rest) = splitSpec(v)
                val u = parseUuid(us) ?: return null to "$key: BAD UUID (4, 8 OR 32 HEX DIGITS)"
                val pat = if (rest.isBlank()) null else
                    parsePattern(rest, false) ?: return null to "$key: BAD HEX PATTERN"
                return t(bound = true) { m ->
                    m.parsed.svcData.any { u.matches(it.uuid) && (pat == null || pat.matches(it.data)) }
                } to null
            }
            "ADV" -> {
                val pat = parsePattern(v, true) ?: return null to "$key: BAD HEX PATTERN"
                return t(bound = true) { m -> m.parsed.raws.any { pat.matches(it) } } to null
            }
            "APPLE" -> {
                val k = v.uppercase().replace(" ", "").replace("-", "").replace("_", "")
                val id = APPLE_NAMES[k] ?: k.removePrefix("0X").takeIf { isHex(it) && it.length <= 4 }?.toInt(16)
                    ?: return null to "$key: BAD TYPE (HEX LIKE 10, OR AIRPODS / NEARBY / HANDOFF / FINDMY / IBEACON)"
                return t { m -> id in m.appleTypes } to null
            }
            "APPEARANCE" -> {
                val c = v.removePrefix("0x").removePrefix("0X").uppercase().replace('.', '?').replace('X', '?')
                if (c.isEmpty() || c.length > 4 || c.any { !(it in '0'..'9' || it in 'A'..'F' || it == '?') }) {
                    return null to "$key: BAD VALUE (UP TO 4 HEX DIGITS)"
                }
                val p = c.padStart(4, '0')
                return t(bound = true) { m -> m.parsed.appearance.any { nibbleMatch(p, hex(it.toLong(), 4), 0) } } to null
            }
            "NAME" -> return t { m -> m.name?.contains(v, ignoreCase = true) == true } to null
            "EXACT" -> return t { m -> m.name?.equals(v, ignoreCase = true) == true } to null
            "STARTS" -> return t { m -> m.name?.startsWith(v, ignoreCase = true) == true } to null
            "ENDS" -> return t { m -> m.name?.endsWith(v, ignoreCase = true) == true } to null
            "REGEX" -> {
                val re = runCatching { Regex(v, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return null to "$key: BAD REGEX"
                return t { m -> m.name?.let { re.containsMatchIn(it) } == true } to null
            }
            "MAC" -> {
                val (term, err) = macTerm(v)
                return (term?.let { Term(neg, false, it.test) }) to err
            }
            "VENDOR" -> return t { m ->
                m.vendor?.contains(v, ignoreCase = true) == true ||
                    m.companyIds.any { Classifier.companyName(it)?.contains(v, ignoreCase = true) == true }
            } to null
            "TRANSPORT" -> {
                val k = v.uppercase()
                val f: (Int) -> Boolean = when (k) {
                    "CLASSIC", "BT", "BREDR" -> ({ x: Int -> x and 2 != 0 })
                    "BLE", "LE" -> ({ x: Int -> x and 1 != 0 })
                    "BOTH", "DUAL" -> ({ x: Int -> x == 3 })
                    else -> return null to "$key: USE CLASSIC, BLE OR BOTH"
                }
                return t { m -> f(m.transport) } to null
            }
            "COD" -> {
                val f = ClassicBt.spec(v)
                    ?: return null to "$key: BAD CLASS (PHONE, AUDIO, HEADPHONES, KEYBOARD... OR HEX LIKE 0418)"
                return t { m -> f(m.cod) } to null
            }
            "CLASS" -> return t { m ->
                m.cls?.let { c ->
                    listOfNotNull(c.label, c.title, c.category, c.reason).any { it.contains(v, ignoreCase = true) }
                } == true
            } to null
        }
        return null to "UNKNOWN TYPE $key"
    }

    private fun build(entry: String): Compiled {
        var body = entry.trim()
        if (body.isEmpty()) return Compiled(null, "EMPTY")
        var gateRaw: Int? = null
        val tail = GATE_TAIL.find(body)
        val only = GATE_ONLY.find(body)
        if (tail != null) {
            gateRaw = tail.groupValues[1].toInt()
            body = body.substring(0, tail.range.first).trim()
        } else if (only != null) {
            gateRaw = only.groupValues[1].toInt()
            body = ""
        }
        var gate: Int? = null
        if (gateRaw != null) {
            val n = if (gateRaw > 0) -gateRaw else gateRaw
            if (n < -120 || n > -1) return Compiled(null, "@ SIGNAL MUST BE -1 TO -120 DBM")
            gate = n
        }
        if (body.isEmpty() && gate == null) return Compiled(null, "EMPTY")

        val parts = if (body.isEmpty()) emptyList() else body.split(AND).map { it.trim() }.filter { it.isNotEmpty() }
        fun keyOf(p: String): String? =
            KEYED.matchEntire(p)?.groupValues?.get(2)?.uppercase()?.takeIf { it in KEYS }
        val compound = parts.size > 1 && parts.any { keyOf(it) != null }
        val pieces = if (compound || parts.size <= 1) parts else listOf(body)

        val terms = ArrayList<Term>()
        val kinds = ArrayList<String>()
        for (p in pieces) {
            val km = KEYED.matchEntire(p)
            val key = km?.groupValues?.get(2)?.uppercase()
            val (term, err) = if (km != null && key != null && key in KEYS) {
                kinds.add(shortKind(key))
                keyedTerm(km.groupValues[1] == "!", key, km.groupValues[3])
            } else {
                kinds.add(when {
                    Matcher.isMac(p) -> "MAC"
                    Matcher.isMacPrefix(p) -> "PFX"
                    p.contains('?') && MAC_WILD.matches(p) -> "MAC"
                    else -> "NAME"
                })
                plainTerm(p)
            }
            if (term == null) return Compiled(null, err ?: "BAD ENTRY")
            terms.add(term)
        }
        val kind = when {
            terms.isEmpty() -> "NEAR"
            terms.size == 1 && gate == null -> kinds[0]
            else -> "RULE"
        }
        val plain = terms.size == 1 && gate == null && kinds[0] in setOf("MAC", "PFX", "NAME") && !compound &&
            keyOf(pieces[0]) == null
        return Compiled(TargetRule(terms, gate, kind, plain), null)
    }

    private fun shortKind(key: String) = when (key) {
        "COMPANY" -> "CO"
        "SVC" -> "UUID"
        "APPEARANCE" -> "APPR"
        "SVCDATA" -> "SDATA"
        "STARTS", "ENDS", "EXACT", "REGEX" -> "NAME"
        "TRANSPORT" -> "XPORT"
        else -> key
    }

    // ---- suggestions for the tap menu -----------------------------------------------------------

    class Suggestion(val label: String, val entry: String)

    /** Targets that would match this device, built from what it is actually advertising. */
    fun suggest(d: SeenDevice, raw: RawAd?): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        fun add(label: String, entry: String) { if (out.none { it.entry.equals(entry, true) }) out.add(Suggestion(label, entry)) }
        d.name?.takeIf { it.isNotBlank() }?.let { add("NAME  $it", it) }
        add("MAC  ${d.address}", d.address)
        val pfx = d.address.take(8)
        if (pfx.length == 8 && !Vendors.isLocal(d.address)) {
            add("PREFIX  $pfx" + (Vendors.lookup(d.address)?.let { "  ($it)" } ?: ""), pfx)
        }
        for (id in d.companyIds.sorted()) {
            val code = hex(id.toLong(), 4)
            add("COMPANY ID  0x$code" + (Classifier.companyName(id)?.let { "  ($it)" } ?: ""), "CID:$code")
        }
        val m = MatchInput(
            d.name, d.address, d.rssi, d.companyIds, d.services, d.appleTypes, d.cls,
            raw?.packets ?: emptyList(), null, d.transport, d.cod
        )
        if (d.transport and 2 != 0) {
            add("CLASSIC BLUETOOTH  heard by classic discovery", "TRANSPORT:CLASSIC")
            ClassicBt.text(d.cod)?.let { t ->
                add("DEVICE CLASS  $t", "COD:" + "%04X".format(d.cod and 0xFFFF))
                val major = t.substringBefore(" \u00B7 ").substringBefore("/")
                if (ClassicBt.spec(major) != null) add("DEVICE CLASS  $major (ANY)", "COD:$major")
            }
        }
        val p = m.parsed
        for (u in p.uuids.sorted()) add("SERVICE UUID  ${shortUuid(u)}", "UUID:${shortUuid(u)}")
        for (t in d.appleTypes.sorted()) {
            val code = hex(t.toLong(), if (t > 0xFF) 4 else 2)
            add("APPLE MESSAGE  ${AdDecoder.appleKinds(setOf(t))}", "APPLE:$code")
        }
        for (x in p.mfg) {
            val head = x.data.take(4).joinToString("") { hex((it.toInt() and 0xFF).toLong(), 2) }
            val c = hex(x.cid.toLong(), 4)
            if (head.isNotEmpty()) add("MFG DATA  $c:$head", "MFG:$c:$head")
        }
        for (x in p.svcData) {
            val head = x.data.take(3).joinToString("") { hex((it.toInt() and 0xFF).toLong(), 2) }
            add("SERVICE DATA  ${shortUuid(x.uuid)}" + if (head.isNotEmpty()) ":$head" else "",
                "SVCDATA:${shortUuid(x.uuid)}" + if (head.isNotEmpty()) ":$head" else "")
        }
        for (a in p.appearance) add("APPEARANCE  0x${hex(a.toLong(), 4)}", "APPEARANCE:${hex(a.toLong(), 4)}")
        d.cls?.let { c ->
            (c.label ?: c.title).takeIf { it.isNotBlank() }?.let { add("CLASS  $it", "CLASS:$it") }
        }
        return out
    }

    /** 0000FE9F-0000-1000-8000-00805F9B34FB -> FE9F; other UUIDs stay in full. */
    fun shortUuid(u: String): String = when {
        u.length == 32 && u.endsWith(TAIL) && u.startsWith("0000") -> u.substring(4, 8)
        u.length == 32 && u.endsWith(TAIL) -> u.substring(0, 8)
        else -> u
    }
}
