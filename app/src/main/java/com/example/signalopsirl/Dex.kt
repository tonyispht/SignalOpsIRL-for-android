package com.example.signalopsirl

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/** The three pages of the Signal Dex. */
enum class DexTab(val label: String) {
    /** The kinds of device the classifier knows (a fixed list, so it can be completed). */
    TYPES("TYPES"),
    /** Specific things: an AirPods model, a Tile tracker, a named product. Open-ended. */
    KINDS("KINDS"),
    /** Who makes them: the owner of the MAC prefix or the Bluetooth company ID. Open-ended. */
    MAKERS("MAKERS")
}

/** One page entry. [days] counts the different days it was seen; that sets the mastery [stars]. */
data class DexEntry(
    val key: String,
    val tab: DexTab,
    val name: String,
    val icon: String,
    val first: Long,
    val last: Long,
    val days: Int,
    val lastDay: Int,
    /** Strongest signal ever heard from it (dBm), i.e. the closest you got. */
    val best: Int,
    /** Found since the Dex was last opened (shown as NEW). */
    val fresh: Boolean = false
) {
    val stars: Int get() = if (days >= 7) 3 else if (days >= 3) 2 else 1
}

data class DexState(
    val entries: List<DexEntry> = emptyList(),
    val categories: List<CategoryInfo> = emptyList(),
    /** Changes with every update, so the UI always sees a new value. */
    val gen: Int = 0
) {
    val newCount: Int get() = entries.count { it.fresh }
}

/**
 * The Signal Dex: a collection book of every kind of device, specific product and maker the scanner has found.
 * Entries are by type, product and maker, never by address, so rotating addresses cannot inflate it.
 * Called for every advertisement, so it only looks at a device again every few seconds.
 */
object Dex {
    private const val FILE = "dex.json"
    private const val EVAL_MS = 4_000L
    private const val CARD_GAP_MS = 15_000L
    private const val DAY_MS = 86_400_000L

    private val lock = Any()
    private var ctx: Context? = null
    private var loaded = false
    private var dirty = false
    private var lastCardAt = 0L
    private var gen = 0
    private val entries = LinkedHashMap<String, DexEntry>()
    private val lastEval = HashMap<String, Long>()

    /** Read by the achievements (see [GameStats]). */
    @Volatile var typeCount = 0
        private set
    @Volatile var typePct = 0
        private set
    @Volatile var kindCount = 0
        private set
    @Volatile var makerCount = 0
        private set
    @Volatile var masteredCount = 0
        private set

    private val _state = MutableStateFlow(DexState())
    val state: StateFlow<DexState> get() = _state

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val app = context.applicationContext
            ctx = app
            try {
                val f = File(app.filesDir, FILE)
                if (f.exists()) {
                    val a = JSONArray(f.readText())
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val tab = DexTab.values().firstOrNull { it.name == o.optString("t") } ?: continue
                        val key = o.optString("k")
                        if (key.isEmpty()) continue
                        entries[key] = DexEntry(
                            key, tab, o.optString("n"), o.optString("i"), o.optLong("f"), o.optLong("l"),
                            o.optInt("d", 1), o.optInt("ld"), o.optInt("b", -100), o.optBoolean("w", false)
                        )
                    }
                }
            } catch (_: Exception) {
                // a damaged file just means starting a new book
            }
            refresh()
        }
    }

    private fun today(now: Long): Int = ((now + TimeZone.getDefault().getOffset(now)) / DAY_MS).toInt()

    /** Recounts and publishes (also called when the classifier config finishes loading, so the totals appear). */
    fun refresh() {
        synchronized(lock) {
            if (!loaded) return
            val cats = Classifier.categories()
            val catKeys = HashSet<String>(cats.size * 2)
            cats.forEach { catKeys.add("T:" + it.key) }
            var t = 0
            var k = 0
            var m = 0
            var mastered = 0
            for (e in entries.values) {
                when (e.tab) {
                    DexTab.TYPES -> if (e.key in catKeys) t++
                    DexTab.KINDS -> k++
                    DexTab.MAKERS -> m++
                }
                if (e.stars >= 3) mastered++
            }
            typeCount = t
            typePct = if (cats.isEmpty()) 0 else t * 100 / cats.size
            kindCount = k
            makerCount = m
            masteredCount = mastered
            _state.value = DexState(entries.values.sortedByDescending { it.first }, cats, ++gen)
        }
    }

    /**
     * Looks at one device: its type, its specific kind (a label or an Apple model) and its maker.
     * Call this before [Game.onDevice], so a type the game already counted earlier is not announced again.
     */
    fun onSeen(d: SeenDevice) {
        if (!loaded) return
        val now = System.currentTimeMillis()
        val after = ArrayList<() -> Unit>(3)
        var changed = false
        synchronized(lock) {
            val prevT = lastEval[d.address]
            if (prevT != null && now - prevT < EVAL_MS) return
            if (lastEval.size > 4000) lastEval.clear()
            lastEval[d.address] = now

            val cls = d.cls
            val cat = cls?.category
            if (cls != null && cat != null) {
                changed = touch("T:$cat", DexTab.TYPES, cls.title, cls.icon, d.rssi, now, after, cat) or changed
            }
            val kind = (d.appleModel ?: cls?.label)?.trim()
            if (!kind.isNullOrEmpty()) {
                val shown = kind.take(48)
                changed = touch("K:" + shown.uppercase(), DexTab.KINDS, shown, cls?.icon ?: "", d.rssi, now, after, null) or changed
            }
            val makers = ArrayList<String>(2)
            if (!Vendors.isLocal(d.address)) Vendors.lookup(d.address)?.let { makers.add(it) }
            for (id in d.companyIds) Classifier.companyName(id)?.let { makers.add(it) }
            for (mk in makers) {
                val shown = mk.trim().take(40)
                if (shown.isEmpty()) continue
                changed = touch("M:" + shown.uppercase(), DexTab.MAKERS, shown, "", d.rssi, now, after, null) or changed
            }
            if (changed) {
                dirty = true
                refresh()
            }
        }
        if (changed) {
            Game.touch()
            after.forEach { it() }
        }
    }

    /** Adds or updates one entry. Returns true when something changed. Runs inside [lock]. */
    private fun touch(
        key: String, tab: DexTab, name: String, icon: String, rssi: Int, now: Long,
        after: MutableList<() -> Unit>, gameCategory: String?
    ): Boolean {
        val day = today(now)
        val e = entries[key]
        if (e == null) {
            // A type the game had already counted before the Dex existed is added quietly.
            val known = gameCategory != null && Game.hasCategory(gameCategory)
            entries[key] = DexEntry(key, tab, name, icon, now, now, 1, day, rssi, fresh = !known)
            if (!known) {
                when (tab) {
                    DexTab.TYPES -> {
                        val found = entries.values.count { it.tab == DexTab.TYPES }
                        val total = Classifier.categories().size
                        after.add { Game.push(GameEvent.DexEntryFound(Game.nextSeq(), name, icon, "TYPE", found, total, 0)) }
                    }
                    DexTab.KINDS -> {
                        val card = now - lastCardAt > CARD_GAP_MS
                        if (card) lastCardAt = now
                        val found = entries.values.count { it.tab == DexTab.KINDS }
                        after.add {
                            Game.grantXp(8, "NEW KIND")
                            if (card) Game.push(GameEvent.DexEntryFound(Game.nextSeq(), name, icon, "KIND", found, 0, 8))
                        }
                    }
                    DexTab.MAKERS -> after.add { Game.grantXp(5, "NEW MAKER") }
                }
            }
            return true
        }
        var ne = e
        var changed = false
        if (day != e.lastDay) {
            ne = ne.copy(days = e.days + 1, lastDay = day, last = now)
            changed = true
            val before = e.stars
            val star = ne.stars
            if (star > before) {
                after.add { Game.grantXp(if (star >= 3) 30 else 15, "DEX MASTERY") }
            }
        }
        if (rssi > e.best + 3) {
            ne = ne.copy(best = rssi)
            changed = true
        }
        if (changed) entries[key] = ne
        return changed
    }

    /** The Dex was looked at, so entries no longer count as NEW. */
    fun markViewed() {
        synchronized(lock) {
            if (!loaded || entries.values.none { it.fresh }) return
            for (k in entries.keys.toList()) {
                val e = entries[k] ?: continue
                if (e.fresh) entries[k] = e.copy(fresh = false)
            }
            dirty = true
            refresh()
        }
    }

    fun reset() {
        synchronized(lock) {
            if (!loaded) return
            entries.clear()
            lastEval.clear()
            dirty = false
            ctx?.let { runCatching { File(it.filesDir, FILE).delete() } }
            refresh()
        }
    }

    /** Writes the book to disk when something changed (called together with the game's own save). */
    fun save() {
        val text: String
        val dir: File
        synchronized(lock) {
            if (!loaded || !dirty) return
            dirty = false
            dir = ctx?.filesDir ?: return
            val a = JSONArray()
            for (e in entries.values) {
                a.put(
                    JSONObject()
                        .put("k", e.key).put("t", e.tab.name).put("n", e.name).put("i", e.icon)
                        .put("f", e.first).put("l", e.last).put("d", e.days).put("ld", e.lastDay)
                        .put("b", e.best).put("w", e.fresh)
                )
            }
            text = a.toString()
        }
        runCatching { File(dir, FILE).writeText(text) }
    }
}
