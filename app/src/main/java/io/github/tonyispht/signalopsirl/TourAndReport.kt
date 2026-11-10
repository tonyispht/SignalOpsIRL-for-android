package io.github.tonyispht.signalopsirl

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private class TourPage(val glyph: String, val title: String, val body: String)

private val TOUR = listOf(
    TourPage("◉", "Scan", "Tap INITIATE SCAN on the SCAN tab. Signal OPS listens to the Bluetooth broadcasts around you and lists every device it hears, strongest first."),
    TourPage("☰", "Signals", "Search, filter and sort the list from the bar above it. Tap any device for its full details: what it is, who made it, a live signal graph, and actions like LOCATE, NAME IT and PIN."),
    TourPage("⌖", "Locate and protect", "LOCATE turns your phone into a hot/cold finder for lost devices. The TRACKERS tab warns you when an AirTag or similar tag keeps following you."),
    TourPage("◈", "Level up", "Every new device earns XP. Daily quests, streaks, achievements and the Signal Dex (DEX tab) reward you for exploring. Rare device types are worth hunting."),
    TourPage("⚙", "Make it yours", "SETTINGS has alerts, sounds, effects, text size and more, split into short pages with a search box. Please use the app responsibly: it's for fun, learning and your own security.")
)

/** First-run walkthrough of the app, shown once after the terms are accepted. */
@Composable
fun TourDialog(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var i by remember { mutableIntStateOf(0) }
    val p = TOUR[i]
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(18.dp)) {
            HudCard(border = cs.primary) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("WELCOME, OPERATIVE", fontSize = 11.sp, color = cs.onSurfaceVariant, letterSpacing = 2.sp)
                    Text(p.glyph, fontSize = 44.sp, color = cs.primary)
                    Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                    Text(p.body, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TOUR.indices.forEach { k ->
                            Box(Modifier.size(8.dp).background(if (k == i) cs.primary else cs.outline, CircleShape))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MiniButton("SKIP", cs.onSurfaceVariant) { onDone() }
                        Box(Modifier.weight(1f))
                        if (i > 0) MiniButton("BACK", cs.onSurfaceVariant) { i-- }
                        MiniButton(if (i == TOUR.lastIndex) "LET'S GO" else "NEXT", cs.primary) {
                            if (i == TOUR.lastIndex) onDone() else i++
                        }
                    }
                }
            }
        }
    }
}

/** What one scan session found, for the report after STANDBY. */
data class SessionReport(
    val durationMs: Long,
    val heard: Int,
    val fresh: Int,
    val alerts: Int,
    val xp: Long,
    /** Device types found for the first time ever during this session. */
    val newTypes: List<CategoryInfo>,
    /** Rare, epic and legendary types seen this session (title, rarity). */
    val rare: List<Pair<String, Rarity>>,
    /** Exact product models seen this session. */
    val models: List<String>,
    val peakName: String?,
    val peakRssi: Int?,
    val startedAt: Long = 0L,
    /** Kinds of device (categories) heard this session. */
    val kinds: Int = 0,
    val targets: Int = 0,
    /** Operator level and equipped title when the session ended. */
    val level: Int = 0,
    val rank: String = ""
) {
    fun shareText(): String = buildString {
        val min = (durationMs / 60_000L).coerceAtLeast(1)
        append("Signal OPS IRL session: $min min, $heard devices heard, $fresh new")
        if (xp > 0) append(", +$xp XP")
        append(".")
        if (newTypes.isNotEmpty()) append("\nNew types: " + newTypes.joinToString(", ") { it.title.lowercase().replaceFirstChar { c -> c.uppercase() } })
        if (rare.isNotEmpty()) append("\nRare finds: " + rare.joinToString(", ") { "${it.first} (${it.second.label.lowercase()})" })
        if (models.isNotEmpty()) append("\nModels spotted: " + models.take(6).joinToString(", "))
        append("\n\nhttps://github.com/tonyispht/SignalOpsIRL-for-android")
    }
}

/** The session report shown after a scan stops, with a SHARE button. */
@Composable
fun SessionReportDialog(r: SessionReport, onShare: () -> Unit, onShareText: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = cs.primary.toArgb()
    val card = remember(r) { SessionCard.render(r, tint).asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
            HudCard(border = cs.primary) {
                Column(Modifier.padding(14.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("// SESSION REPORT", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary, letterSpacing = 1.sp)
                    // the shareable card itself, as it will look when shared
                    Image(card, "Session card", Modifier.fillMaxWidth().aspectRatio(1080f / 1350f).border(1.dp, cs.outline))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("KINDS", "${r.kinds}", Modifier.weight(1f))
                        Stat("TARGETS", "${r.targets}", Modifier.weight(1f))
                        Stat("MODELS", "${r.models.size}", Modifier.weight(1f))
                        Stat("LEVEL", if (r.level > 0) "${r.level}" else "-", Modifier.weight(1f))
                    }
                    val min = r.durationMs / 60_000L
                    val sec = (r.durationMs / 1000L) % 60
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("TIME", if (min > 0) "${min}m ${sec}s" else "${sec}s", Modifier.weight(1f))
                        Stat("HEARD", "${r.heard}", Modifier.weight(1f))
                        Stat("NEW", "${r.fresh}", Modifier.weight(1f))
                        Stat("XP", "+${r.xp}", Modifier.weight(1f))
                    }
                    if (r.alerts > 0) Text("${r.alerts} alert${if (r.alerts == 1) "" else "s"} this session", fontSize = 12.sp, color = AlertAmber)
                    r.peakName?.let { Text("Closest: $it (${r.peakRssi} dBm)", fontSize = 12.sp, color = cs.onSurfaceVariant) }
                    if (r.newTypes.isNotEmpty()) {
                        Text("NEW DEVICE TYPES", fontSize = 11.sp, color = GameViolet, letterSpacing = 1.sp)
                        r.newTypes.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${c.icon} ${c.title}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                RarityBadge(rarityOf(c.key))
                            }
                        }
                    }
                    if (r.rare.isNotEmpty()) {
                        Text("RARE FINDS", fontSize = 11.sp, color = GameGold, letterSpacing = 1.sp)
                        r.rare.forEach { (t, rr) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                RarityBadge(rr)
                            }
                        }
                    }
                    if (r.models.isNotEmpty()) {
                        Text("MODELS SPOTTED", fontSize = 11.sp, color = cs.secondary, letterSpacing = 1.sp)
                        Text(r.models.joinToString(", "), fontSize = 13.sp)
                    }
                    if (r.newTypes.isEmpty() && r.rare.isEmpty() && r.models.isEmpty()) {
                        Text("No new types this time. Try a busier place: a mall, a gym, an airport or a coffee shop.",
                            fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MiniButton("CLOSE", cs.onSurfaceVariant) { onDismiss() }
                        MiniButton("SHARE TEXT", cs.secondary) { onShareText() }
                        MiniButton("SHARE IMAGE", cs.primary) { onShare() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        Text(label, fontSize = 10.sp, color = cs.onSurfaceVariant, letterSpacing = 1.sp)
    }
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Pick targets from devices already seen instead of typing names and addresses. */
@Composable
fun PickTargetsDialog(
    devices: Collection<SeenDevice>,
    nicks: Map<String, String>,
    watch: List<String>,
    onAddName: (String) -> Unit,
    onAddMac: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val list = remember(devices) { devices.sortedByDescending { it.rssi }.take(80) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            HudCard(border = cs.primary) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("// PICK TARGETS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.weight(1f))
                        MiniButton("DONE") { onDismiss() }
                    }
                    Text("Devices heard this session, strongest first. NAME alerts on any device with that name; MAC alerts on that exact address.",
                        fontSize = 12.sp, lineHeight = 15.sp, color = cs.onSurfaceVariant)
                    if (list.isEmpty()) Text("Nothing heard yet. Start a scan first.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { d ->
                            val title = nicks.nick(d.address) ?: d.name ?: d.cls?.label ?: "(no name) ${d.address.takeLast(5)}"
                            val nameOn = d.name != null && watch.any { it.equals(d.name, ignoreCase = true) }
                            val macOn = watch.any { it.equals(d.address, ignoreCase = true) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 13.sp, maxLines = 1)
                                    Text("${d.address} · ${d.rssi} dBm", fontSize = 10.sp, color = cs.onSurfaceVariant)
                                }
                                d.name?.let { n ->
                                    MiniButton(if (nameOn) "✓ NAME" else "+NAME", if (nameOn) cs.onSurfaceVariant else cs.primary) {
                                        if (!nameOn) onAddName(n)
                                    }
                                }
                                MiniButton(if (macOn) "✓ MAC" else "+MAC", if (macOn) cs.onSurfaceVariant else cs.primary) {
                                    if (!macOn) onAddMac(d.address)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
