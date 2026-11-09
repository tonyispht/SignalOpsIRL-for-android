package io.github.tonyispht.signalopsirl

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Daily quests and streaks.
 *
 * Every day three quests (one easy, one medium, one hard) are picked from a pool. The pick depends only on the
 * date, so it is the same all day and survives restarts. Quests complete by themselves the moment the goal is
 * reached and pay XP at once. The first quest finished each day counts the day for the streak. The streak only ever
 * goes up: skipped days never reset it or cost anything (like the login days in RAID: Shadow Legends). It gives a
 * bonus on quest XP.
 *
 * A "day" runs from 04:00 to 04:00 local time, so late-night scanning counts for the day it started in.
 *
 * Everything here runs under the same lock as [Game] (it is only entered from Game or from the UI on the main thread).
 */

/** What was done today; the quest goals are measured against these. */
data class DailyStats(
    val devices: Int = 0,
    val kinds: Int = 0,
    val possible: Int = 0,
    val alerts: Int = 0,
    val watch: Int = 0,
    val locates: Int = 0,
    val named: Int = 0,
    val idents: Int = 0,
    val sensors: Int = 0,
    val scanMin: Int = 0,
    /** Exact product models identified (Fast Pair, AirPods/Beats, Galaxy Buds, Swift Pair names). */
    val models: Int = 0,
    /** Smart-home devices found. */
    val home: Int = 0,
    /** Item trackers found. */
    val trackers: Int = 0
)

class QuestDef(
    val id: String,
    val name: String,
    val desc: String,
    /** 0 = easy, 1 = medium, 2 = hard. */
    val tier: Int,
    val goal: Int,
    val xp: Int,
    val progress: (DailyStats) -> Int
)

data class QuestState(val def: QuestDef, val progress: Int, val done: Boolean, val reward: Int)

data class DailyState(
    val day: Long = 0,
    val quests: List<QuestState> = emptyList(),
    /** Days counted so far (the first quest finished each day counts the day). It never goes down. */
    val streak: Int = 0,
    /** At least one quest done today, so today already counts for the streak. */
    val countedToday: Boolean = false,
    val perfect: Boolean = false,
    /** Extra quest XP from the streak, in percent. */
    val bonusPct: Int = 0,
    val perfectBonus: Int = 0,
    val nextMilestone: Int = 3,
    val prevMilestone: Int = 0,
    /** When the quests change (epoch ms). */
    val resetAtMs: Long = 0
)

object Daily {
    const val QUESTS_PER_DAY = 3
    const val MAX_BONUS_PCT = 50
    const val PERFECT_XP = 150
    /** Streak milestones: these, and then every 100 days. */
    val MILESTONES = intArrayOf(3, 7, 14, 30, 100)
    private const val DEVICE_CAP = 3_000
    private const val FILE = "daily_seen.txt"
    private const val ROLLOVER_HOUR = 4L

    private fun q(
        id: String, name: String, desc: String, tier: Int, goal: Int, xp: Int,
        p: (DailyStats) -> Int
    ) = QuestDef(id, name, desc, tier, goal, xp, p)

    val POOL: List<List<QuestDef>> = listOf(
        listOf(
            q("sweep", "SWEEP", "Find 15 different devices today.", 0, 15, 40) { it.devices },
            q("patrol", "ON PATROL", "Scan for 10 minutes today.", 0, 10, 40) { it.scanMin },
            q("kinds3", "VARIETY PACK", "Identify 3 kinds of device today.", 0, 3, 40) { it.kinds },
            q("label", "LABEL MAKER", "Give a device your own name today.", 0, 1, 40) { it.named },
            q("fox1", "QUICK TRACK", "Track a device until it is stronger than -50 dBm today.", 0, 1, 40) { it.locates },
            q("model1", "MODEL SPOTTER", "Identify an exact earbud, speaker or accessory model today.", 0, 1, 40) { it.models },
            q("tag2", "TAG WATCH", "Find 2 item trackers today.", 0, 2, 40) { it.trackers }
        ),
        listOf(
            q("net", "WIDE NET", "Find 40 different devices today.", 1, 40, 80) { it.devices },
            q("shift", "LONG SHIFT", "Scan for 30 minutes today.", 1, 30, 80) { it.scanMin },
            q("kinds6", "FIELD NOTES", "Identify 6 kinds of device today.", 1, 6, 80) { it.kinds },
            q("fox2", "DOUBLE TRACK", "Locate 2 different devices today.", 1, 2, 80) { it.locates },
            q("sens2", "SENSOR SWEEP", "Decode 2 different sensors today.", 1, 2, 80) { it.sensors },
            q("hunch", "HUNCH", "Find a POSSIBLE-tier device today.", 1, 1, 80) { it.possible },
            q("model3", "AUDIOPHILE", "Identify 3 exact earbud, speaker or accessory models today.", 1, 3, 80) { it.models },
            q("home3", "SMART HOME", "Find 3 smart-home devices today.", 1, 3, 80) { it.home }
        ),
        listOf(
            q("drag", "DRAGNET", "Find 100 different devices today.", 2, 100, 150) { it.devices },
            q("stake", "STAKEOUT SHIFT", "Scan for 60 minutes today.", 2, 60, 150) { it.scanMin },
            q("lock", "TARGET LOCK", "Find an ALERT-tier device today.", 2, 1, 150) { it.alerts },
            q("bull", "BULLSEYE", "Match a device on your target list today.", 2, 1, 150) { it.watch },
            q("kinds10", "ENCYCLOPEDIA", "Identify 10 kinds of device today.", 2, 10, 150) { it.kinds },
            q("fox3", "TRIPLE HUNT", "Locate 3 different devices today.", 2, 3, 150) { it.locates },
            q("sens4", "WEATHER STATION", "Decode 4 different sensors today.", 2, 4, 150) { it.sensors },
            q("model6", "GEAR COLLECTOR", "Identify 6 exact earbud, speaker or accessory models today.", 2, 6, 150) { it.models }
        )
    )

    fun tierName(tier: Int): String = when (tier) {
        0 -> "EASY"
        1 -> "MEDIUM"
        else -> "HARD"
    }

    private val _state = MutableStateFlow(DailyState())
    val state: StateFlow<DailyState> get() = _state

    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private var loaded = false

    private var day = 0L
    private var lastCheck = 0L
    private var streak = 0
    private var perfect = 0
    private var total = 0
    private var counted = false
    private var scanMs = 0L
    private val done = LinkedHashSet<String>()

    // What happened today. Each set holds plain keys (no string building on the per-advertisement path).
    private val devs = HashSet<String>()
    private val alertS = HashSet<String>()
    private val possS = HashSet<String>()
    private val watchS = HashSet<String>()
    private val kindsS = HashSet<String>()
    private val locS = HashSet<String>()
    private val nameS = HashSet<String>()
    private val identS = HashSet<String>()
    private val sensS = HashSet<String>()
    private val modelS = HashSet<String>()
    private val homeS = HashSet<String>()
    private val trackS = HashSet<String>()
    private val newLines = ArrayList<String>()
    private var fileDay = -1L

    private var qDay = -1L
    private var qList: List<QuestDef> = emptyList()

    // Read by Game for the lifetime achievements.
    val totalQuests: Int get() = total
    val perfectDays: Int get() = perfect
    val streakDays: Int get() = streak

    // ---- life cycle ------------------------------------------------------------------------

    fun init(context: Context) {
        synchronized(Game) {
            if (loaded) return
            loaded = true
            val app = context.applicationContext
            ctx = app
            val p = app.getSharedPreferences("signalops_daily", Context.MODE_PRIVATE)
            prefs = p
            day = p.getLong("day", 0L)
            // Older versions could lose the streak and kept the best one separately; nobody loses days now.
            streak = maxOf(p.getInt("streak", 0), p.getInt("best", 0))
            perfect = p.getInt("perfect", 0)
            total = p.getInt("total", 0)
            counted = p.getBoolean("counted", false)
            scanMs = p.getLong("scanMs", 0L)
            runCatching {
                val a = JSONArray(p.getString("done", "[]") ?: "[]")
                for (i in 0 until a.length()) done.add(a.getString(i))
            }
            runCatching {
                val f = File(app.filesDir, FILE)
                if (f.exists()) {
                    val lines = f.readLines()
                    if (lines.isNotEmpty() && lines[0] == "#$day") {
                        for (i in 1 until lines.size) loadLine(lines[i])
                        fileDay = day
                    }
                }
            }
            checkDay()
            publish()
        }
    }

    private fun loadLine(l: String) {
        if (l.length < 3 || l[1] != ':') return
        val v = l.substring(2)
        when (l[0]) {
            'd' -> devs.add(v)
            'A' -> alertS.add(v)
            'P' -> possS.add(v)
            'W' -> watchS.add(v)
            'k' -> kindsS.add(v)
            'l' -> locS.add(v)
            'n' -> nameS.add(v)
            'i' -> identS.add(v)
            's' -> sensS.add(v)
            'm' -> modelS.add(v)
            'h' -> homeS.add(v)
            't' -> trackS.add(v)
        }
    }

    fun reset() {
        synchronized(Game) {
            if (!loaded) return
            streak = 0; perfect = 0; total = 0
            prefs?.edit()?.clear()?.apply()
            ctx?.let { runCatching { File(it.filesDir, FILE).delete() } }
            day = 0L
            startDay(dayNow())
            publish()
        }
    }

    /** Checks whether the day has changed (the UI calls this now and then). */
    fun refresh() {
        synchronized(Game) {
            if (!loaded) return
            if (checkDay()) save()
            publish()
        }
    }

    /**
     * Called a few times a second by [Game.tickScan]. [deltaMs] is the scanning time since the last call
     * (0 when not scanning). Returns true when something changed that the game must re-publish.
     */
    fun tick(deltaMs: Long): Boolean {
        return synchronized(Game) {
            if (!loaded) return@synchronized false
            var changed = false
            val el = SystemClock.elapsedRealtime()
            if (el - lastCheck > 20_000L) {
                lastCheck = el
                if (checkDay()) {
                    save()
                    publish()
                    changed = true
                }
            }
            if (deltaMs > 0L) {
                val before = scanMs / 60_000L
                scanMs += deltaMs
                if (scanMs / 60_000L > before) {
                    onChange()
                    changed = true
                }
            }
            changed
        }
    }

    // ---- things that happen ----------------------------------------------------------------

    /**
     * A device was heard. [key] is the same fair-counting key the game uses (null when the device does not
     * count), [cat] its identified kind, [tier] its tier. Returns true when today's totals changed.
     */
    fun onDevice(key: String?, cat: String?, tier: Tier?, matched: Boolean, model: String? = null, devKey: String? = null): Boolean {
        return synchronized(Game) {
            if (!loaded) return@synchronized false
            var ch = false
            if (key != null) {
                if (devs.size < DEVICE_CAP && addTo('d', devs, key)) ch = true
                if (tier == Tier.ALERT) {
                    if (addTo('A', alertS, key)) ch = true
                } else if (tier == Tier.POSSIBLE) {
                    if (addTo('P', possS, key)) ch = true
                }
                if (matched && addTo('W', watchS, key)) ch = true
            }
            if (cat != null && addTo('k', kindsS, cat)) ch = true
            // Models count once per model name; smart-home and trackers once per device (any address kind).
            if (model != null && addTo('m', modelS, model.take(60))) ch = true
            val dk = key ?: devKey
            if (dk != null && cat == "smart_iot" && addTo('h', homeS, dk)) ch = true
            if (dk != null && cat == "trackers_valuables" && addTo('t', trackS, dk)) ch = true
            if (ch) onChange()
            ch
        }
    }

    fun onLocated(addr: String): Boolean = touch('l', locS, addr)

    fun onSensor(addr: String): Boolean = touch('s', sensS, addr)

    fun onIdentified(addr: String): Boolean = touch('i', identS, addr)

    fun onNamed(addr: String): Boolean = touch('n', nameS, addr)

    private fun touch(tag: Char, set: HashSet<String>, v: String): Boolean {
        return synchronized(Game) {
            if (!loaded) return@synchronized false
            if (!addTo(tag, set, v)) return@synchronized false
            onChange()
            true
        }
    }

    /** Writes what changed to disk. Called by [Game.save]. */
    fun save() {
        synchronized(Game) {
            val p = prefs ?: return
            p.edit()
                .putLong("day", day)
                .putInt("streak", streak)
                .putInt("perfect", perfect)
                .putInt("total", total)
                .putBoolean("counted", counted)
                .putLong("scanMs", scanMs)
                .putString("done", JSONArray(done).toString())
                .apply()
            val c = ctx ?: return
            runCatching {
                val f = File(c.filesDir, FILE)
                if (fileDay != day) {
                    // First save of the day (or the file belonged to another day): write everything once.
                    val sb = StringBuilder("#$day\n")
                    dump(sb, 'd', devs); dump(sb, 'A', alertS); dump(sb, 'P', possS); dump(sb, 'W', watchS)
                    dump(sb, 'k', kindsS); dump(sb, 'l', locS); dump(sb, 'n', nameS); dump(sb, 'i', identS)
                    dump(sb, 's', sensS); dump(sb, 'm', modelS); dump(sb, 'h', homeS); dump(sb, 't', trackS)
                    f.writeText(sb.toString())
                    fileDay = day
                } else if (newLines.isNotEmpty()) {
                    f.appendText(newLines.joinToString("\n", postfix = "\n"))
                }
            }
            newLines.clear()
        }
    }

    private fun dump(sb: StringBuilder, tag: Char, set: Set<String>) {
        for (v in set) sb.append(tag).append(':').append(v.replace('\n', ' ')).append('\n')
    }

    // ---- internals -------------------------------------------------------------------------

    private fun addTo(tag: Char, set: HashSet<String>, v: String): Boolean {
        if (!set.add(v)) return false
        newLines.add("$tag:${v.replace('\n', ' ')}")
        return true
    }

    private fun onChange() {
        evaluate()
        publish()
    }

    internal fun dayNow(): Long = LocalDateTime.now().minusHours(ROLLOVER_HOUR).toLocalDate().toEpochDay()

    internal fun nextResetMs(): Long {
        val next = LocalDateTime.now().minusHours(ROLLOVER_HOUR).toLocalDate()
            .plusDays(1).atStartOfDay().plusHours(ROLLOVER_HOUR)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Starts a new day if the clock says so. Returns true when it did. The streak is never touched here. */
    private fun checkDay(): Boolean {
        val now = dayNow()
        if (day != 0L && now <= day) return false
        startDay(now)
        return true
    }

    private fun isMilestone(d: Int): Boolean = d in MILESTONES || (d > 100 && d % 100 == 0)

    private fun nextMilestone(d: Int): Int = MILESTONES.firstOrNull { it > d } ?: ((d / 100) + 1) * 100

    private fun prevMilestone(d: Int): Int = if (d >= 100) (d / 100) * 100 else (MILESTONES.lastOrNull { it <= d } ?: 0)

    private fun startDay(now: Long) {
        day = now
        done.clear()
        devs.clear(); alertS.clear(); possS.clear(); watchS.clear(); kindsS.clear()
        locS.clear(); nameS.clear(); identS.clear(); sensS.clear()
        modelS.clear(); homeS.clear(); trackS.clear()
        newLines.clear()
        counted = false
        scanMs = 0L
        fileDay = -1L
        ctx?.let { runCatching { File(it.filesDir, FILE).delete() } }
        save()
    }

    private fun stats() = DailyStats(
        devs.size, kindsS.size, possS.size, alertS.size, watchS.size, locS.size,
        nameS.size, identS.size, sensS.size, (scanMs / 60_000L).toInt(),
        modelS.size, homeS.size, trackS.size
    )

    private fun pick(d: Long, tier: Int): QuestDef {
        val pool = POOL[tier]
        fun idx(x: Long) = Random(x * 7919L + tier * 104729L + 17L).nextInt(pool.size)
        var i = idx(d)
        // Never the same quest two days in a row.
        if (pool.size > 1 && i == idx(d - 1L)) i = (i + 1) % pool.size
        return pool[i]
    }

    private fun quests(): List<QuestDef> {
        if (qDay != day) {
            qList = List(QUESTS_PER_DAY) { pick(day, it) }
            qDay = day
        }
        return qList
    }

    private fun bonusPct(): Int = (streak * 2).coerceAtMost(MAX_BONUS_PCT)

    private fun scaled(xp: Int): Int = xp * (100 + bonusPct()) / 100

    private fun evaluate() {
        val s = stats()
        for (qd in quests()) {
            if (qd.id in done || qd.progress(s) < qd.goal) continue
            complete(qd)
        }
    }

    private fun complete(qd: QuestDef) {
        done.add(qd.id)
        total++
        val first = !counted
        if (first) {
            counted = true
            streak++
        }
        val pct = bonusPct()
        val reward = scaled(qd.xp)
        Game.push(GameEvent.QuestDone(Game.nextSeq(), qd.name, qd.desc, reward, pct, done.size, QUESTS_PER_DAY))
        if (first) {
            Game.push(GameEvent.StreakUp(Game.nextSeq(), streak, isMilestone(streak), pct))
        }
        Game.addXp(reward, "DAILY QUEST")
        if (done.size >= QUESTS_PER_DAY) {
            perfect++
            val bonus = scaled(PERFECT_XP)
            Game.push(GameEvent.PerfectDay(Game.nextSeq(), bonus))
            Game.addXp(bonus, "PERFECT DAY")
        }
    }

    private fun publish() {
        val s = stats()
        val list = quests().map { qd ->
            QuestState(qd, qd.progress(s).coerceAtMost(qd.goal), qd.id in done, scaled(qd.xp))
        }
        val next = nextMilestone(streak)
        val prev = prevMilestone(streak)
        _state.value = DailyState(
            day = day,
            quests = list,
            streak = streak,
            countedToday = counted,
            perfect = done.size >= QUESTS_PER_DAY,
            bonusPct = bonusPct(),
            perfectBonus = scaled(PERFECT_XP),
            nextMilestone = next,
            prevMilestone = prev,
            resetAtMs = nextResetMs()
        )
    }
}
