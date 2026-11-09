package io.github.tonyispht.signalopsirl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stars(n: Int): String = "\u2605".repeat(n.coerceIn(0, 3)) + "\u2606".repeat(3 - n.coerceIn(0, 3))

/** The Signal Dex (the DEX tab): TYPES (a fixed list to complete), KINDS and MAKERS (open-ended collections). */
@Composable
fun DexContent(state: DexState) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(DexTab.TYPES) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }
    val byTab = remember(state.gen) { state.entries.groupBy { it.tab } }
    val types = byTab[DexTab.TYPES] ?: emptyList()
    val kinds = byTab[DexTab.KINDS] ?: emptyList()
    val makers = byTab[DexTab.MAKERS] ?: emptyList()
    val total = state.categories.size
    val typeKeys = remember(state.gen) { types.map { it.key.removePrefix("T:") }.toSet() }
    val typesFound = state.categories.count { it.key in typeKeys }
    val mastered = state.entries.count { it.stars >= 3 }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "TYPES $typesFound/$total  \u00B7  KINDS ${kinds.size}  \u00B7  MAKERS ${makers.size}  \u00B7  MASTERED $mastered",
            color = cs.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
            ChipRow(DexTab.values().toList(), tab, { it.label }) { tab = it }
        }
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (tab) {
                DexTab.TYPES -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                            val pct = if (total > 0) typesFound * 100 / total else 0
                            Text(
                                if (total > 0) "$typesFound of $total kinds of device found ($pct%)"
                                else "Loading the list of device types...",
                                color = GameViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                            GameBar(if (total > 0) typesFound.toFloat() / total else 0f, height = 7.dp, color = GameViolet)
                            // progress per rarity
                            val byRarity = state.categories.groupBy { rarityOf(it.key) }
                            Rarity.values().forEach { r ->
                                val all = byRarity[r] ?: return@forEach
                                val got = all.count { it.key in typeKeys }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                    Box(Modifier.width(84.dp)) { RarityBadge(r) }
                                    Box(Modifier.weight(1f)) {
                                        GameBar(got.toFloat() / all.size, height = 5.dp, color = r.color)
                                    }
                                    Text("  $got/${all.size}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                            }
                            Text(
                                "Find one of each type to fill its page. Locked pages show hints of what to look for.",
                                color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp
                            )
                        }
                    }
                    val found = state.categories.filter { it.key in typeKeys }
                        .sortedByDescending { c -> types.firstOrNull { it.key == "T:" + c.key }?.first ?: 0L }
                    val missing = state.categories.filter { it.key !in typeKeys }.sortedBy { rarityOf(it.key).ordinal }
                    items(found, key = { "f" + it.key }) { c ->
                        val e = types.firstOrNull { it.key == "T:" + c.key }
                        DexRow(c.icon, c.title, e, fmt, locked = false, hint = null, rarity = rarityOf(c.key))
                    }
                    items(missing, key = { "m" + it.key }) { c ->
                        val ex = remember(c.key, state.gen) { Classifier.examples(c.key) }
                        DexRow(
                            c.icon, c.title, null, fmt, locked = true,
                            hint = if (ex.isNotEmpty()) "Look for: " + ex.joinToString(", ") else when (c.tier) {
                                Tier.ALERT -> "Alert-tier type"
                                Tier.POSSIBLE -> "Possible-tier type"
                                Tier.INFO -> null
                            },
                            rarity = rarityOf(c.key)
                        )
                    }
                }
                DexTab.KINDS -> {
                    item {
                        Text("${kinds.size} specific kinds found (AirPods models, trackers, named products...). Newest first.",
                            color = GameViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (kinds.isEmpty()) item { EmptyHint() }
                    items(kinds, key = { it.key }) { e -> DexRow(e.icon, e.name, e, fmt, locked = false, hint = null) }
                }
                DexTab.MAKERS -> {
                    item {
                        Text("${makers.size} makers found (from the MAC prefix or the Bluetooth company ID). Newest first.",
                            color = GameViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (makers.isEmpty()) item { EmptyHint() }
                    items(makers, key = { it.key }) { e -> DexRow(e.icon, e.name, e, fmt, locked = false, hint = null) }
                }
            }
            item {
                Text(
                    "Mastery: \u2605 found, \u2605\u2605 seen on 3 different days (+15 XP), \u2605\u2605\u2605 seen on 7 different days (+30 XP). " +
                        "New kind +8 XP, new maker +5 XP. Entries are by type, product and maker, never by address, " +
                        "so a device that changes address cannot fill the book.",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Text("Nothing here yet. Keep scanning.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable
private fun DexRow(
    icon: String, title: String, e: DexEntry?, fmt: SimpleDateFormat, locked: Boolean, hint: String?, rarity: Rarity? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (locked) "?" else icon.ifEmpty { "\u25C8" }, fontSize = 18.sp,
            color = if (locked) cs.outline else Color.White, modifier = Modifier.width(30.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (locked) cs.onSurfaceVariant.copy(alpha = 0.6f) else Color.White,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (rarity != null) Box(Modifier.padding(start = 6.dp)) { RarityBadge(rarity) }
                if (e != null && e.fresh) {
                    Text("NEW", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).background(GameViolet).padding(horizontal = 3.dp))
                }
            }
            if (e != null) {
                Text(
                    "FIRST ${fmt.format(Date(e.first))}  \u00B7  ${e.days} DAY${if (e.days == 1) "" else "S"}  \u00B7  BEST ${e.best} DBM",
                    fontSize = 10.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else if (hint != null) {
                Text(hint, fontSize = 11.sp, color = GameOrange.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Not found yet", fontSize = 11.sp, color = cs.outline)
            }
        }
        if (e != null) {
            Text(stars(e.stars), color = if (e.stars >= 3) GameGold else GameViolet, fontSize = 13.sp)
        } else {
            Text("LOCKED", color = cs.outline, fontSize = 9.sp)
        }
    }
}
