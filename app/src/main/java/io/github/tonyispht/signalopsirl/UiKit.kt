package io.github.tonyispht.signalopsirl

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the bottom navigation bar (without the system navigation inset). */
val NAV_H: Dp = 58.dp

/** The four main tabs. */
enum class MainTab(val label: String, val glyph: String) {
    SCAN("SCAN", "◉"),
    DEX("DEX", "◈"),
    TRACKERS("TRACKERS", "⌖"),
    SETTINGS("SETTINGS", "⚙")
}

/**
 * Bottom navigation: SCAN · DEX · TRACKERS · SETTINGS, with small count badges.
 * The selected tab gets a glowing bar that slides between tabs, and its icon grows a little.
 */
@Composable
fun MainNavBar(current: MainTab, badges: Map<MainTab, String>, modifier: Modifier = Modifier, onSelect: (MainTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tabs = MainTab.values()
    val pos by animateFloatAsState(current.ordinal.toFloat(), spring(dampingRatio = 0.75f, stiffness = 420f), label = "navPos")
    Column(modifier.fillMaxWidth().background(Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline))
        Box(Modifier.fillMaxWidth().navigationBarsPadding().height(NAV_H)) {
            // sliding glow behind the selected tab
            Canvas(Modifier.matchParentSize()) {
                val w = size.width / tabs.size
                val cx = w * (pos + 0.5f)
                drawRect(
                    Brush.radialGradient(
                        listOf(cs.primary.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(cx, size.height), radius = w * 0.75f
                    )
                )
                val bar = w * 0.42f
                drawRect(cs.primary, topLeft = Offset(cx - bar / 2, 0f), size = Size(bar, 2.5.dp.toPx()))
                drawRect(cs.primary.copy(alpha = 0.25f), topLeft = Offset(cx - bar / 2, 0f), size = Size(bar, 7.dp.toPx()))
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    val on = t == current
                    val scale by animateFloatAsState(if (on) 1.18f else 1f, spring(stiffness = 500f), label = "navScale")
                    val tint by animateColorAsState(if (on) cs.primary else cs.onSurfaceVariant, tween(220), label = "navTint")
                    Column(
                        Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            Text(t.glyph, fontSize = 20.sp, color = tint, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                            badges[t]?.let { b ->
                                Text(
                                    b, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 18.dp)
                                        .background(if (t == MainTab.TRACKERS) AlertAmber else GameViolet, CutCornerShape(2.dp))
                                        .padding(horizontal = 3.dp)
                                )
                            }
                        }
                        Text(
                            t.label, fontSize = 10.sp, letterSpacing = 1.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen page used by the DEX, TRACKERS and SETTINGS tabs: title bar, content, room for the nav bar. */
@Composable
fun TabPage(title: String, topPad: Dp, fx: Boolean, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(top = topPad)
            .navigationBarsPadding().padding(bottom = NAV_H + 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlitchText(title, cs.primary, 16.sp, fx, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** Search text of the Settings page; settings rows that don't match it hide themselves. Empty = no search. */
val LocalSettingsQuery = compositionLocalOf { "" }

/** True when [texts] match the current Settings search (or there is no search). */
@Composable
fun settingMatches(vararg texts: String?): Boolean {
    val q = LocalSettingsQuery.current.trim()
    if (q.isEmpty()) return true
    return texts.any { it != null && it.contains(q, ignoreCase = true) }
}

private val KEEP_UPPER = setOf(
    "BLE", "BT", "LE", "GPS", "MAC", "MACS", "USB", "OTG", "PIN", "ID", "IDS", "OUI", "IEEE", "SIG", "UUID", "UUIDS",
    "CPU", "BR/EDR", "MPH", "XP", "FX", "CRT", "GATT", "PHY", "RSSI", "DBM", "IOT", "UI", "OK", "TV", "PC", "PCS",
    "ALERT", "POSSIBLE", "HOT/COLD", "NEW", "RAW", "MINE", "SIGNALS", "TRACKERS", "SETTINGS", "DEX", "SCAN",
    "INITIATE", "STANDBY", "LOCATE", "SOUND", "NFC", "1M", "2M", "S", "N", "M"
)
private val CAP_WORDS = mapOf(
    "AIRTAG" to "AirTag", "TILE" to "Tile", "SMARTTAG" to "SmartTag", "APPLE" to "Apple", "ANDROID" to "Android",
    "BLUETOOTH" to "Bluetooth", "GOOGLE" to "Google", "SAMSUNG" to "Samsung", "WINDOWS" to "Windows", "LINUX" to "Linux",
    "REALTEK" to "Realtek", "TP-LINK" to "TP-Link", "FIND" to "Find", "MY" to "My", "GITHUB" to "GitHub", "IEEE" to "IEEE"
)

/**
 * Turns an ALL-CAPS explanation into easier-to-read sentence case, keeping acronyms (BLE, MAC, GPS...) and setting
 * names in capitals. Text that is not all caps is returned unchanged.
 */
fun readable(s: String): String {
    if (s.any { it.isLowerCase() }) return s
    val out = StringBuilder(s.length)
    var startOfSentence = true
    val tokens = Regex("[^\\s]+|\\s+").findAll(s).map { it.value }
    for (t in tokens) {
        if (t.isBlank()) { out.append(t); continue }
        val core = t.trim { !it.isLetterOrDigit() && it != '/' && it != '-' }
        val word = when {
            core.isEmpty() -> t
            core.uppercase() in KEEP_UPPER && core.length > 1 -> t
            CAP_WORDS.containsKey(core.uppercase()) -> t.replace(core, CAP_WORDS.getValue(core.uppercase()))
            core.any { it.isDigit() } -> t
            else -> {
                val lower = t.lowercase()
                if (startOfSentence) lower.replaceFirstChar { it.uppercase() } else lower
            }
        }
        out.append(word)
        startOfSentence = t.endsWith(".") || t.endsWith(":") || t.endsWith("!") || t.endsWith("?")
    }
    return out.toString()
}

/** A small line graph. [values] NaN = gap. [min]/[max] null = fit to the data. */
@Composable
fun Sparkline(
    values: List<Float>, color: Color, modifier: Modifier = Modifier,
    min: Float? = null, max: Float? = null, fill: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawRect(cs.outline.copy(alpha = 0.25f), style = Stroke(1f))
        val pts = values.filter { !it.isNaN() }
        if (pts.size < 2) return@Canvas
        val lo = min ?: pts.min()
        val hi = (max ?: pts.max()).let { if (it - lo < 1e-3f) lo + 1f else it }
        val n = values.size
        val path = Path()
        var started = false
        var lastX = 0f
        values.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val x = if (n == 1) 0f else size.width * i / (n - 1)
            val y = size.height - (v.coerceIn(lo, hi) - lo) / (hi - lo) * size.height
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            lastX = x
        }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(lastX, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.15f))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        // grid line at mid height
        drawLine(cs.outline.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
    }
}

/** A tappable square-cornered tile for the action grid in the device detail screen. */
@Composable
fun ActionTile(label: String, sub: String?, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.border(1.dp, color.copy(alpha = 0.7f), CutCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp)
        if (sub != null) Text(sub, fontSize = 10.sp, lineHeight = 12.sp, color = cs.onSurfaceVariant)
    }
}

// ---- rarity of device types (Signal Dex) ----------------------------------------------------------

enum class Rarity(val label: String, val color: Color) {
    COMMON("COMMON", Color(0xFF8FA3B0)),
    UNCOMMON("UNCOMMON", Color(0xFF39FF88)),
    RARE("RARE", Color(0xFF00B7FF)),
    EPIC("EPIC", Color(0xFFB66BFF)),
    LEGENDARY("LEGENDARY", Color(0xFFFFC233))
}

private val COMMON_TYPES = setOf(
    "smartphones", "laptops_tablets", "wearables_audio", "computer_peripherals", "smart_iot", "home_entertainment_av",
    "networking_mesh", "smart_appliances"
)
private val UNCOMMON_TYPES = setOf(
    "auto_aerial", "trackers_valuables", "fitness_gym", "handhelds_gaming", "proximity_beacons", "medical_health",
    "toys_robots_stem", "ereaders_notepads_reading", "commercial_pos_retail", "security_access_control",
    "music_instruments_pro_audio", "ev_micromobility", "pet_trackers", "cameras_photo_video_production"
)
private val EPIC_TYPES = setOf(
    "hacker_pentest", "hobby_rc_drones", "crypto_security_keys", "smart_glasses_ai_wearables", "card_skimmer_suspects",
    "aviation_marine_gliding", "military_defense_govt", "utilities_scada_smart_city", "ems_ambulance_fire_rescue"
)

fun rarityOf(categoryKey: String): Rarity = when (categoryKey) {
    in COMMON_TYPES -> Rarity.COMMON
    in UNCOMMON_TYPES -> Rarity.UNCOMMON
    in EPIC_TYPES -> Rarity.EPIC
    else -> Rarity.RARE
}

@Composable
fun RarityBadge(r: Rarity) {
    Text(
        r.label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center,
        modifier = Modifier.background(r.color, CutCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** A circular progress ring that animates to [progress] (0..1), with [label] in the middle. */
@Composable
fun ProgressRing(progress: Float, color: Color, label: String, sub: String? = null, modifier: Modifier = Modifier, ringSize: Dp = 74.dp) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = 7.dp.toPx()
            val inset = sw / 2
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(cs.outline.copy(alpha = 0.45f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            // soft glow under the arc
            drawArc(color.copy(alpha = 0.25f), -90f, 360f * p, false, Offset(inset, inset), arcSize,
                style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            if (sub != null) Text(sub, fontSize = 9.sp, color = cs.onSurfaceVariant, letterSpacing = 1.sp)
        }
    }
}

/**
 * A half-circle signal meter from -100 dBm (left) to -35 dBm (right), coloured weak (cool) to strong (hot),
 * with an animated needle and the dBm value in the middle.
 */
@Composable
fun SignalGauge(rssi: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val t by animateFloatAsState(((rssi + 100f) / 65f).coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 120f), label = "gauge")
    Box(modifier.size(width = 120.dp, height = 68.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.matchParentSize()) {
            val sw = 8.dp.toPx()
            val d = size.width - sw
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(d, d)
            val steps = 24
            for (i in 0 until steps) {
                val f = i / steps.toFloat()
                val on = f < t
                drawArc(
                    if (on) heatSpectrum(f) else cs.outline.copy(alpha = 0.35f),
                    180f + 180f * f + 1f, 180f / steps - 2f, false, topLeft, arc, style = Stroke(sw)
                )
            }
            // needle
            val ang = Math.toRadians((180.0 + 180.0 * t))
            val c = Offset(size.width / 2, sw / 2 + d / 2)
            val r = d / 2 - sw * 1.4f
            val tip = Offset(c.x + (r * kotlin.math.cos(ang)).toFloat(), c.y + (r * kotlin.math.sin(ang)).toFloat())
            // the needle starts outside the number so it never covers it
            val base = Offset(c.x + (r * 0.62f * kotlin.math.cos(ang)).toFloat(), c.y + (r * 0.62f * kotlin.math.sin(ang)).toFloat())
            drawLine(heatSpectrum(t).copy(alpha = 0.35f), base, tip, 6.dp.toPx(), StrokeCap.Round)
            drawLine(heatSpectrum(t), base, tip, 2.dp.toPx(), StrokeCap.Round)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 0.dp)) {
            Text("$rssi", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = heatSpectrum(t))
            Text(" dBm", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** A small radar shown while the Signals list is empty: it sweeps while scanning and sits still when stopped. */
@Composable
fun ListeningRadar(active: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val inf = rememberInfiniteTransition(label = "radar")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    Canvas(modifier.size(120.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 2.dp.toPx()
        val line = cs.primary.copy(alpha = 0.35f)
        for (k in 1..3) drawCircle(line, r * k / 3f, c, style = Stroke(1.dp.toPx()))
        drawLine(line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        if (active) {
            drawCircle(cs.primary.copy(alpha = 0.5f * (1f - pulse)), r * pulse, c, style = Stroke(2.dp.toPx()))
            rotate(sweep, c) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, cs.primary.copy(alpha = 0.45f)), center = c),
                    -60f, 60f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2)
                )
                drawLine(cs.primary, c, Offset(c.x + r, c.y), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        drawCircle(cs.primary, 3.dp.toPx(), c)
    }
}
