package com.example.signalopsirl

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// Segment bits: a (top), b (top right), c (bottom right), d (bottom), e (bottom left), f (top left), g (middle).
private val DIGIT_MASK = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)
private const val DASH_MASK = 0x40

/** A color choice for the speed digits; a null [color] means the app's own accent color. */
private class SpeedColor(val name: String, val color: Color?)

/** Eight hues that are easy to tell apart on a dark screen. Swiping on the speed screen steps through them. */
private val SPEED_COLORS = listOf(
    SpeedColor("CYAN", null),
    SpeedColor("GREEN", Color(0xFF39FF14)),
    SpeedColor("RED", Color(0xFFFF2D2D)),
    SpeedColor("YELLOW", Color(0xFFFFE600)),
    SpeedColor("MAGENTA", Color(0xFFFF2BD6)),
    SpeedColor("BLUE", Color(0xFF3D7BFF)),
    SpeedColor("WHITE", Color(0xFFFFFFFF)),
    SpeedColor("ORANGE", Color(0xFFFF8A00))
)

/** A device counts as "active" when it was heard within this many milliseconds. */
private const val ACTIVE_MS = 10_000L

/**
 * Full-screen digital speedometer with the phone's battery, the scan state and live signal counts around it.
 * [speed] is in mph, or null when there is no GPS speed yet (shows dashes). A tap closes it; a swipe left or right
 * changes the color of the digits ([colorIndex] 0-7, reported through [onColorChange]). The screen stays on while it
 * is showing.
 */
@Composable
fun SpeedScreen(
    speed: Int?,
    phase: Phase,
    devices: Collection<SeenDevice>,
    trackerCount: Int,
    trackerFollowing: Boolean,
    colorIndex: Int,
    onColorChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    val bat = rememberBatteryInfo()
    // The counts depend on the clock, so tick once a second; the same tick measures packets per second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var lastCount = ScanBus.packetCount
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val c = ScanBus.packetCount
            val t = SystemClock.elapsedRealtime()
            pps = ((c - lastCount) * 1000f / (t - lastT).coerceAtLeast(1L)).roundToInt()
            lastCount = c
            lastT = t
            now = System.currentTimeMillis()
        }
    }
    val active = devices.count { now - it.lastSeen <= ACTIVE_MS }
    val hits = devices.count { it.matched && now - it.lastSeen <= 60_000L }

    val n = SPEED_COLORS.size
    val idx = colorIndex.mod(n)
    val base = SPEED_COLORS[idx].color ?: cs.primary
    // With no GPS speed the digits are dimmed, but still show the chosen color so a swipe is visible.
    val lit by animateColorAsState(
        if (speed == null) base.copy(alpha = 0.4f) else base, tween(180), label = "speedColor"
    )
    val idxNow by rememberUpdatedState(idx)
    val changeNow by rememberUpdatedState(onColorChange)
    val closeNow by rememberUpdatedState(onClose)
    val v = speed?.coerceIn(0, 999)
    val masks = if (v == null) intArrayOf(0, DASH_MASK, DASH_MASK) else intArrayOf(
        if (v >= 100) DIGIT_MASK[v / 100] else 0,
        if (v >= 10) DIGIT_MASK[(v / 10) % 10] else 0,
        DIGIT_MASK[v % 10]
    )
    val stateColor = when (phase) {
        Phase.SCANNING -> GameGreen
        Phase.WAITING -> AlertAmber
        Phase.IDLE -> cs.onSurfaceVariant
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val swipePx = 56.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var dragged = false          // moved more than a tap allows: letting go must not close the screen
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.changedToUp()) {
                            if (!dragged) closeNow()
                            break
                        }
                        val d = c.positionChange()
                        dx += d.x
                        dy += d.y
                        if (abs(dx) > slop || abs(dy) > slop) dragged = true
                        // Swipe left = next color, right = previous. Keeping the finger moving steps on through the list.
                        if (abs(dx) >= swipePx && abs(dx) > abs(dy) * 1.5f) {
                            changeNow((idxNow + if (dx < 0f) 1 else -1).mod(n))
                            dx = 0f
                            dy = 0f
                        }
                        if (dragged) c.consume()
                    }
                }
            }
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top bar: scan state on the left, battery on the right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(stateColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    when (phase) {
                        Phase.SCANNING -> "SCANNING"
                        Phase.WAITING -> "STANDBY"
                        Phase.IDLE -> "OFFLINE"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = stateColor
                )
            }
            bat?.let { b ->
                val bc = when {
                    b.charging -> GameGreen
                    b.pct <= 15 -> AlertRed
                    b.pct <= 30 -> AlertAmber
                    else -> cs.onSurface
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (b.charging) Text("\u26A1 ", fontSize = 15.sp, color = bc)
                        Text("${b.pct}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = bc)
                        Spacer(Modifier.width(8.dp))
                        BatteryIcon(b.pct, bc, Modifier.size(width = 34.dp, height = 16.dp))
                    }
                    b.statusText().takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 10.sp, color = if (b.charging) GameGreen else cs.onSurfaceVariant)
                    }
                }
            }
        }

        // The speed, as big as the space between the top bar and the counters allows.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val gap = 12.dp
            // Three digits, each 0.56 as wide as it is tall; 56 dp is kept for the MPH label underneath.
            val digitH = minOf((maxHeight - 56.dp).coerceAtLeast(40.dp), (maxWidth - gap * 2) / 3 / 0.56f)
            val digitW = digitH * 0.56f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    masks.forEach { m -> SevenSeg(m, lit, Modifier.size(digitW, digitH)) }
                }
                Spacer(Modifier.height(10.dp))
                Text("MPH", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = lit.copy(alpha = 0.85f), letterSpacing = 4.sp)
            }
        }

        // Color picker hint: one dot per color, the current one larger, plus its name.
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                SPEED_COLORS.forEachIndexed { i, sc ->
                    val col = sc.color ?: cs.primary
                    Box(
                        Modifier
                            .size(if (i == idx) 12.dp else 7.dp)
                            .background(if (i == idx) col else col.copy(alpha = 0.4f), CircleShape)
                    )
                }
            }
            Text(SPEED_COLORS[idx].name, fontSize = 10.sp, color = base, modifier = Modifier.padding(top = 4.dp))
        }

        // Live signal counts.
        Row(Modifier.fillMaxWidth()) {
            Stat("ACTIVE 10S", "$active", if (active > 0) cs.primary else cs.onSurfaceVariant)
            Stat("TRACKED", "${devices.size}", cs.onSurface)
            Stat("TARGETS", "$hits", if (hits > 0) AlertRed else cs.onSurfaceVariant)
            Stat("PKT/S", "$pps", cs.onSurface)
        }
        if (trackerCount > 0) {
            Text(
                if (trackerFollowing) "\u26A0 TRACKER FOLLOWING YOU" else "TRACKERS NEARBY: $trackerCount",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (trackerFollowing) AlertRed else AlertAmber,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
            )
        }
        Text(
            if (speed == null) "NO GPS SPEED YET. IS A SCAN RUNNING AND LOCATION ON?  \u00B7  SWIPE FOR COLOR  \u00B7  TAP TO CLOSE"
            else "SWIPE LEFT OR RIGHT FOR COLOR  \u00B7  TAP TO CLOSE",
            fontSize = 9.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
        )
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A small battery outline filled to [pct] percent. */
@Composable
private fun BatteryIcon(pct: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val nub = size.width * 0.09f
        val bodyW = size.width - nub
        val sw = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(bodyW - sw, size.height - sw),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(width = sw)
        )
        val pad = sw * 1.8f
        val fillW = ((bodyW - pad * 2f) * pct / 100f).coerceAtLeast(0f)
        drawRect(color = color, topLeft = Offset(pad, pad), size = Size(fillW, size.height - pad * 2f))
        drawRect(color = color, topLeft = Offset(bodyW, size.height * 0.3f), size = Size(nub, size.height * 0.4f))
    }
}

/** One seven-segment digit. Unlit segments stay faintly visible, like an LCD, and lit ones get a soft glow. */
@Composable
private fun SevenSeg(mask: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = w * 0.17f                       // segment thickness
        val gap = t * 0.12f                     // space between neighbouring segments
        val x0 = t / 2f
        val x1 = w - t / 2f
        val y0 = t / 2f
        val ym = h / 2f
        val y2 = h - t / 2f

        fun hSeg(y: Float): Path = Path().apply {
            val l = x0 + gap
            val r = x1 - gap
            moveTo(l, y); lineTo(l + t / 2f, y - t / 2f); lineTo(r - t / 2f, y - t / 2f)
            lineTo(r, y); lineTo(r - t / 2f, y + t / 2f); lineTo(l + t / 2f, y + t / 2f); close()
        }

        fun vSeg(x: Float, top: Float, bottom: Float): Path = Path().apply {
            val a = top + gap
            val b = bottom - gap
            moveTo(x, a); lineTo(x + t / 2f, a + t / 2f); lineTo(x + t / 2f, b - t / 2f)
            lineTo(x, b); lineTo(x - t / 2f, b - t / 2f); lineTo(x - t / 2f, a + t / 2f); close()
        }

        val segs = listOf(
            hSeg(y0),              // a
            vSeg(x1, y0, ym),      // b
            vSeg(x1, ym, y2),      // c
            hSeg(y2),              // d
            vSeg(x0, ym, y2),      // e
            vSeg(x0, y0, ym),      // f
            hSeg(ym)               // g
        )
        segs.forEachIndexed { i, p ->
            if ((mask and (1 shl i)) != 0) {
                drawPath(p, color.copy(alpha = 0.28f), style = Stroke(width = t * 0.55f, join = StrokeJoin.Round))
                drawPath(p, color)
            } else {
                drawPath(p, color.copy(alpha = 0.07f))
            }
        }
    }
}
