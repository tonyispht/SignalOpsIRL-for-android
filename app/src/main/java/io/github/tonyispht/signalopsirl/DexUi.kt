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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stars(n: Int): String = "\u2605".repeat(n.coerceIn(0, 3)) + "\u2606".repeat(3 - n.coerceIn(0, 3))

/** The Signal Dex: TYPES (a fixed list to complete), KINDS and MAKERS (open-ended collections). */
@Composable
fun DexDialog(state: DexState, onDismiss: () -> Unit) {
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(10.dp)) {
            HudCard(border = GameViolet) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("// SIGNAL DEX", color = GameViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    MiniButton("CLOSE") { onDismiss() }
                }
                Text(
                    "TYPES $typesFound/$total  \u00B7  KINDS ${kinds.size}  \u00B7  MAKERS ${makers.size}  \u00B7  MASTERED $mastered",
                    color = cs.onSurfaceVariant, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    ChipRow(DexTab.values().toList(), tab, { it.label }) { tab = it }
                }
                LazyColumn(
                    Modifier.heightIn(max = 470.dp).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (tab) {
                        DexTab.TYPES -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    val pct = if (total > 0) typesFound * 100 / total else 0
                                    Text(
                                        if (total > 0) "$typesFound OF $total KINDS OF DEVICE FOUND  ($pct%)"
                                        else "LOADING THE LIST OF DEVICE TYPES...",
                                        color = GameViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold
                                    )
                                    GameBar(if (total > 0) typesFound.toFloat() / total else 0f, height = 6.dp, color = GameViolet)
                                    Text("FINDING ONE OF EACH TYPE FILLS ITS PAGE. LOCKED PAGES SHOW WHAT IS STILL OUT THERE.",
                                        color = cs.onSurfaceVariant, fontSize = 9.sp)
                                }
                            }
                            val found = state.categories.filter { it.key in typeKeys }
                                .sortedByDescending { c -> types.firstOrNull { it.key == "T:" + c.key }?.first ?: 0L }
                            val missing = state.categories.filter { it.key !in typeKeys }
                            items(found, key = { "f" + it.key }) { c ->
                                val e = types.firstOrNull { it.key == "T:" + c.key }
                                DexRow(c.icon, c.title, e, fmt, locked = false, hint = null)
                            }
                            items(missing, key = { "m" + it.key }) { c ->
                                DexRow(
                                    c.icon, c.title, null, fmt, locked = true,
                                    hint = when (c.tier) {
                                        Tier.ALERT -> "ALERT-TIER TYPE"
                                        Tier.POSSIBLE -> "POSSIBLE-TIER TYPE"
                                        Tier.INFO -> null
                                    }
                                )
                            }
                        }
                        DexTab.KINDS -> {
                            item {
                                Text("${kinds.size} SPECIFIC KINDS FOUND (AIRPODS MODELS, TRACKERS, NAMED PRODUCTS...). NEWEST FIRST.",
                                    color = GameViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if (kinds.isEmpty()) item { EmptyHint() }
                            items(kinds, key = { it.key }) { e -> DexRow(e.icon, e.name, e, fmt, locked = false, hint = null) }
                        }
                        DexTab.MAKERS -> {
                            item {
                                Text("${makers.size} MAKERS FOUND (FROM THE MAC PREFIX OR THE BLUETOOTH COMPANY ID). NEWEST FIRST.",
                                    color = GameViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if (makers.isEmpty()) item { EmptyHint() }
                            items(makers, key = { it.key }) { e -> DexRow(e.icon, e.name, e, fmt, locked = false, hint = null) }
                        }
                    }
                    item {
                        Text(
                            "MASTERY: \u2605 FOUND, \u2605\u2605 SEEN ON 3 DIFFERENT DAYS (+15 XP), \u2605\u2605\u2605 SEEN ON 7 DIFFERENT DAYS (+30 XP). " +
                                "NEW KIND +8 XP, NEW MAKER +5 XP. ENTRIES ARE BY TYPE, PRODUCT AND MAKER, NEVER BY ADDRESS, " +
                                "SO A DEVICE THAT CHANGES ADDRESS CANNOT FILL THE BOOK.",
                            color = cs.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Text("NOTHING HERE YET. KEEP SCANNING.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
}

@Composable
private fun DexRow(icon: String, title: String, e: DexEntry?, fmt: SimpleDateFormat, locked: Boolean, hint: String?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (locked) "?" else icon.ifEmpty { "\u25C8" }, fontSize = 18.sp,
            color = if (locked) cs.outline else Color.White, modifier = Modifier.width(30.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (locked) cs.onSurfaceVariant.copy(alpha = 0.6f) else Color.White,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (e != null && e.fresh) {
                    Text("NEW", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).background(GameViolet).padding(horizontal = 3.dp))
                }
            }
            if (e != null) {
                Text(
                    "FIRST ${fmt.format(Date(e.first))}  \u00B7  ${e.days} DAY${if (e.days == 1) "" else "S"}  \u00B7  BEST ${e.best} DBM",
                    fontSize = 9.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else if (hint != null) {
                Text(hint, fontSize = 9.sp, color = GameOrange.copy(alpha = 0.8f))
            } else {
                Text("NOT FOUND YET", fontSize = 9.sp, color = cs.outline)
            }
        }
        if (e != null) {
            Text(stars(e.stars), color = if (e.stars >= 3) GameGold else GameViolet, fontSize = 13.sp)
        } else {
            Text("LOCKED", color = cs.outline, fontSize = 9.sp)
        }
    }
}
