package com.example.signalopsirl

/** One row of the Signals list. */
class ShownDevice(
    val d: SeenDevice,
    /** More than 1 for a group of unnamed rotating devices of the same kind. */
    val count: Int,
    /** How many other devices with exactly this name are hidden behind this (strongest) one. */
    val sameName: Int,
    /** How many addresses this one device has used (1 = no rotation seen). */
    val linked: Int
)

class SignalRows(
    val rows: List<ShownDevice>, val total: Int,
    /** Addresses the service currently holds. */
    val all: Int = 0,
    /** Addresses folded into another row because they were linked as one rotating-address device. */
    val merged: Int = 0,
    /** Unnamed devices folded into an "x N" row. */
    val grouped: Int = 0,
    /** Devices hidden behind a stronger device with exactly the same name. */
    val sameName: Int = 0,
    /** Rows dropped by the NEW filter. */
    val filteredNew: Int = 0,
    /** Rows cut off because the list is limited in length. */
    val cut: Int = 0
) {
    /** Everything in the scanner that has no row of its own right now. */
    val hidden: Int get() = (all - rows.size).coerceAtLeast(0)
}

/** The user's own name for a device, if they gave it one. */
fun Map<String, String>.nick(address: String): String? = this[address.uppercase()]

/**
 * Turns the raw per-address device map into what the Signals list shows:
 *  1. addresses linked to one device collapse into one row (the newest address),
 *  2. unnamed rotating "kinds" (Find My etc.) collapse into one row with a count,
 *  3. devices sharing an exact name collapse into the strongest one currently heard.
 * Devices the user has named are never grouped or hidden; they always get their own row.
 */
fun buildSignalRows(
    all: Collection<SeenDevice>, mergeRotating: Boolean, onePerName: Boolean, now: Long,
    nicks: Map<String, String> = emptyMap(), limit: Int = 40,
    /** > 0 = only devices first heard within this many ms, newest first. 0 = everything, strongest first. */
    newWithinMs: Long = 0L,
    /** True = unnamed devices of the same kind collapse into one "x N" row (old behaviour). False = one row each. */
    groupAnon: Boolean = false
): SignalRows {
    val devices: List<SeenDevice> = if (mergeRotating) {
        all.groupBy { if (it.identity != 0) "i${it.identity}" else it.address }
            .values.map { g ->
                val top = g.maxByOrNull { it.lastSeen }!!
                // a device is only "new" when its oldest address is new
                if (g.size == 1) top else top.copy(firstSeen = g.minOf { it.firstSeen })
            }
    } else all.toList()

    val (mine, others) = devices.partition { nicks.nick(it.address) != null }
    val (anon, named) =
        if (groupAnon) others.partition { it.name == null && it.cls?.anon == true }
        else Pair(emptyList<SeenDevice>(), others)
    val rows = ArrayList<ShownDevice>()
    var groupedAway = 0
    var sameNameAway = 0
    mine.forEach { rows.add(ShownDevice(it, 1, 0, it.linked)) }

    for (g in anon.groupBy { "${it.cls?.category}|${it.cls?.label}" }.values) {
        val strongest = g.maxByOrNull { it.rssi }!!
        groupedAway += g.size - 1
        // a group row is as fresh as its newest member and only as new as its oldest
        val top = if (g.size == 1) strongest
        else strongest.copy(lastSeen = g.maxOf { it.lastSeen }, firstSeen = g.minOf { it.firstSeen })
        rows.add(ShownDevice(top, g.size, 0, if (g.size == 1) top.linked else 1))
    }

    if (onePerName) {
        val (withName, noName) = named.partition { it.name != null }
        noName.forEach { rows.add(ShownDevice(it, 1, 0, it.linked)) }
        for (g in withName.groupBy { it.name }.values) {
            val active = g.filter { now - it.lastSeen < 30_000L }
            val strongest = (if (active.isNotEmpty()) active else g).maxByOrNull { it.rssi }!!
            sameNameAway += g.size - 1
            val top = if (g.size == 1) strongest
            else strongest.copy(lastSeen = g.maxOf { it.lastSeen }, firstSeen = g.minOf { it.firstSeen })
            rows.add(ShownDevice(top, 1, g.size - 1, top.linked))
        }
    } else {
        named.forEach { rows.add(ShownDevice(it, 1, 0, it.linked)) }
    }

    val sorted = if (newWithinMs > 0L) {
        rows.filter { it.d.firstSeen > 0L && now - it.d.firstSeen <= newWithinMs }
            .sortedWith(compareByDescending<ShownDevice> { it.d.firstSeen }.thenBy { it.d.address })
    } else {
        rows.sortedWith(compareByDescending<ShownDevice> { it.d.rssi }.thenBy { it.d.address })
    }
    val shown = sorted.take(limit)
    return SignalRows(
        shown, sorted.size, all.size, all.size - devices.size, groupedAway, sameNameAway,
        rows.size - sorted.size, sorted.size - shown.size
    )
}

/** A distance given in metres, shown in feet (or miles when long): "420FT", "1.3MI". */
fun distanceText(meters: Int): String {
    val ft = meters * 3.28084
    return if (ft < 1000.0) "${Math.round(ft / 10.0) * 10L}FT" else String.format(java.util.Locale.US, "%.1fMI", ft / 5280.0)
}

/** "4s", "12m", "3h": how long ago a device was last heard. */
fun ageText(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60L -> "${s}s"
        s < 3600L -> "${s / 60L}m"
        else -> "${minOf(s / 3600L, 99L)}h"
    }
}

/**
 * Very rough distance from signal strength (log-distance model, -59 dBm at 1 m, path-loss exponent 2.5), in feet.
 * Walls, bodies and each device's transmit power move this a lot, so it is shown coarsely.
 */
fun roughDistance(rssi: Int): String {
    val ft = Math.pow(10.0, (-59.0 - rssi) / 25.0) * 3.28084
    return when {
        ft < 3.0 -> "<3ft"
        ft < 30.0 -> "~${Math.round(ft)}ft"
        ft < 150.0 -> "~${Math.round(ft / 5.0) * 5L}ft"
        else -> ">150ft"
    }
}


/** True for an unnamed device whose row would look the same as other devices of its kind. */
fun isAnonRow(d: SeenDevice): Boolean = d.name == null && d.cls?.anon == true

/** A short tag that tells such rows apart: the linked-device number when addresses are merged, else the address tail. */
fun anonTag(d: SeenDevice): String = if (d.identity != 0) "#${d.identity}" else d.address.takeLast(5)

/** Shortens the long generic labels so the tag after them stays visible in the one-line row. */
fun anonLabel(label: String): String = when (label) {
    "Apple Find My accessory" -> "Find My"
    "Apple/Beats earbuds" -> "Apple earbuds"
    else -> if (label.length > 14) label.take(13) + "\u2026" else label
}
