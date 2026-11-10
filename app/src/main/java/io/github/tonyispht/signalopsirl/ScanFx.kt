package io.github.tonyispht.signalopsirl

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.PointMode
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Main-screen effects: data rain, oscilloscope strip, CRT power on/off, and the Signals list
 * entry / exit animations. Everything here is drawn on a canvas or in a layout modifier, and the
 * callers only show it while MOVIE FX is on.
 */

/** A clock that ticks once per frame while [active]; read it inside a draw block to redraw every frame. */
@Composable
private fun rememberFrameMs(active: Boolean): State<Long> {
    val s = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) withFrameMillis { s.longValue = it }
    }
    return s
}

private fun withAlpha(argb: Int, a: Float): Int =
    (argb and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)

private fun easeOut(x: Float): Float {
    val v = 1f - x.coerceIn(0f, 1f)
    return 1f - v * v * v
}

// ---------------------------------------------------------------------------------------------
// Data rain
// ---------------------------------------------------------------------------------------------

private val HEX = Array(256) { String.format("%02X", it) }

/** One falling packet: the real advertising bytes of a received packet, head byte first. */
private class RainCol(val lane: Int, val bytes: ByteArray, val speed: Float, val tint: Int) {
    var head = 0f
}

/**
 * Faint columns of hex falling over the screen. Every column is one packet the phone just received,
 * so the rain gets denser the busier the air is. Drawn on top of the content at low opacity;
 * it never takes touches.
 */
@Composable
fun DataRain(active: Boolean, modifier: Modifier = Modifier) {
    val frame = rememberFrameMs(active)
    val cols = remember { ArrayList<RainCol>() }
    val clock = remember { longArrayOf(0L) }
    val cs = MaterialTheme.colorScheme
    val cyan = cs.primary.toArgb()
    val green = cs.secondary.toArgb()
    val paint = remember {
        Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE }
    }
    Canvas(modifier) {
        val now = frame.value
        if (!active) {
            cols.clear()
            clock[0] = 0L
            return@Canvas
        }
        val dt = if (clock[0] == 0L) 0f else ((now - clock[0]) / 1000f).coerceIn(0f, 0.05f)
        clock[0] = now

        val textPx = 10.sp.toPx()
        val cellH = textPx * 1.35f
        val laneW = textPx * 1.9f
        val lanes = max(1, (size.width / laneW).toInt())
        val rows = size.height / cellH

        // At most one new packet per frame, so a burst of packets spreads out instead of landing together.
        if (cols.size < 14) {
            val b = ScanBus.pollPacket()
            if (b != null) {
                val free = (0 until lanes).filter { l -> cols.none { it.lane == l && it.head - it.bytes.size < 3f } }
                if (free.isNotEmpty()) {
                    cols.add(
                        RainCol(
                            free[Random.nextInt(free.size)], b,
                            7f + Random.nextFloat() * 7f,
                            if (Random.nextInt(3) == 0) green else cyan
                        )
                    )
                }
            }
        }

        paint.textSize = textPx
        val nc = drawContext.canvas.nativeCanvas
        val iter = cols.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            c.head += c.speed * dt
            if (c.head - c.bytes.size > rows + 1f) {
                iter.remove()
                continue
            }
            val x = c.lane * laneW + (laneW - textPx * 1.2f) / 2f
            for (k in c.bytes.indices) {
                val row = c.head - k
                if (row < 0f) continue
                val y = row * cellH
                if (y > size.height + cellH) continue
                val fade = 1f - k.toFloat() / c.bytes.size
                if (k == 0) {
                    paint.color = withAlpha(0xFFFFFFFF.toInt(), 0.32f)
                } else {
                    paint.color = withAlpha(c.tint, 0.04f + 0.13f * fade * fade)
                }
                nc.drawText(HEX[c.bytes[k].toInt() and 0xFF], x, y, paint)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Oscilloscope strip
// ---------------------------------------------------------------------------------------------

private class ScopeState {
    val buf = FloatArray(180)
    var pos = 0
    var lastTick = 0L
    var lastCount = 0L
    var pol = 1f
    var last = 0f
    var rate = 0f
    var shownRate = -1
    var label = "0 PKT/S"
}

/**
 * A scrolling oscilloscope trace. Each received packet makes a spike (taller for a stronger signal,
 * alternating up and down), then the trace rings out and settles; the text shows packets per second.
 */
@Composable
fun ScopeStrip(phase: Phase, modifier: Modifier = Modifier) {
    val frame = rememberFrameMs(true)
    val st = remember { ScopeState() }
    val cs = MaterialTheme.colorScheme
    val labelPaint = remember {
        Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    }
    val base = when (phase) {
        Phase.SCANNING -> cs.primary
        Phase.WAITING -> cs.tertiary
        Phase.IDLE -> cs.primary
    }
    val strength = when (phase) {
        Phase.SCANNING -> 1f
        Phase.WAITING -> 0.6f
        Phase.IDLE -> 0.35f
    }
    Canvas(modifier) {
        val now = frame.value
        val n = st.buf.size
        val tickMs = 25L // 40 samples per second
        if (st.lastTick == 0L) {
            st.lastTick = now
            st.lastCount = ScanBus.packetCount
        }
        var steps = ((now - st.lastTick) / tickMs).toInt()
        if (steps > 0) {
            if (steps > 8) { st.lastTick = now; steps = 8 } else st.lastTick += steps * tickMs
            val count = ScanBus.packetCount
            val burst = (count - st.lastCount).toInt().coerceAtLeast(0)
            st.lastCount = count
            val noise = when (phase) {
                Phase.SCANNING -> 0.05f
                Phase.WAITING -> 0.02f
                Phase.IDLE -> 0.012f
            }
            for (s in 0 until steps) {
                val b = if (s == 0) burst else 0
                var v = st.last * 0.55f // ringing decay
                if (b > 0 && phase == Phase.SCANNING) {
                    val sig = ((ScanBus.lastRssi + 100) / 65f).coerceIn(0.15f, 1f)
                    val amp = (0.3f + 0.7f * sig) * (0.55f + 0.45f * min(b, 6) / 6f)
                    st.pol = -st.pol
                    v = st.pol * amp
                }
                v += (Random.nextFloat() - 0.5f) * 2f * noise
                st.last = v.coerceIn(-1f, 1f)
                st.buf[st.pos] = st.last
                st.pos = (st.pos + 1) % n
                st.rate = st.rate * 0.97f + b * 40f * 0.03f
            }
            val r = st.rate.roundToInt()
            if (r != st.shownRate) {
                st.shownRate = r
                st.label = "$r PKT/S"
            }
        }

        val w = size.width
        val h = size.height
        val midY = h / 2f
        val amp = midY - 4.dp.toPx()

        // graticule
        val grid = base.copy(alpha = 0.10f)
        for (i in 1..9) drawLine(grid, Offset(w * i / 10f, 0f), Offset(w * i / 10f, h), 1f)
        drawLine(base.copy(alpha = 0.18f), Offset(0f, midY), Offset(w, midY), 1f)
        drawRect(base.copy(alpha = 0.28f), Offset.Zero, Size(w, h), style = Stroke(1f))

        val path = Path()
        for (i in 0 until n) {
            val v = st.buf[(st.pos + i) % n]
            val x = w * i / (n - 1f)
            val y = midY - v * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, base.copy(alpha = 0.16f * strength),
            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, base.copy(alpha = strength),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val headY = midY - st.buf[(st.pos + n - 1) % n] * amp
        drawCircle(base.copy(alpha = strength), 2.5.dp.toPx(), Offset(w, headY))

        labelPaint.textSize = 9.sp.toPx()
        labelPaint.color = withAlpha(base.toArgb(), 0.75f * strength)
        drawContext.canvas.nativeCanvas.drawText(st.label, w - 6.dp.toPx(), 11.dp.toPx(), labelPaint)
    }
}

// ---------------------------------------------------------------------------------------------
// CRT power on / off
// ---------------------------------------------------------------------------------------------

/**
 * [offFirst]: the screen powers down and sits on a black STANDBY screen until it is touched
 * (then it simply returns to the main screen). Otherwise a plain power-on.
 */
class CrtEvent(val offFirst: Boolean, val summary: SessionSummary? = null) {
    /** Length of the timed animation; the standby screen then waits for a touch. */
    val totalMs: Int get() = if (offFirst) OFF_MS + HOLD_MS else ON_MS

    companion object {
        const val OFF_MS = 420
        const val HOLD_MS = 260
        const val ON_MS = 560
    }
}

private val standbyPaint = Paint().apply {
    isAntiAlias = true
    typeface = Typeface.MONOSPACE
    textAlign = Paint.Align.CENTER
    letterSpacing = 0.3f
}

private val summaryPaint = Paint().apply {
    isAntiAlias = true
    typeface = Typeface.MONOSPACE
    textAlign = Paint.Align.CENTER
    letterSpacing = 0.03f
}

/** What happened between pressing INITIATE SCAN and pressing STANDBY. */
data class SessionSummary(
    val durationMs: Long,
    val heard: Int,
    val fresh: Int,
    val targets: Int,
    val alerts: Int,
    val peakRssi: Int?,
    val peakName: String?,
    val xp: Long,
    val kinds: Int = 0,
    val newTypes: Int = 0,
    val rare: Int = 0,
    val models: Int = 0,
    /** Rarest type heard, e.g. "EPIC  HACKER / PENTEST". */
    val bestFind: String? = null,
    val level: Int = 0,
    /** True when a shareable session card is waiting for after the screen wakes. */
    val cardReady: Boolean = false
)

private const val SUMMARY_W = 28

/** One report line. [value] maps count-up progress 0..1 to the text shown; an empty label means a centred, dim line. */
private class SRow(val label: String, val value: (Float) -> String)

private fun summaryRows(s: SessionSummary): List<SRow> {
    fun count(n: Long, prefix: String = "", suffix: String = "") =
        { p: Float -> prefix + (n * p).toLong() + suffix }
    val sec = s.durationMs / 1000
    val rows = mutableListOf(
        SRow("DURATION") { p ->
            val x = (sec * p).toLong()
            "%02d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60)
        },
        SRow("DEVICES HEARD", count(s.heard.toLong())),
        SRow("NEW DEVICES", count(s.fresh.toLong())),
        SRow("KINDS OF DEVICE", count(s.kinds.toLong())),
        SRow("NEW DEX TYPES", count(s.newTypes.toLong())),
        SRow("RARE+ FINDS", count(s.rare.toLong())),
        SRow("MODELS SPOTTED", count(s.models.toLong())),
        SRow("TARGETS FOUND", count(s.targets.toLong())),
        SRow("ALERTS", count(s.alerts.toLong())),
        SRow("PEAK SIGNAL") { p ->
            val r = s.peakRssi
            if (r == null) "--" else "${(-100 + (r + 100) * p).toInt()} DBM"
        }
    )
    val nm = s.peakName
    if (s.peakRssi != null && !nm.isNullOrBlank()) {
        val name = nm.take(SUMMARY_W - 4)
        rows += SRow("") { p -> name.take((name.length * p).toInt()) }
    }
    s.bestFind?.let { bf ->
        val t = bf.take(SUMMARY_W)
        rows += SRow("BEST FIND") { "" }
        rows += SRow("") { p -> t.take((t.length * p).toInt()) }
    }
    rows += SRow("XP EARNED", count(s.xp, prefix = "+"))
    if (s.level > 0) rows += SRow("OPERATOR LEVEL", count(s.level.toLong()))
    return rows
}

private fun rowText(label: String, value: String): String =
    if (label.isEmpty()) value else label + " ".repeat(maxOf(1, SUMMARY_W - label.length - value.length)) + value

/** How long the boot-back (ripple + LINK RESTORED) lasts after the screen is touched. */
private const val WAKE_MS = 800L

/** A snow-noise burst plays first, then the ripple opens. */
private const val STATIC_MS = 230L

@Composable
fun CrtPowerOverlay(ev: CrtEvent, sound: Boolean, onDone: () -> Unit) {
    val p = remember(ev) { Animatable(0f) }
    var waiting by remember(ev) { mutableStateOf(false) }
    // Frame clock for the standby screen's little animations (grain, scanlines, cursor, typing).
    var now by remember(ev) { mutableLongStateOf(0L) }
    var t0 by remember(ev) { mutableLongStateOf(0L) }
    // Boot-back: where the screen was touched and when (frame-clock ms; 0 = not touched yet).
    var wakeAt by remember(ev) { mutableLongStateOf(0L) }
    var wakeX by remember(ev) { mutableFloatStateOf(0f) }
    var wakeY by remember(ev) { mutableFloatStateOf(0f) }
    LaunchedEffect(ev) {
        if (sound) { if (ev.offFirst) AlertEffects.playCrtOff() else AlertEffects.playCrtOn() }
        if (ev.offFirst) launch {
            while (true) withFrameMillis { f -> if (t0 == 0L) t0 = f; now = f }
        }
        withContext(FullSpeed) { p.animateTo(1f, tween(ev.totalMs, easing = LinearEasing)) }
        if (ev.offFirst) waiting = true else onDone()
    }
    LaunchedEffect(wakeAt) {
        if (wakeAt != 0L) {
            delay(STATIC_MS + WAKE_MS)
            onDone()
        }
    }
    val rows = remember(ev) { ev.summary?.let { summaryRows(it) } ?: emptyList() }
    val cardReady = ev.summary?.cardReady == true
    val tint = MaterialTheme.colorScheme.primary
    // While standing by, the overlay swallows touches; the first touch wakes the screen.
    val touch = if (ev.offFirst) {
        Modifier.pointerInput(ev, waiting, wakeAt != 0L) {
            if (waiting && wakeAt == 0L) awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val down = e.changes.firstOrNull { it.pressed }
                    if (down != null) {
                        e.changes.forEach { it.consume() }
                        if (sound) AlertEffects.playCrtOn()
                        wakeX = down.position.x
                        wakeY = down.position.y
                        wakeAt = now.coerceAtLeast(1L)
                        break
                    }
                }
            } else awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    } else Modifier
    // Offscreen layer so the wake ripple can punch a transparent hole through the black standby screen.
    val layer = if (ev.offFirst) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier
    Canvas(Modifier.fillMaxSize().then(layer).then(touch)) {
        val t = p.value * ev.totalMs
        if (!ev.offFirst) {
            drawPowerOn(t / CrtEvent.ON_MS, tint)
        } else if (t < CrtEvent.OFF_MS) {
            drawPowerOff(t / CrtEvent.OFF_MS, tint)
        } else {
            drawStandby(now, if (t0 == 0L) 0L else now - t0 - CrtEvent.OFF_MS, rows, waiting, tint, cardReady)
            if (wakeAt != 0L) {
                val e = (now - wakeAt).coerceAtLeast(0L)
                if (e < STATIC_MS) drawStaticBurst(e, now, tint)
                else drawWake(((e - STATIC_MS).toFloat() / WAKE_MS).coerceIn(0f, 1f), Offset(wakeX, wakeY), tint)
            }
        }
    }
}

/** Snow-noise flash over the standby screen: dense at first, thinning out as it ends. */
private fun DrawScope.drawStaticBurst(e: Long, now: Long, tint: Color) {
    val w = size.width
    val h = size.height
    val q = e.toFloat() / STATIC_MS
    val rnd = Random((now / 16).toInt())
    val cell = 5.dp.toPx()
    val density = 0.9f * (1f - q) + 0.12f
    val white = ArrayList<Offset>()
    val grey = ArrayList<Offset>()
    val glow = ArrayList<Offset>()
    var y = 0f
    while (y < h) {
        var x = 0f
        while (x < w) {
            if (rnd.nextFloat() < density) {
                val r = rnd.nextFloat()
                val o = Offset(x + cell / 2f, y + cell / 2f)
                if (r < 0.35f) white.add(o) else if (r < 0.8f) grey.add(o) else glow.add(o)
            }
            x += cell
        }
        y += cell
    }
    drawRect(Bg.copy(alpha = 0.55f * (1f - q)))
    drawPoints(grey, PointMode.Points, Color(0xFF8A9096).copy(alpha = 0.75f), cell, StrokeCap.Square)
    drawPoints(glow, PointMode.Points, tint.copy(alpha = 0.8f), cell, StrokeCap.Square)
    drawPoints(white, PointMode.Points, Color.White.copy(alpha = 0.9f), cell, StrokeCap.Square)
    // rolling tear bars
    repeat(3) {
        val by = rnd.nextFloat() * h
        drawRect(Color.White.copy(alpha = 0.25f * (1f - q)), Offset(0f, by), Size(w, (4 + rnd.nextInt(14)).dp.toPx()))
    }
    // opening flash
    if (e < 45L) drawRect(Color.White.copy(alpha = 0.4f * (1f - e / 45f)))
}

/** Boot-back: a ripple opens from the touch point, revealing the main screen, with LINK RESTORED flashing over it. */
private fun DrawScope.drawWake(q: Float, at: Offset, tint: Color) {
    val w = size.width
    val h = size.height
    val far = max(max(at.x, w - at.x), max(at.y, h - at.y))
    val reach = kotlin.math.hypot(far, far) * 1.05f
    val r = reach * easeOut(q)
    // the hole: everything inside the ripple is the live screen again
    drawCircle(Color.Black, r, at, blendMode = BlendMode.Clear)
    // rings riding the wavefront
    val fade = (1f - q).coerceIn(0f, 1f)
    drawCircle(tint.copy(alpha = 0.85f * fade), r, at, style = Stroke(3.dp.toPx()))
    drawCircle(tint.copy(alpha = 0.35f * fade), r * 0.82f, at, style = Stroke(1.5.dp.toPx()))
    drawCircle(tint.copy(alpha = 0.15f * fade), r * 0.64f, at, style = Stroke(1.dp.toPx()))
    // LINK RESTORED, flickering out
    if (q < 0.75f) {
        val a = (1f - q / 0.75f)
        val flick = if (((q * 1000f) / 50f).toInt() % 4 == 0) 0.4f else 1f
        standbyPaint.textSize = 15.sp.toPx()
        val tw = standbyPaint.measureText("LINK RESTORED")
        drawRect(Bg.copy(alpha = 0.7f * a), Offset(w / 2f - tw / 2f - 14.dp.toPx(), h * 0.42f - 24.dp.toPx()),
            Size(tw + 28.dp.toPx(), 36.dp.toPx()))
        standbyPaint.color = withAlpha(tint.toArgb(), a * flick)
        drawContext.canvas.nativeCanvas.drawText("LINK RESTORED", w / 2f, h * 0.42f, standbyPaint)
    }
}

/** The black STANDBY screen: grain, scanlines, a slow roll bar, a blinking cursor and the session report typing in. */
private fun DrawScope.drawStandby(now: Long, k: Long, rows: List<SRow>, waiting: Boolean, tint: Color, cardReady: Boolean = false) {
    val w = size.width
    val h = size.height
    drawRect(Bg)

    // faint scanlines
    val step = 3.dp.toPx()
    val line = tint.copy(alpha = 0.035f)
    var y = 0f
    while (y < h) { drawRect(line, Offset(0f, y), Size(w, 1.dp.toPx())); y += step }

    // slow rolling brightness bar
    val period = 5000L
    val bandH = 70.dp.toPx()
    val by = ((now % period).toFloat() / period) * (h + bandH) - bandH
    drawRect(tint.copy(alpha = 0.05f), Offset(0f, by), Size(w, bandH))

    // phosphor grain + the odd glitch line
    val rnd = Random((now / 90).toInt())
    repeat(26) {
        drawRect(
            tint.copy(alpha = 0.08f + rnd.nextFloat() * 0.14f),
            Offset(rnd.nextFloat() * w, rnd.nextFloat() * h), Size(1.5.dp.toPx(), 1.5.dp.toPx())
        )
    }
    if (now % 7000 < 70) drawRect(tint.copy(alpha = 0.35f), Offset(0f, rnd.nextFloat() * h), Size(w, 2.dp.toPx()))

    // layout: title above a fixed-height report block, whole thing vertically centred
    // line height shrinks a little for long reports so everything fits between STANDBY and the wake hint
    val lh = minOf(18.dp.toPx(), (h - 170.dp.toPx()) / (rows.size + 3).coerceAtLeast(1))
    val blockH = if (rows.isEmpty()) 0f else (rows.size + 2) * lh
    val titleY = h / 2f - blockH / 2f
    val kk = k.coerceAtLeast(0L)

    // STANDBY flickers on, then breathes
    val flicker = kk < CrtEvent.HOLD_MS && ((kk / 55f).toInt() % 3) == 0
    val breathe = 0.78f + 0.17f * sin(now / 700f)
    standbyPaint.textSize = 14.sp.toPx()
    standbyPaint.color = withAlpha(tint.toArgb(), if (flicker) 0.2f else breathe)
    drawContext.canvas.nativeCanvas.drawText("STANDBY", w / 2f, titleY, standbyPaint)
    if ((now / 530) % 2 == 0L) {
        val cx = w / 2f + standbyPaint.measureText("STANDBY") / 2f + 6.dp.toPx()
        drawRect(tint.copy(alpha = 0.85f), Offset(cx, titleY - 11.dp.toPx()), Size(7.dp.toPx(), 12.dp.toPx()))
    }

    // the session report types in line by line
    if (rows.isNotEmpty()) {
        val shown = ((kk - 250) / 150).toInt().coerceIn(0, rows.size + 1)
        summaryPaint.textSize = 11.sp.toPx()
        val top = titleY + 34.dp.toPx()
        if (shown >= 1) {
            summaryPaint.color = withAlpha(tint.toArgb(), 0.55f)
            drawContext.canvas.nativeCanvas.drawText("- SESSION REPORT -", w / 2f, top, summaryPaint)
        }
        rows.forEachIndexed { i, row ->
            val appear = 250L + 150L * (i + 2)
            if (kk >= appear) {
                // numbers spin up from zero after the line appears
                val p = easeOut(((kk - appear) / 700f).coerceIn(0f, 1f))
                summaryPaint.color = withAlpha(tint.toArgb(), if (row.label.isEmpty()) 0.5f else 0.85f)
                drawContext.canvas.nativeCanvas.drawText(rowText(row.label, row.value(p)), w / 2f, top + (i + 1.4f) * lh, summaryPaint)
            }
        }
    }

    // pulsing wake hint
    if (waiting) {
        summaryPaint.textSize = 10.sp.toPx()
        summaryPaint.color = withAlpha(tint.toArgb(), 0.3f + 0.3f * (0.5f + 0.5f * sin(now / 450f)))
        drawContext.canvas.nativeCanvas.drawText(
            if (cardReady) "TOUCH TO WAKE  \u00B7  SESSION CARD READY" else "TOUCH TO WAKE", w / 2f, h - 48.dp.toPx(), summaryPaint
        )
    }
}

private fun DrawScope.glowLine(cy: Float, x0: Float, x1: Float, thin: Float, tint: Color) {
    drawRect(tint.copy(alpha = 0.25f), Offset(x0, cy - thin * 3f), Size(max(0f, x1 - x0), thin * 6f))
    drawRect(Color.White, Offset(x0, cy - thin / 2f), Size(max(0f, x1 - x0), thin))
}

/** The lit part of the screen is a horizontal band of height [ap]; everything above and below is black. */
private fun DrawScope.drawAperture(ap: Float, flash: Float, tint: Color) {
    val w = size.width
    val h = size.height
    val top = (h - ap) / 2f
    val bot = top + ap
    drawRect(Bg, Offset.Zero, Size(w, top))
    drawRect(Bg, Offset(0f, bot), Size(w, h - bot))
    drawRect(Color.White.copy(alpha = flash.coerceIn(0f, 1f)), Offset(0f, top), Size(w, ap))
    val edge = 3.dp.toPx()
    drawRect(tint.copy(alpha = 0.9f), Offset(0f, top - edge / 2f), Size(w, edge))
    drawRect(tint.copy(alpha = 0.9f), Offset(0f, bot - edge / 2f), Size(w, edge))
}

/** q 0..1: a bright line grows across, opens into the picture, and a white flash settles. */
private fun DrawScope.drawPowerOn(q: Float, tint: Color) {
    val w = size.width
    val h = size.height
    val thin = 3.dp.toPx()
    when {
        q < 0.22f -> {
            drawRect(Bg)
            val lw = w * easeOut(q / 0.22f)
            glowLine(h / 2f, (w - lw) / 2f, (w + lw) / 2f, thin, tint)
        }
        q < 0.78f -> {
            val x = (q - 0.22f) / 0.56f
            drawAperture(max(thin, h * easeOut(x)), 0.55f * (1f - x), tint)
        }
        else -> {
            val a = 0.28f * (1f - (q - 0.78f) / 0.22f)
            drawRect(Color.White.copy(alpha = a.coerceIn(0f, 1f)))
        }
    }
}

/** q 0..1: the picture brightens and squashes to a line, the line shrinks to a dot, the dot fades. */
private fun DrawScope.drawPowerOff(q: Float, tint: Color) {
    val w = size.width
    val h = size.height
    val thin = 3.dp.toPx()
    when {
        q < 0.6f -> {
            val x = q / 0.6f
            drawAperture(max(thin, h * (1f - x * x)), 0.15f + 0.5f * x, tint)
        }
        q < 0.85f -> {
            drawRect(Bg)
            val lw = max(thin * 2f, w * (1f - easeOut((q - 0.6f) / 0.25f)))
            glowLine(h / 2f, (w - lw) / 2f, (w + lw) / 2f, thin, tint)
        }
        else -> {
            drawRect(Bg)
            val a = (1f - (q - 0.85f) / 0.15f).coerceIn(0f, 1f)
            val c = Offset(w / 2f, h / 2f)
            drawCircle(tint.copy(alpha = 0.3f * a), thin * 3f, c)
            drawCircle(Color.White.copy(alpha = a), thin * 1.2f, c)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Signals list: entry and exit
// ---------------------------------------------------------------------------------------------

const val ENTRY_MS = 520
private const val GHOST_MS = 700

/**
 * New-device entrance: the row grows open, then a bright edge wipes across and reveals it left to right.
 * [progress] runs 0..1; at 1 the modifier does nothing.
 */
fun Modifier.scanEntry(progress: () -> Float): Modifier = this
    .layout { m, c ->
        val pl = m.measure(c)
        val p = progress()
        val f = if (p >= 1f) 1f else FastOutSlowInEasing.transform((p / 0.35f).coerceIn(0f, 1f))
        layout(pl.width, (pl.height * f).roundToInt()) { pl.placeRelative(0, 0) }
    }
    .drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
        } else {
            val rx = ((p - 0.15f) / 0.7f).coerceIn(0f, 1f)
            val x = size.width * rx
            clipRect(0f, 0f, x, size.height) { this@drawWithContent.drawContent() }
            if (rx in 0.001f..0.999f) {
                drawRect(Color.White.copy(alpha = 0.85f * (1f - rx * 0.5f)), Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
    }

/** What the Signals list remembers about a row so it can be replayed as it disappears. */
class GhostInfo(val title: String, val color: Color)

/** A row that has left the list and is dissolving out. [index] is where it used to sit. */
class RowGhost(val key: Long, val index: Int, val title: String, val color: Color)

/** The visible rows with the dissolving ghosts put back at the positions they left. */
fun mergeGhosts(rows: List<ShownDevice>, ghosts: List<RowGhost>): List<Any> {
    if (ghosts.isEmpty()) return rows
    val out = ArrayList<Any>(rows.size + ghosts.size)
    out.addAll(rows)
    ghosts.sortedBy { it.index }.forEach { out.add(it.index.coerceAtMost(out.size), it) }
    return out
}

/** The row's name breaks up into scan-line bars and fades out, then the gap closes. */
@Composable
fun GhostRow(g: RowGhost, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        withContext(FullSpeed) { p.animateTo(1f, tween(GHOST_MS, easing = LinearEasing)) }
        onDone()
    }
    val cs = MaterialTheme.colorScheme
    val bars = remember {
        val r = Random(g.key)
        List(18) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.6f, 0.15f + r.nextFloat() * 0.5f) }
    }
    val cover = cs.surface
    Row(
        Modifier
            .layout { m, c ->
                val pl = m.measure(c)
                val f = ((p.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
                layout(pl.width, (pl.height * (1f - f)).roundToInt()) { pl.placeRelative(0, 0) }
            }
            .clipToBounds()
            .graphicsLayer { alpha = (1f - p.value * p.value).coerceIn(0f, 1f) }
            .drawWithContent {
                drawContent()
                val shown = (p.value / 0.8f * bars.size).toInt().coerceAtMost(bars.size)
                for (i in 0 until shown) {
                    val b = bars[i]
                    drawRect(
                        cover,
                        Offset(size.width * b[1], size.height * b[0]),
                        Size(size.width * b[2], 3.dp.toPx())
                    )
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            g.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = g.color, modifier = Modifier.weight(1f)
        )
        Text("LOST", fontSize = 9.sp, fontFamily = Mono, color = cs.error)
    }
}


// ---------------------------------------------------------------------------------------------
// Curved glass
// ---------------------------------------------------------------------------------------------

/** A curved-tube look over everything: darkened edges, rounded bezel corners and a faint glare. Never takes touches. */
@Composable
fun CurvedGlass() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val corner = 30.dp.toPx()

        // edges fall off in brightness, strongest in the corners
        drawRect(
            Brush.radialGradient(
                0.0f to Color.Transparent,
                0.62f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.55f),
                center = Offset(w / 2f, h / 2f),
                radius = kotlin.math.hypot(w / 2f, h / 2f)
            )
        )

        // soft inner shadow hugging the rounded edge: the glass bulging away from the bezel
        for ((width, alpha) in listOf(26.dp to 0.10f, 16.dp to 0.12f, 8.dp to 0.16f, 3.dp to 0.25f)) {
            val sw = width.toPx()
            drawRoundRect(
                Color.Black.copy(alpha = alpha), Offset(sw / 2f, sw / 2f), Size(w - sw, h - sw),
                CornerRadius(corner), style = Stroke(sw)
            )
        }

        // black bezel outside the rounded corners
        val bezel = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
            addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner)))
        }
        drawPath(bezel, Color.Black)

        // glare on the upper left of the glass
        drawRect(
            Brush.linearGradient(
                0.0f to Color.White.copy(alpha = 0.07f),
                0.35f to Color.Transparent,
                start = Offset(0f, 0f), end = Offset(w * 0.8f, h * 0.55f)
            )
        )
    }
}
