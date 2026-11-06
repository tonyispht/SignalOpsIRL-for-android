package io.github.tonyispht.signalopsirl

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val radarLabel = Paint().apply {
    isAntiAlias = true
    typeface = Typeface.MONOSPACE
}

/** Blip position: the angle is a stable pick from the address (Bluetooth cannot tell direction), the distance comes from signal strength. */
private fun blipPos(d: SeenDevice, c: Offset, r: Float): Offset {
    val deg = ((d.address.hashCode() and 0x7FFFFFFF) % 3600) / 10f
    val frac = ((-d.rssi - 35) / 60f).coerceIn(0.08f, 0.95f)
    val rad = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * frac * cos(rad).toFloat(), c.y + r * frac * sin(rad).toFloat())
}

private fun blipDeg(d: SeenDevice): Float = ((d.address.hashCode() and 0x7FFFFFFF) % 3600) / 10f

/** Rough distance from signal strength (free-space-ish path loss; walls and bodies make it far from exact). */
private fun approxMeters(rssi: Int): Double = 10.0.pow((-59.0 - rssi) / 25.0)

private fun fmtMeters(m: Double): String = when {
    m < 1.0 -> "<1"
    m < 10.0 -> "%.1f".format(m)
    else -> "%d".format(m.toInt())
}

/** Full-screen radar opened from the small one: every device as a blip, range rings, a sweep and tap-to-select. */
@Composable
fun RadarScreen(
    phase: Phase,
    devices: Collection<SeenDevice>,
    nameOf: (SeenDevice) -> String,
    trackTarget: String?,
    onOpen: (SeenDevice) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val cyan = cs.primary
    var live by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf<String?>(null) }
    var frameMs by remember { mutableLongStateOf(0L) }
    var wall by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { f -> frameMs = f; wall = System.currentTimeMillis() }
    }
    val scanning = phase == Phase.SCANNING
    val shown = devices.filter { !live || wall - it.lastSeen < 60_000L }
    val shownNow by rememberUpdatedState(shown)
    val selDev = sel?.let { a -> devices.firstOrNull { it.address == a } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            // swallow touches so nothing underneath reacts
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("RADAR", color = cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 3.sp)
                Text(
                    "${shown.size} CONTACTS" + (if (live) "  ·  LAST 60 S" else "  ·  ALL SEEN") +
                        (if (!scanning) "  ·  OFFLINE" else ""),
                    color = cs.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 1.sp
                )
            }
            MiniButton(if (live) "LIVE" else "ALL") { live = !live }
            MiniButton("CLOSE", cs.error) { onClose() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = min(size.width, size.height) / 2f - 30.dp.toPx()
                        val hit = shownNow
                            .map { it to hypot(blipPos(it, c, r).x - tap.x, blipPos(it, c, r).y - tap.y) }
                            .filter { it.second < 30.dp.toPx() }
                            .minByOrNull { it.second }
                        sel = hit?.first?.address
                    }
                }
            ) {
                val c = center
                val r = min(size.width, size.height) / 2f - 30.dp.toPx()
                val dim = if (phase == Phase.IDLE) 0.45f else 1f
                val angle = if (scanning) (frameMs % 3200L) / 3200f * 360f else 0f

                // range rings with their signal level and rough distance
                radarLabel.textSize = 9.sp.toPx()
                radarLabel.textAlign = Paint.Align.LEFT
                for (i in 1..4) {
                    drawCircle(cyan.copy(alpha = 0.28f * dim), r * i / 4f, c, style = Stroke(1.dp.toPx()))
                    val rssi = -35 - 15 * i
                    radarLabel.color = cyan.copy(alpha = 0.6f * dim).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        "$rssi DBM · ~${fmtMeters(approxMeters(rssi))} M",
                        c.x + 4.dp.toPx(), c.y - r * i / 4f - 3.dp.toPx(), radarLabel
                    )
                }
                drawLine(cyan.copy(alpha = 0.18f * dim), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
                drawLine(cyan.copy(alpha = 0.18f * dim), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
                for (i in 0 until 72) {
                    val a = Math.toRadians((i * 5).toDouble())
                    val len = if (i % 6 == 0) 0.05f else 0.025f
                    drawLine(
                        cyan.copy(alpha = 0.45f * dim),
                        Offset(c.x + r * (1f - len) * cos(a).toFloat(), c.y + r * (1f - len) * sin(a).toFloat()),
                        Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 1.dp.toPx()
                    )
                }

                // sweep with a fading trail
                if (scanning) {
                    for (i in 0 until 40) {
                        val a = Math.toRadians((angle - i * 1.6f).toDouble())
                        drawLine(
                            cyan.copy(alpha = 0.5f * (1f - i / 40f)), c,
                            Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 3.dp.toPx()
                        )
                    }
                }
                drawCircle(cyan, 3.dp.toPx(), c)

                // blips: strongest labelled, matched / tracked / selected always labelled
                val strongest = shown.sortedByDescending { it.rssi }.take(5).map { it.address }.toSet()
                for (d in shown) {
                    val p = blipPos(d, c, r)
                    val ageS = ((wall - d.lastSeen) / 1000f).coerceAtLeast(0f)
                    val age = 1f - 0.6f * (ageS / 60f).coerceIn(0f, 1f)
                    val isSel = d.address == sel
                    val tracked = d.address == trackTarget
                    val col = when {
                        d.matched -> AlertRed
                        tracked -> AlertAmber
                        else -> cyan
                    }
                    var glow = 0f
                    if (scanning) {
                        val behind = (((angle - blipDeg(d)) % 360f) + 360f) % 360f
                        val fresh = 1f - behind / 360f
                        glow = fresh * fresh * fresh
                    }
                    val a = (0.35f + 0.65f * (if (scanning) 0.25f + 0.75f * glow else 0.7f)) * age * dim
                    drawCircle(col.copy(alpha = a), (3f + 3f * glow).dp.toPx(), p)
                    if (glow > 0.1f) drawCircle(col.copy(alpha = 0.3f * glow), (8f + 10f * glow).dp.toPx(), p)
                    if (d.matched) {
                        val ping = ((frameMs % 1400L) / 1400f)
                        drawCircle(AlertRed.copy(alpha = 0.7f * (1f - ping)), (6f + 18f * ping).dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                    }
                    if (isSel) bracket(p, Color.White)
                    if (isSel || d.matched || tracked || d.address in strongest) {
                        radarLabel.textSize = 10.sp.toPx()
                        radarLabel.color = col.copy(alpha = 0.9f * age).toArgb()
                        radarLabel.textAlign = if (p.x > c.x) Paint.Align.RIGHT else Paint.Align.LEFT
                        val dx = if (p.x > c.x) -9.dp.toPx() else 9.dp.toPx()
                        drawContext.canvas.nativeCanvas.drawText(nameOf(d).take(18), p.x + dx, p.y + 3.dp.toPx(), radarLabel)
                    }
                }
            }
        }

        // selected contact, or a hint
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (selDev == null) {
                Text(
                    "TAP A BLIP FOR DETAILS",
                    color = cs.onSurface, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "DISTANCE COMES FROM SIGNAL STRENGTH AND IS ROUGH. BLUETOOTH CANNOT TELL DIRECTION, SO A BLIP'S ANGLE IS ARBITRARY BUT STAYS THE SAME FOR EACH DEVICE.",
                    color = cs.onSurfaceVariant, fontSize = 8.sp, letterSpacing = 0.5.sp, lineHeight = 11.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            nameOf(selDev), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            color = if (selDev.matched) AlertRed else cs.onSurface, maxLines = 1
                        )
                        Text(
                            selDev.address + "  ·  ${selDev.rssi} DBM  ·  ~${fmtMeters(approxMeters(selDev.rssi))} M",
                            fontSize = 9.sp, color = cs.onSurfaceVariant, letterSpacing = 0.5.sp
                        )
                    }
                    SignalBars(selDev.rssi, if (selDev.matched) AlertRed else cyan)
                    MiniButton("DETAILS") { onOpen(selDev) }
                }
            }
        }
    }
}

private fun DrawScope.bracket(p: Offset, col: Color) {
    val s = 11.dp.toPx()
    val l = 5.dp.toPx()
    val w = 1.5.dp.toPx()
    fun corner(sx: Float, sy: Float) {
        drawLine(col, Offset(p.x + sx * s, p.y + sy * s), Offset(p.x + sx * (s - l), p.y + sy * s), w)
        drawLine(col, Offset(p.x + sx * s, p.y + sy * s), Offset(p.x + sx * s, p.y + sy * (s - l)), w)
    }
    corner(1f, 1f); corner(-1f, 1f); corner(1f, -1f); corner(-1f, -1f)
}
