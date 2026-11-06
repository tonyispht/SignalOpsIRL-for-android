package com.example.signalopsirl

import kotlin.math.abs

/** One address of an Apple device that is currently in use: the kind of advert it sends and when it was last heard. */
class AddrStream(val kind: String, var last: Long, var rssi: Float)

/** One physical device as far as we can tell, possibly seen under several rotating (random) addresses. */
class Identity(val id: Int, var addr: String, var tokens: Set<String>, var rssi: Float, now: Long) {
    /** Apple devices only: every address of this device in use right now (one per advert kind), by address. */
    val streams = HashMap<String, AddrStream>()
    /** Every address linked to this device, oldest first. */
    val addrs = LinkedHashSet<String>()
    var last = now
    var linkedAt = 0L
    /** After a conflict (two live devices looked alike) this identity stops absorbing new addresses for a while. */
    var noLinkUntil = 0L

    init {
        addrs.add(addr)
    }
}

/** [oldAddr] is the address this device used before, when [identity] just took over a new one. */
class IdentityResult(
    val identity: Identity, val oldAddr: String?, val other: Identity? = null,
    /** True when this call linked a new address to the identity (so the UI's address list needs a refresh). */
    val changed: Boolean = false
)

/**
 * Links the changing random addresses of one device together.
 *
 * Devices with a resolvable private address swap it every few minutes, so one phone, watch or tracker shows up as
 * a stream of "new" devices. What does not change is the shape of its advertisements: which AD structures it sends,
 * their lengths, its service UUIDs, company ID, Tx power, name. When a new random address appears with an
 * advertisement pattern compatible with an identity whose address just went quiet (and a similar signal strength),
 * the new address is treated as that device's next address. If the old address then comes back, the two were
 * really different devices and the link is undone.
 */
/** How much better the best candidate must score than the runner-up (dB-like units) before two addresses are linked. */
private const val AMBIGUOUS_MARGIN = 3f

/** Apple adverts: a new address of the same kind that appears this soon after another one went quiet is its successor. */
private const val HANDOVER_MS = 5_000L
private const val HANDOVER_RSSI = 10f
/** Apple adverts: a device that just changed one address and starts a new kind of advert within this time is the same device. */
private const val CORRELATED_MS = 2_000L
private const val CORRELATED_RSSI = 6f
private const val STREAM_TTL_MS = 60_000L

/** Which Apple message decides what kind of advert this is (Find My, AirPods, Nearby Info...). iBeacon (02) is left out: it never rotates. */
private val APPLE_KIND_ORDER = listOf("12", "07", "10", "0C", "0F")

class IdentityTracker {
    /** Receives one short line for every link and split, for the LINK LOG in settings. */
    var log: ((String) -> Unit)? = null
    private var nextId = 1
    private val byAddr = HashMap<String, Identity>()
    private val all = LinkedHashMap<Int, Identity>()
    private var lastPrune = 0L

    fun clear() {
        byAddr.clear()
        all.clear()
    }

    /** True for an address that a newer address of the same device has replaced. */
    fun isSuperseded(addr: String): Boolean = byAddr[addr]?.let { it.addr != addr && it.streams[addr] == null } ?: false

    /** The addresses linked to an identity, newest first. */
    fun addresses(id: Int): List<String> = all[id]?.addrs?.toList()?.asReversed() ?: emptyList()

    /**
     * Random addresses have bit 6 of the first octet set; addresses of known manufacturers are public.
     * Apple also sends adverts from non-resolvable private addresses (top bits 00), which have no marker, so for
     * Apple adverts any address that is not in the IEEE vendor list counts (only once that list is loaded).
     */
    private fun looksRandom(addr: String, apple: Boolean): Boolean {
        val first = addr.take(2).toIntOrNull(16) ?: return false
        if ((first and 0x40) != 0) return Vendors.lookup(addr) == null
        return apple && Vendors.count.value > 0 && Vendors.lookup(addr) == null
    }

    /** The kind of Apple continuity advert in [tokens], or null when it is not one. */
    private fun appleKind(tokens: Set<String>): String? {
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (t in tokens) {
            if (!t.startsWith("FF:004C:")) continue
            val ty = t.split(':').getOrNull(2) ?: continue
            if (ty == "02") continue
            val idx = APPLE_KIND_ORDER.indexOf(ty)
            val rank = if (idx < 0) APPLE_KIND_ORDER.size else idx
            if (rank < bestRank) {
                best = ty
                bestRank = rank
            }
        }
        return best
    }

    private fun tail(a: String) = a.takeLast(5)

    /** Needs something more characteristic than flags and Tx power to be worth matching on. */
    private fun distinctive(tokens: Set<String>): Boolean =
        tokens.size >= 2 && tokens.any { !(it.startsWith("01:") || it.startsWith("0A:")) }

    private fun compatible(a: Set<String>, b: Set<String>): Boolean {
        val small = if (a.size <= b.size) a else b
        val big = if (a.size <= b.size) b else a
        return small.size >= 2 && big.containsAll(small)
    }

    private fun create(addr: String, tokens: Set<String>, rssi: Int, now: Long, kind: String?): Identity {
        val i = Identity(nextId++, addr, tokens, rssi.toFloat(), now)
        if (kind != null) i.streams[addr] = AddrStream(kind, now, rssi.toFloat())
        all[i.id] = i
        byAddr[addr] = i
        return i
    }

    private fun prune(now: Long) {
        if (now - lastPrune < 10_000L) return
        lastPrune = now
        val it = all.values.iterator()
        while (it.hasNext()) {
            val i = it.next()
            if (now - i.last > 30 * 60_000L) {
                i.addrs.forEach { a -> if (byAddr[a] === i) byAddr.remove(a) }
                it.remove()
            }
        }
    }

    /** Makes [addr] the newest address of [b]. [old] is the address of the same advert kind it replaces, if any. */
    private fun attach(
        b: Identity, addr: String, tokens: Set<String>, rssi: Int, now: Long, kind: String?, old: String?
    ): IdentityResult {
        val prev = old ?: b.addr
        b.addr = addr
        b.addrs.add(addr)
        byAddr[addr] = b
        b.tokens = if (kind != null) b.tokens + tokens else tokens
        b.rssi = rssi.toFloat()
        b.last = now
        b.linkedAt = now
        if (kind != null) {
            if (old != null) b.streams.remove(old)
            b.streams.entries.removeAll { now - it.value.last > STREAM_TTL_MS }
            b.streams[addr] = AddrStream(kind, now, rssi.toFloat())
        }
        while (b.addrs.size > 12) {
            val oldest = b.addrs.first()
            b.addrs.remove(oldest)
            b.streams.remove(oldest)
            if (byAddr[oldest] === b) byAddr.remove(oldest)
        }
        return IdentityResult(b, prev, changed = true)
    }

    /**
     * Apple devices rotate their addresses on a timer and send several kinds of advert (Nearby Info, Find My...).
     * Two rules, both on timing and signal strength only:
     *  1. HANDOVER: a new address of the same advert kind appearing within seconds of one of that kind going quiet.
     *  2. CORRELATED: a different kind of advert starting right after the device changed another address.
     * Both refuse to guess when two devices fit about equally well.
     */
    private fun appleLink(addr: String, kind: String, tokens: Set<String>, rssi: Int, now: Long): IdentityResult? {
        var best: Identity? = null
        var bestOld: String? = null
        var bestScore = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        var bestGap = 0L
        var bestDr = 0f
        for (c in all.values) {
            if (now < c.noLinkUntil) continue
            var cScore = Float.MAX_VALUE
            var cOld: String? = null
            var cGap = 0L
            var cDr = 0f
            for ((a, st) in c.streams) {
                if (st.kind != kind) continue
                val gap = now - st.last
                if (gap > HANDOVER_MS) continue
                val dr = abs(st.rssi - rssi)
                if (dr > HANDOVER_RSSI) continue
                val sc = dr + gap / 1000f
                if (sc < cScore) {
                    cScore = sc; cOld = a; cGap = gap; cDr = dr
                }
            }
            if (cOld == null) continue
            if (cScore < bestScore) {
                second = bestScore
                best = c; bestOld = cOld; bestScore = cScore; bestGap = cGap; bestDr = cDr
            } else if (cScore < second) {
                second = cScore
            }
        }
        val b = best
        if (b != null) {
            if (second - bestScore >= AMBIGUOUS_MARGIN) {
                log?.invoke("HANDOVER #${b.id} ${tail(bestOld!!)}>${tail(addr)} kind $kind gap ${bestGap}ms d${"%.0f".format(bestDr)}dB")
                return attach(b, addr, tokens, rssi, now, kind, bestOld)
            }
            log?.invoke("SKIP ${tail(addr)} kind $kind: ambiguous handover (2+ devices fit)")
            return null
        }

        var cb: Identity? = null
        var cbScore = Float.MAX_VALUE
        var cbSecond = Float.MAX_VALUE
        for (c in all.values) {
            if (now < c.noLinkUntil || c.streams.isEmpty()) continue
            val since = now - c.linkedAt
            if (since > CORRELATED_MS) continue
            if (c.streams.values.any { it.kind == kind && now - it.last <= HANDOVER_MS }) continue
            val dr = abs(c.rssi - rssi)
            if (dr > CORRELATED_RSSI) continue
            val sc = dr + since / 1000f
            if (sc < cbScore) {
                cbSecond = cbScore
                cb = c; cbScore = sc
            } else if (sc < cbSecond) {
                cbSecond = sc
            }
        }
        val c = cb
        if (c != null && cbSecond - cbScore >= AMBIGUOUS_MARGIN) {
            log?.invoke("CORRELATED #${c.id} +${tail(addr)} kind $kind")
            return attach(c, addr, tokens, rssi, now, kind, null)
        }
        return null
    }

    /** Null for addresses that are not random (they never rotate, so there is nothing to link). */
    fun observe(addr: String, tokens: Set<String>, rssi: Int, now: Long): IdentityResult? {
        val kind = appleKind(tokens)
        if (!looksRandom(addr, kind != null)) return null
        prune(now)

        val known = byAddr[addr]
        if (known != null) {
            val st = known.streams[addr]
            if (known.addr == addr || st != null) {
                known.last = now
                known.rssi = known.rssi * 0.6f + rssi * 0.4f
                if (st != null) {
                    st.last = now
                    st.rssi = st.rssi * 0.6f + rssi * 0.4f
                }
                if (!known.tokens.containsAll(tokens)) known.tokens = known.tokens + tokens
                return IdentityResult(known, null)
            }
            // A stray packet from the old address just after the swap is normal.
            if (now - known.linkedAt < 3_000L) return IdentityResult(known, null)
            // The old address is back while its supposed successor is still around: two devices, not one.
            known.addrs.remove(addr)
            known.streams.remove(addr)
            byAddr.remove(addr)
            known.noLinkUntil = now + 600_000L
            val fresh = create(addr, tokens, rssi, now, kind)
            fresh.noLinkUntil = now + 600_000L
            log?.invoke("SPLIT #${known.id} / #${fresh.id}: ${tail(addr)} came back")
            return IdentityResult(fresh, null, other = known, changed = true)
        }

        if (kind != null) appleLink(addr, kind, tokens, rssi, now)?.let { return it }

        var best: Identity? = null
        var bestScore = Float.MAX_VALUE
        var secondScore = Float.MAX_VALUE
        if (distinctive(tokens)) {
            for (c in all.values) {
                if (c.addr == addr || now < c.noLinkUntil) continue
                val gap = now - c.last
                if (gap > 120_000L) continue
                val dr = abs(c.rssi - rssi)
                if (dr > 15f) continue
                if (!compatible(c.tokens, tokens)) continue
                val score = dr + gap / 5000f
                if (score < bestScore) {
                    secondScore = bestScore
                    best = c
                    bestScore = score
                } else if (score < secondScore) {
                    secondScore = score
                }
            }
        }
        val b = best
        // Two candidates that fit about equally well (a crowd of similar phones): do not guess. A wrong link hides a
        // real device; a missed link only shows a rotated address as a new row until the old one ages out.
        if (b != null && secondScore - bestScore >= AMBIGUOUS_MARGIN) {
            log?.invoke("LINK #${b.id} ${tail(b.addr)}>${tail(addr)} pattern")
            return attach(b, addr, tokens, rssi, now, kind, if (kind != null) b.addr else null)
        }
        return IdentityResult(create(addr, tokens, rssi, now, kind), null)
    }
}
