package com.example.signalopsirl

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

enum class TrackLevel { WATCHING, LINGERING, FOLLOWING }

/** One tracker being watched, as shown in the Trackers card. */
data class TrackerStatus(
    val key: String,
    val address: String,
    val label: String,
    val level: TrackLevel,
    val minutes: Int,
    /** How far you have moved (farthest point from where it was first heard), in metres; -1 = no location. */
    val meters: Int,
    val rssi: Int,
    val lastSeen: Long
)

/**
 * Warns when a tracker (AirTag / Find My accessory, Tile, SmartTag and other item trackers) keeps showing up around you,
 * which is how someone can be followed with a tag slipped into a bag or car.
 *
 * It follows each tracker's address while you scan. A tracker counts as FOLLOWING when it has been heard on and off for
 * 10+ minutes AND you have moved 250 m or more since it was first heard (needs the location permission). Without location
 * it can only say LINGERING (heard for 20+ minutes at a decent signal), since staying put and being followed look the same.
 * Trackers you mark MINE are ignored. Limits: a tag that changes its address (owner nearby: about every 15 minutes) starts
 * a new count, and an AirTag away from its owner keeps one address for about a day, which is the case this is built for.
 * Locations stay in memory only and are not saved.
 */
object TrackerWatch {
    private const val FOLLOW_MS = 10 * 60_000L
    private const val FOLLOW_METERS = 250f
    private const val LINGER_MS = 30 * 60_000L          // with location: heard this long but you did not move
    private const val LINGER_NOLOC_MS = 20 * 60_000L    // without location
    private const val FORGET_MS = 45 * 60_000L
    private const val SAMPLE_MS = 5_000L
    private const val MIN_RSSI = -88
    private const val STEP_METERS = 30f
    private const val FIX_MAX_AGE_MS = 120_000L
    private const val FIX_MAX_ACCURACY = 75f
    private const val MAX_RECS = 150

    private class Rec(val key: String, val address: String, val first: Long) {
        var label = ""
        var last = first
        var lastSample = 0L
        var samples = 0
        val minutes = HashSet<Long>()
        var rssi = -100f
        var firstLoc: Location? = null
        var lastLoc: Location? = null
        var disp = 0f
        var level = TrackLevel.WATCHING
        var alerted = TrackLevel.WATCHING
        var locSeen = false
    }

    private val recs = HashMap<String, Rec>()

    /** Trackers worth showing (heard for 2+ minutes, or flagged), strongest concern first. */
    val status = MutableStateFlow<List<TrackerStatus>>(emptyList())

    /** Addresses and linked-device keys the user marked as their own. */
    private val mine: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var mineLoaded = false

    @Volatile private var fix: Location? = null
    @Volatile private var fixAt = 0L

    private const val KEY_MINE = "trackerMine"

    fun load(store: Store) {
        if (mineLoaded) return
        mineLoaded = true
        runCatching {
            val a = JSONArray(store.prefs.getString(KEY_MINE, "[]") ?: "[]")
            for (i in 0 until a.length()) mine.add(a.getString(i))
        }
    }

    fun setFix(l: Location) {
        fix = l
        fixAt = System.currentTimeMillis()
    }

    fun hasRecentFix(now: Long) = fix != null && now - fixAt <= FIX_MAX_AGE_MS

    fun isMine(addr: String, key: String) = addr in mine || key in mine

    /** Marks a tracker as the user's own so it never raises a warning. */
    fun markMine(store: Store, key: String, address: String) {
        mine.add(key)
        mine.add(address)
        store.prefs.edit().putString(KEY_MINE, JSONArray(mine.toList()).toString()).apply()
        recs.remove(key)
        recs.remove(address)
        publish(System.currentTimeMillis())
    }

    fun clear() {
        recs.clear()
        status.value = emptyList()
    }

    /** True for item trackers: Find My accessories, Tile, SmartTag-style tags and other "valuables" / pet trackers. */
    fun isTracker(d: SeenDevice): Boolean {
        val c = d.cls?.category
        return c == "trackers_valuables" || c == "pet_trackers" ||
            (0x004C in d.companyIds && 0x12 in d.appleTypes) || 0x067C in d.companyIds
    }

    /**
     * Call for each advertisement from a tracker. Returns the level to warn about the first time it is reached
     * (FOLLOWING, or LINGERING when there is no location to tell whether you are moving), otherwise null.
     */
    fun onSighting(d: SeenDevice, key: String, label: String, now: Long): TrackLevel? {
        if (isMine(d.address, key) || d.rssi < MIN_RSSI) return null
        val r = recs.getOrPut(key) { Rec(key, d.address, now) }
        r.last = now
        r.label = label
        if (now - r.lastSample < SAMPLE_MS) return null
        r.lastSample = now
        r.samples++
        r.minutes.add(now / 60_000L)
        r.rssi = if (r.samples == 1) d.rssi.toFloat() else r.rssi * 0.8f + d.rssi * 0.2f

        val f = fix
        if (f != null && now - fixAt <= FIX_MAX_AGE_MS && f.accuracy <= FIX_MAX_ACCURACY) {
            r.locSeen = true
            val first = r.firstLoc
            if (first == null) {
                r.firstLoc = f
                r.lastLoc = f
            } else {
                val step = (r.lastLoc ?: first).distanceTo(f)
                if (step >= STEP_METERS) r.lastLoc = f
                r.disp = maxOf(r.disp, first.distanceTo(f))
            }
        }

        val span = now - r.first
        val coverage = r.minutes.size.toFloat() / maxOf(1L, span / 60_000L + 1L)
        val lvl = when {
            r.locSeen && span >= FOLLOW_MS && r.disp >= FOLLOW_METERS && r.samples >= 6 && coverage >= 0.4f -> TrackLevel.FOLLOWING
            r.locSeen && span >= LINGER_MS && coverage >= 0.5f -> TrackLevel.LINGERING
            !r.locSeen && span >= LINGER_NOLOC_MS && coverage >= 0.6f && r.rssi >= -80f -> TrackLevel.LINGERING
            else -> TrackLevel.WATCHING
        }
        if (lvl > r.level) r.level = lvl
        // Warn once per level. A stationary tracker (location known, you did not move) is only listed, never warned about.
        val warn = r.level > r.alerted && (r.level == TrackLevel.FOLLOWING || (r.level == TrackLevel.LINGERING && !r.locSeen))
        if (warn) {
            r.alerted = r.level
            return r.level
        }
        return null
    }

    /** Drops old trackers and refreshes [status]; called a few times a second by the scan service. */
    fun publish(now: Long) {
        if (recs.size > MAX_RECS) {
            recs.values.removeAll { now - it.last >= 10 * 60_000L && it.level == TrackLevel.WATCHING }
        }
        recs.values.removeAll { now - it.last >= FORGET_MS }
        val out = recs.values
            .filter { it.level != TrackLevel.WATCHING || it.minutes.size >= 2 }
            .map {
                TrackerStatus(
                    it.key, it.address, it.label, it.level, ((it.last - it.first) / 60_000L).toInt(),
                    if (it.locSeen) it.disp.toInt() else -1, it.rssi.toInt(), it.last
                )
            }
            .sortedWith(compareByDescending<TrackerStatus> { it.level }.thenByDescending { it.minutes })
        if (out != status.value) status.value = out
    }
}
