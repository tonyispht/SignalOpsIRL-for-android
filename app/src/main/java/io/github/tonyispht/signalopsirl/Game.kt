package io.github.tonyispht.signalopsirl

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * The leveling game: experience for finding devices, achievements, and titles you can equip.
 * Progress is saved on the phone (its own preferences file plus a list of already-counted devices).
 * Everything here is called from the main thread (the scan service and the UI), and is cheap when
 * nothing new happened, because it runs for every advertisement.
 */

/** Totals the achievements are measured against. */
data class GameStats(
    val devices: Int = 0,
    val categories: Int = 0,
    val possible: Int = 0,
    val alerts: Int = 0,
    val watch: Int = 0,
    val locates: Int = 0,
    val named: Int = 0,
    val idents: Int = 0,
    val sensors: Int = 0,
    val scanMin: Int = 0,
    val quests: Int = 0,
    val perfect: Int = 0,
    val streakBest: Int = 0,
    val logins: Int = 0,
    val dexTypes: Int = 0,
    val dexTypePct: Int = 0,
    val dexKinds: Int = 0,
    val dexMakers: Int = 0,
    val dexMastered: Int = 0
)

class Achievement(
    val id: String,
    val name: String,
    val desc: String,
    val xp: Int,
    /** Title unlocked together with this achievement, or null. */
    val title: String? = null,
    val goal: Int = 1,
    /** Current value toward [goal]. */
    val progress: (GameStats) -> Int
)

/** A title you can equip. Unlocked by reaching [level] (when above 0) or by the achievement that names it. */
class GameTitle(val id: String, val name: String, val level: Int = 0)

data class GameState(
    val xp: Long = 0,
    val level: Int = 1,
    /** XP earned inside the current level, and the size of the current level (for the bar). */
    val xpIntoLevel: Long = 0,
    val xpForLevel: Long = 100,
    val equipped: String = "rookie",
    val stats: GameStats = GameStats(),
    val achievements: Map<String, Long> = emptyMap(),
    val titles: Set<String> = setOf("rookie"),
    /** Unlocked since the profile screen was last opened (shown as NEW). */
    val newAchievements: Set<String> = emptySet(),
    val newTitles: Set<String> = emptySet()
)

/** Something worth a celebration on screen. */
sealed class GameEvent {
    abstract val id: Long

    /** Set once the sound and buzz for this event were given, so they are never played twice. */
    var played = false

    class LevelUp(override val id: Long, val level: Int, val rank: String) : GameEvent()
    class AchievementUnlocked(override val id: Long, val achievement: Achievement) : GameEvent()
    class TitleUnlocked(override val id: Long, val title: GameTitle) : GameEvent()
    class QuestDone(
        override val id: Long, val name: String, val desc: String, val xp: Int,
        val bonusPct: Int, val index: Int, val total: Int
    ) : GameEvent()
    class StreakUp(
        override val id: Long, val days: Int, val milestone: Boolean, val bonusPct: Int
    ) : GameEvent()
    class PerfectDay(override val id: Long, val bonusXp: Int) : GameEvent()
    /** The login calendar reward for [day] (1..28 of the cycle) was collected. [big] days are the milestones. */
    class LoginClaimed(override val id: Long, val day: Int, val xp: Int, val big: Boolean) : GameEvent()
    /** A new Signal Dex entry. [tab] is "TYPE" or "KIND"; [total] is 0 when the page has no fixed size. */
    class DexEntryFound(
        override val id: Long, val name: String, val icon: String, val tab: String,
        val found: Int, val total: Int, val xp: Int
    ) : GameEvent()
}

/** A recent XP gain, for the small "+10 XP" readout next to the bar. */
data class XpGain(val amount: Int, val why: String, val seq: Long)

object Game {
    const val MAX_LEVEL = 100
    private const val SEEN_CAP = 20_000
    private const val FILE_SEEN = "game_seen.txt"

    val ACHIEVEMENTS: List<Achievement> = listOf(
        Achievement("first_contact", "FIRST CONTACT", "Find your first device.", 20) { it.devices },
        Achievement("dev10", "WARMING UP", "Find 10 different devices.", 40, goal = 10) { it.devices },
        Achievement("dev50", "SIGNAL COLLECTOR", "Find 50 different devices.", 100, goal = 50) { it.devices },
        Achievement("dev250", "SPECTRUM HUNTER", "Find 250 different devices.", 250, "collector", 250) { it.devices },
        Achievement("dev1000", "RADIO HOARDER", "Find 1,000 different devices.", 600, "hoarder", 1000) { it.devices },
        Achievement("cat5", "TAXONOMIST", "Identify 5 kinds of device.", 60, goal = 5) { it.categories },
        Achievement("cat15", "FIELD BIOLOGIST", "Identify 15 kinds of device.", 200, "biologist", 15) { it.categories },
        Achievement("cat30", "KNOW-IT-ALL", "Identify 30 kinds of device.", 500, goal = 30) { it.categories },
        Achievement("watch1", "WATCHLIST HIT", "Match a device on your target list.", 50) { it.watch },
        Achievement("locate1", "FOX HUNTER", "Track a device until it is stronger than -50 dBm.", 100, "foxhunter") { it.locates },
        Achievement("locate10", "BLOODHOUND", "Locate 10 different devices.", 300, "bloodhound", 10) { it.locates },
        Achievement("name1", "NAMING RIGHTS", "Give a device your own name.", 30) { it.named },
        Achievement("ident1", "INTERROGATOR", "Read a device's name over GATT.", 50) { it.idents },
        Achievement("sensor1", "SENSOR SNIFFER", "Decode a sensor broadcast.", 40) { it.sensors },
        Achievement("sensor5", "WEATHERMAN", "Decode 5 different sensors.", 150, "weatherman", 5) { it.sensors },
        Achievement("scan60", "STAKEOUT", "Scan for 60 minutes in total.", 80, goal = 60) { it.scanMin },
        Achievement("scan600", "NIGHT WATCH", "Scan for 10 hours in total.", 400, "nightwatch", 600) { it.scanMin },
        // daily play
        Achievement("quest1", "DAILY GRIND", "Complete a daily quest.", 30) { it.quests },
        Achievement("quest10", "ROUTINE", "Complete 10 daily quests.", 100, goal = 10) { it.quests },
        Achievement("quest50", "CREATURE OF HABIT", "Complete 50 daily quests.", 300, "grinder", 50) { it.quests },
        Achievement("quest200", "QUEST MASTER", "Complete 200 daily quests.", 800, "questmaster", 200) { it.quests },
        Achievement("perfect1", "CLEAN SWEEP", "Complete all 3 daily quests in one day.", 100) { it.perfect },
        Achievement("perfect7", "PERFECTIONIST", "Get 7 perfect days.", 500, "perfectionist", 7) { it.perfect },
        Achievement("streak3", "ON A ROLL", "Finish a daily quest on 3 different days.", 60, goal = 3) { it.streakBest },
        Achievement("streak7", "WEEK WARRIOR", "Finish a daily quest on 7 different days.", 200, "weekwarrior", 7) { it.streakBest },
        Achievement("streak14", "FORTNIGHT", "Finish a daily quest on 14 different days.", 400, goal = 14) { it.streakBest },
        Achievement("streak30", "IRON HABIT", "Finish a daily quest on 30 different days.", 1000, "ironhabit", 30) { it.streakBest },
        Achievement("streak100", "CENTURION", "Finish a daily quest on 100 different days.", 3000, "centurion", 100) { it.streakBest },
        // login calendar
        Achievement("login7", "SHOWING UP", "Collect the login reward on 7 different days.", 100, goal = 7) { it.logins },
        Achievement("login28", "REGULAR", "Collect the login reward on 28 different days.", 400, "regular", 28) { it.logins },
        Achievement("login100", "LOYAL OPERATOR", "Collect the login reward on 100 different days.", 1500, "loyal", 100) { it.logins },
        // signal dex
        Achievement("dex_t10", "FIELD GUIDE", "Fill 10 TYPES pages of the Signal Dex.", 60, goal = 10) { it.dexTypes },
        Achievement("dex_t25", "CATALOGUER", "Fill 25 TYPES pages of the Signal Dex.", 200, "cataloguer", 25) { it.dexTypes },
        Achievement("dex_p75", "NEARLY COMPLETE", "Fill 75% of the TYPES pages.", 500, goal = 75) { it.dexTypePct },
        Achievement("dex_p100", "DEX COMPLETE", "Fill every TYPES page of the Signal Dex.", 2000, "dexmaster", 100) { it.dexTypePct },
        Achievement("dex_k10", "SPECIES SPOTTER", "Find 10 different KINDS.", 50, goal = 10) { it.dexKinds },
        Achievement("dex_k50", "ZOOLOGIST", "Find 50 different KINDS.", 250, "zoologist", 50) { it.dexKinds },
        Achievement("dex_k150", "ENCYCLOPEDIA", "Find 150 different KINDS.", 800, goal = 150) { it.dexKinds },
        Achievement("dex_m10", "BRAND AWARE", "Find devices from 10 different makers.", 50, goal = 10) { it.dexMakers },
        Achievement("dex_m50", "SUPPLY CHAIN", "Find devices from 50 different makers.", 250, goal = 50) { it.dexMakers },
        Achievement("dex_m100", "VENDOR ENCYCLOPEDIA", "Find devices from 100 different makers.", 600, goal = 100) { it.dexMakers },
        Achievement("dex_s1", "OLD ACQUAINTANCE", "Master a Dex entry (see it on 7 different days).", 60) { it.dexMastered },
        Achievement("dex_s10", "OLD FRIENDS", "Master 10 Dex entries.", 300, goal = 10) { it.dexMastered }
    )

    val TITLES: List<GameTitle> = listOf(
        GameTitle("rookie", "ROOKIE", level = 1),
        GameTitle("scout", "SCOUT", level = 5),
        GameTitle("tracker", "TRACKER", level = 10),
        GameTitle("operative", "OPERATIVE", level = 15),
        GameTitle("spymaster", "SPYMASTER", level = 25),
        GameTitle("legend", "LEGEND", level = 40),
        GameTitle("foxhunter", "FOX HUNTER"),
        GameTitle("bloodhound", "BLOODHOUND"),
        GameTitle("collector", "COLLECTOR"),
        GameTitle("hoarder", "RADIO HOARDER"),
        GameTitle("biologist", "FIELD BIOLOGIST"),
        GameTitle("weatherman", "WEATHERMAN"),
        GameTitle("nightwatch", "NIGHT WATCH"),
        GameTitle("grinder", "DAILY GRINDER"),
        GameTitle("questmaster", "QUEST MASTER"),
        GameTitle("perfectionist", "PERFECTIONIST"),
        GameTitle("weekwarrior", "WEEK WARRIOR"),
        GameTitle("ironhabit", "IRON HABIT"),
        GameTitle("centurion", "CENTURION"),
        GameTitle("regular", "REGULAR"),
        GameTitle("loyal", "LOYAL OPERATOR"),
        GameTitle("cataloguer", "CATALOGUER"),
        GameTitle("dexmaster", "DEX MASTER"),
        GameTitle("zoologist", "ZOOLOGIST")
    )

    // ---- levels ------------------------------------------------------------------------------

    /** Total XP needed to reach [level]: 0, 100, 300, 600, 1000, 1500, ... */
    fun xpForLevel(level: Int): Long = 50L * level * (level - 1)

    fun levelOf(xp: Long): Int {
        var l = ((1.0 + sqrt(1.0 + xp / 12.5)) / 2.0).toInt().coerceIn(1, MAX_LEVEL)
        while (l < MAX_LEVEL && xpForLevel(l + 1) <= xp) l++
        while (l > 1 && xpForLevel(l) > xp) l--
        return l
    }

    fun rankOf(level: Int): String =
        TITLES.filter { it.level in 1..level }.maxByOrNull { it.level }?.name ?: "ROOKIE"

    fun titleName(id: String): String = TITLES.firstOrNull { it.id == id }?.name ?: "ROOKIE"

    fun howToUnlock(t: GameTitle): String =
        if (t.level > 1) "REACH LEVEL ${t.level}"
        else if (t.level == 1) "STARTING TITLE"
        else ACHIEVEMENTS.firstOrNull { it.title == t.id }?.let { "ACHIEVEMENT: ${it.name}" } ?: "SECRET"

    // ---- state -------------------------------------------------------------------------------

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> get() = _state

    private val _pending = MutableStateFlow<List<GameEvent>>(emptyList())
    /** Celebrations waiting to be shown, oldest first. The UI plays the first one and calls [consume]. */
    val pending: StateFlow<List<GameEvent>> get() = _pending

    private val _gain = MutableStateFlow<XpGain?>(null)
    val gain: StateFlow<XpGain?> get() = _gain

    private var ctx: Context? = null
    private var store: Store? = null
    private var prefs: SharedPreferences? = null
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L
    private var seq = 0L

    private var xp = 0L
    private var equipped = "rookie"
    private val ach = LinkedHashMap<String, Long>()
    private val titles = LinkedHashSet<String>()
    private val cats = HashSet<String>()
    private val named = HashSet<String>()
    private val idents = HashSet<String>()
    private val sensors = HashSet<String>()
    private val seen = HashSet<String>()
    private val newSeen = ArrayList<String>()
    private val newAch = HashSet<String>()
    private val newTitles = HashSet<String>()
    private val located = HashSet<String>()      // this run only
    private var devices = 0
    private var possible = 0
    private var alerts = 0
    private var watchHits = 0
    private var locates = 0
    private var scanMs = 0L
    private var scanMinPaid = 0L
    private var lastTick = 0L
    private var gainAt = 0L
    private var gainSum = 0
    private var gainSeq = 0L
    private var soundAt = 0L

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        ctx = app
        store = Store(app)
        val p = app.getSharedPreferences("signalops_game", Context.MODE_PRIVATE)
        prefs = p
        xp = p.getLong("xp", 0L)
        equipped = p.getString("equipped", "rookie") ?: "rookie"
        devices = p.getInt("devices", 0)
        possible = p.getInt("possible", 0)
        alerts = p.getInt("alerts", 0)
        watchHits = p.getInt("watch", 0)
        locates = p.getInt("locates", 0)
        scanMs = p.getLong("scanMs", 0L)
        scanMinPaid = scanMs / 60_000L
        runCatching {
            val o = JSONObject(p.getString("ach", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                ach[k] = o.getLong(k)
            }
        }
        loadSet(p, "titles", titles)
        loadSet(p, "cats", cats)
        loadSet(p, "named", named)
        loadSet(p, "idents", idents)
        loadSet(p, "sensors", sensors)
        loadSet(p, "newAch", newAch)
        loadSet(p, "newTitles", newTitles)
        runCatching {
            val f = File(app.filesDir, FILE_SEEN)
            if (f.exists()) f.forEachLine { if (it.isNotEmpty()) seen.add(it) }
        }
        // Keep the unlocked titles consistent with level and achievements.
        titles.add("rookie")
        val lvl = levelOf(xp)
        for (t in TITLES) if (t.level in 1..lvl) titles.add(t.id)
        for (a in ACHIEVEMENTS) if (a.title != null && ach.containsKey(a.id)) titles.add(a.title)
        if (equipped !in titles) equipped = "rookie"
        Daily.init(app)
        LoginCalendar.init(app)
        Dex.init(app)
        publish()
    }

    private fun loadSet(p: SharedPreferences, key: String, into: MutableSet<String>) {
        runCatching {
            val a = JSONArray(p.getString(key, "[]") ?: "[]")
            for (i in 0 until a.length()) into.add(a.getString(i))
        }
    }

    // ---- things that happen ------------------------------------------------------------------

    /**
     * A device was heard. Public addresses count once per address; devices with a random (rotating)
     * address count once per name, and unnamed random ones do not count at all, so changing addresses
     * cannot be farmed for XP. A kind of device (category) counts once the first time it is identified.
     */
    @Synchronized
    fun onDevice(addr: String, name: String?, cls: Classification?, matched: Boolean, local: Boolean) {
        if (!loaded) return
        val key: String? = when {
            !local -> "a:$addr"
            !name.isNullOrBlank() -> "n:" + name.trim().lowercase().take(40).replace('\n', ' ')
            else -> null
        }
        var changed = false
        if (key != null && seen.size < SEEN_CAP) {
            if (seen.add(key)) {
                newSeen.add(key)
                devices++
                changed = true
                addXp(10, "NEW DEVICE")
            }
            if (matched && seen.add("W:$key")) {
                newSeen.add("W:$key")
                watchHits++
                changed = true
                addXp(50, "TARGET HIT")
            }
        }
        val cat = cls?.category
        if (cat != null && cats.add(cat)) {
            changed = true
            addXp(25, "NEW CATEGORY")
        }
        // The daily quests count what is new today, even if the device was found on an earlier day.
        val model = cls?.label?.takeIf { isModelReason(cls.reason) }
        if (Daily.onDevice(key, cat, cls?.tier, matched, model, "x:$addr")) changed = true
        if (changed) finishChange()
    }

    /** A tracked device got strong enough to count as found. */
    @Synchronized
    fun onLocated(addr: String) {
        if (!loaded) return
        var changed = Daily.onLocated(addr)
        if (located.add(addr)) {
            locates++
            addXp(30, "DEVICE LOCATED")
            changed = true
        }
        if (changed) finishChange()
    }

    @Synchronized
    fun onSensor(addr: String) {
        if (!loaded) return
        var changed = Daily.onSensor(addr)
        if (sensors.add(addr)) {
            addXp(15, "SENSOR DECODED")
            changed = true
        }
        if (changed) finishChange()
    }

    @Synchronized
    fun onIdentified(addr: String) {
        if (!loaded) return
        var changed = Daily.onIdentified(addr)
        if (idents.add(addr)) {
            addXp(15, "NAME READ")
            changed = true
        }
        if (changed) finishChange()
    }

    @Synchronized
    fun onNamed(addr: String) {
        if (!loaded) return
        var changed = Daily.onNamed(addr)
        if (named.add(addr)) {
            addXp(10, "DEVICE NAMED")
            changed = true
        }
        if (changed) finishChange()
    }

    /** Called a few times a second by the scan service; adds up scanning time (1 XP per full minute). */
    @Synchronized
    fun tickScan(scanning: Boolean, now: Long) {
        if (!loaded) return
        val last = lastTick
        lastTick = now
        val delta = if (scanning && last != 0L) (now - last).coerceIn(0L, 2_000L) else 0L
        var changed = Daily.tick(delta)
        if (delta > 0L) {
            scanMs += delta
            dirty = true
            val min = scanMs / 60_000L
            if (min > scanMinPaid) {
                scanMinPaid = min
                addXp(1, "SCAN TIME")
                changed = true
            }
        }
        if (changed) finishChange()
    }

    @Synchronized
    fun equip(id: String) {
        if (!loaded || id !in titles || id == equipped) return
        equipped = id
        dirty = true
        publish()
    }

    /** The profile screen was looked at, so unlocks no longer count as NEW. */
    @Synchronized
    fun markViewed() {
        if (!loaded || (newAch.isEmpty() && newTitles.isEmpty())) return
        newAch.clear()
        newTitles.clear()
        dirty = true
        publish()
    }

    @Synchronized
    fun consume(id: Long) {
        _pending.value = _pending.value.filterNot { it.id == id }
    }

    /** Drops every waiting celebration (the SKIP ALL button). */
    @Synchronized
    fun consumeAll() {
        _pending.value = emptyList()
    }

    @Synchronized
    internal fun nextSeq(): Long = ++seq

    /** Sound and buzz for a celebration. The UI calls this when the animation starts, so they line up. */
    @Synchronized
    fun playFor(e: GameEvent) {
        if (e.played) return
        e.played = true
        val st = store ?: return
        if (!st.gameFxOn) return
        if (st.soundOn) {
            when (e) {
                is GameEvent.LevelUp -> AlertEffects.playLevelUp()
                is GameEvent.AchievementUnlocked -> AlertEffects.playFanfare()
                is GameEvent.PerfectDay -> AlertEffects.playFanfare()
                is GameEvent.StreakUp -> if (e.milestone) AlertEffects.playFanfare() else AlertEffects.playQuest()
                is GameEvent.QuestDone -> AlertEffects.playQuest()
                is GameEvent.TitleUnlocked -> AlertEffects.playChime()
                is GameEvent.LoginClaimed -> if (e.big) AlertEffects.playFanfare() else AlertEffects.playQuest()
                is GameEvent.DexEntryFound -> AlertEffects.playChime()
            }
        }
        val big = e is GameEvent.LevelUp || e is GameEvent.AchievementUnlocked ||
            e is GameEvent.PerfectDay || (e is GameEvent.StreakUp && e.milestone) ||
            (e is GameEvent.LoginClaimed && e.big)
        if (big && st.vibrateOn) ctx?.let { AlertEffects.vibrateShort(it) }
    }

    /** Queues a sample achievement, level-up and quest card so the effects can be checked (Settings > TEST GAME FX). */
    @Synchronized
    fun testEffects() {
        if (!loaded) return
        val next = levelOf(xp) + 1
        push(GameEvent.AchievementUnlocked(++seq, ACHIEVEMENTS[0]))
        push(GameEvent.LevelUp(++seq, next, rankOf(next)))
        push(GameEvent.QuestDone(++seq, "TEST QUEST", "This is a test card.", 40, 0, 1, 3))
    }

    @Synchronized
    fun reset() {
        if (!loaded) return
        xp = 0; equipped = "rookie"; devices = 0; possible = 0; alerts = 0; watchHits = 0; locates = 0
        scanMs = 0; scanMinPaid = 0
        ach.clear(); titles.clear(); cats.clear(); named.clear(); idents.clear(); sensors.clear()
        seen.clear(); newSeen.clear(); newAch.clear(); newTitles.clear(); located.clear()
        titles.add("rookie")
        prefs?.edit()?.clear()?.apply()
        ctx?.let { File(it.filesDir, FILE_SEEN).delete() }
        _pending.value = emptyList()
        _gain.value = null
        dirty = false
        Daily.reset()
        LoginCalendar.reset()
        Dex.reset()
        publish()
    }

    /** Writes progress to disk (at most every few seconds unless [force]). */
    @Synchronized
    fun flush(force: Boolean = false) {
        if (!loaded || !dirty) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSave < 3_000L) return
        lastSave = now
        dirty = false
        save()
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun finishChange() {
        dirty = true
        checkAchievements()
        publish()
    }

    /** True when the game already counted this kind of device (read without locking: the Dex calls it while holding its own lock). */
    fun hasCategory(key: String): Boolean = key in cats

    /** Something outside the game changed what must be saved (the Dex). */
    @Synchronized
    internal fun touch() {
        if (loaded) dirty = true
    }

    /** XP from outside the scan path (the login calendar): adds it, checks achievements, publishes and saves at once. */
    @Synchronized
    internal fun grantXp(n: Int, why: String) {
        if (!loaded) return
        addXp(n, why)
        finishChange()
        flush(force = true)
    }

    internal fun addXp(n: Int, why: String) {
        if (n <= 0) return
        val before = levelOf(xp)
        xp += n
        val after = levelOf(xp)
        val now = SystemClock.elapsedRealtime()
        gainSum = if (now - gainAt < 1_400L) gainSum + n else n
        gainAt = now
        _gain.value = XpGain(gainSum, why, ++gainSeq)
        if (after > before) {
            push(GameEvent.LevelUp(++seq, after, rankOf(after)))
            for (t in TITLES) if (t.level in (before + 1)..after) unlockTitle(t.id)
        }
    }

    private fun unlockTitle(id: String) {
        if (!titles.add(id)) return
        newTitles.add(id)
        val t = TITLES.firstOrNull { it.id == id } ?: return
        push(GameEvent.TitleUnlocked(++seq, t))
    }

    private fun checkAchievements() {
        val s = stats()
        for (a in ACHIEVEMENTS) {
            if (ach.containsKey(a.id) || a.progress(s) < a.goal) continue
            ach[a.id] = System.currentTimeMillis()
            newAch.add(a.id)
            push(GameEvent.AchievementUnlocked(++seq, a))
            a.title?.let { unlockTitle(it) }
            addXp(a.xp, a.name)
        }
    }

    private fun stats() = GameStats(
        devices, cats.size, possible, alerts, watchHits, locates,
        named.size, idents.size, sensors.size, (scanMs / 60_000L).toInt(),
        Daily.totalQuests, Daily.perfectDays, Daily.streakDays, LoginCalendar.totalDays,
        Dex.typeCount, Dex.typePct, Dex.kindCount, Dex.makerCount, Dex.masteredCount
    )

    private fun publish() {
        val lvl = levelOf(xp)
        val base = xpForLevel(lvl)
        val atMax = lvl >= MAX_LEVEL
        _state.value = GameState(
            xp, lvl,
            if (atMax) 1L else xp - base,
            if (atMax) 1L else xpForLevel(lvl + 1) - base,
            equipped, stats(), LinkedHashMap(ach), LinkedHashSet(titles),
            HashSet(newAch), HashSet(newTitles)
        )
    }

    internal fun push(e: GameEvent) {
        val st = store
        val effects = st == null || st.gameFxOn
        if (!effects) return
        val list = _pending.value.toMutableList()
        val last = list.lastIndex
        // Several level-ups in a row (a big burst of XP) become one celebration for the final level.
        if (e is GameEvent.LevelUp && last > 0 && list[last] is GameEvent.LevelUp) list[last] = e else list.add(e)
        while (list.size > 12) list.removeAt(0)
        _pending.value = list
        // Nobody is looking at the screen: give the sound and buzz right away instead of when it is shown.
        if (!ScanBus.appVisible) playFor(e)
    }

    private fun save() {
        Daily.save()
        Dex.save()
        val p = prefs ?: return
        p.edit()
            .putLong("xp", xp)
            .putString("equipped", equipped)
            .putInt("devices", devices)
            .putInt("possible", possible)
            .putInt("alerts", alerts)
            .putInt("watch", watchHits)
            .putInt("locates", locates)
            .putLong("scanMs", scanMs)
            .putString("ach", JSONObject(ach as Map<*, *>).toString())
            .putString("titles", JSONArray(titles).toString())
            .putString("cats", JSONArray(cats).toString())
            .putString("named", JSONArray(named).toString())
            .putString("idents", JSONArray(idents).toString())
            .putString("sensors", JSONArray(sensors).toString())
            .putString("newAch", JSONArray(newAch).toString())
            .putString("newTitles", JSONArray(newTitles).toString())
            .apply()
        if (newSeen.isNotEmpty()) {
            val lines = newSeen.joinToString("\n", postfix = "\n")
            newSeen.clear()
            ctx?.let { runCatching { File(it.filesDir, FILE_SEEN).appendText(lines) } }
        }
    }
}

/** True when a classification names an exact product model (not just a kind of device). */
fun isModelReason(reason: String): Boolean =
    reason.startsWith("Fast Pair model") || reason.startsWith("Apple model") ||
        reason.startsWith("Samsung model") || reason.startsWith("Microsoft Swift Pair name")
