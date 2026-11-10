package io.github.tonyispht.signalopsirl

/**
 * Devices the user has hidden from the Signals list (their own phone, watch, TV, a neighbour's speaker...).
 *
 * A device is hidden by its MAC address when the address is public (it never changes) or when it has no name.
 * A named device with a random address is hidden by its name instead, because that kind of address changes.
 *
 * Hidden devices still count for XP, quests and the Signal Dex, and your target list still alerts on them.
 * They only leave the Signals list and stop new-device alerts and sounds. Tracker warnings are never affected
 * (use MARK AS MINE for your own tracker), so a hidden device can't silence a warning about being followed.
 */
object Hidden {
    /** One hidden entry. [key] is "MAC:AA:BB:CC:DD:EE:FF" or "NAME:lowercase name"; [label] is what the list shows. */
    data class Entry(val key: String, val label: String) {
        val byName: Boolean get() = key.startsWith("NAME:")
        val value: String get() = key.substringAfter(':')
    }

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var keys: Set<String> = emptySet()
    @Volatile private var loaded = false

    /** The key [d] would be hidden under. */
    fun keyFor(d: SeenDevice): String {
        val name = d.name?.trim()?.takeIf { it.isNotEmpty() }
        return if (name != null && Vendors.isLocal(d.address)) "NAME:" + name.lowercase() else "MAC:" + d.address.uppercase()
    }

    /** True when [d] matches a hidden entry (by address or by name) in [keySet]. */
    fun matches(keySet: Set<String>, d: SeenDevice): Boolean {
        if (keySet.isEmpty()) return false
        if ("MAC:" + d.address.uppercase() in keySet) return true
        val name = d.name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return "NAME:" + name.lowercase() in keySet
    }

    fun load(store: Store) {
        if (loaded) return
        set(parse(store.hiddenRaw))
    }

    fun list(): List<Entry> = entries
    fun keys(): Set<String> = keys
    fun isHidden(d: SeenDevice): Boolean = matches(keys, d)

    fun hide(store: Store, d: SeenDevice, label: String) {
        val k = keyFor(d)
        if (k in keys) return
        save(store, entries + Entry(k, label.ifBlank { k.substringAfter(':') }))
    }

    /** Unhides whatever entry hides [d]. */
    fun unhide(store: Store, d: SeenDevice) {
        val mac = "MAC:" + d.address.uppercase()
        val nm = d.name?.trim()?.takeIf { it.isNotEmpty() }?.let { "NAME:" + it.lowercase() }
        save(store, entries.filter { it.key != mac && it.key != nm })
    }

    fun unhideKey(store: Store, key: String) = save(store, entries.filter { it.key != key })
    fun clear(store: Store) = save(store, emptyList())

    /** Stored as one "key<TAB>label" string per entry. */
    fun parse(raw: Set<String>): List<Entry> = raw.mapNotNull { s ->
        val i = s.indexOf('\t')
        val k = if (i < 0) s else s.substring(0, i)
        if (!k.startsWith("MAC:") && !k.startsWith("NAME:")) return@mapNotNull null
        Entry(k, if (i < 0) k.substringAfter(':') else s.substring(i + 1))
    }.distinctBy { it.key }.sortedBy { it.label.lowercase() }

    private fun save(store: Store, list: List<Entry>) {
        store.hiddenRaw = list.map { it.key + "\t" + it.label }.toSet()
        set(list.sortedBy { it.label.lowercase() })
    }

    private fun set(list: List<Entry>) {
        entries = list
        keys = list.map { it.key }.toSet()
        loaded = true
    }
}
