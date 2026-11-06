package com.example.signalopsirl

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private val GlitchCyan = Color(0xFF00E5FF)
private val GlitchMagenta = Color(0xFFFF2D9A)

/**
 * CRT look drawn straight onto the screen's content: scanlines, a darkened vignette and a faint bright band
 * drifting down like a refresh line. It is a draw modifier, so no extra layer sits above the UI and it can
 * never get in the way of touches.
 */
@Composable
fun Modifier.crtOverlay(accent: Color): Modifier {
    val t = rememberInfiniteTransition(label = "crt")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "crtPos"
    )
    return this.drawWithContent {
        drawContent()
        val step = 4.dp.toPx()
        val line = Color.Black.copy(alpha = 0.14f)
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.5f),
                center = center,
                radius = max(size.width, size.height) * 0.75f
            )
        )
        val h = 140.dp.toPx()
        val by = -h + (size.height + h) * p
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.07f), Color.Transparent),
                startY = by, endY = by + h
            ),
            topLeft = Offset(0f, by), size = Size(size.width, h)
        )
    }
}

/**
 * Text that now and then "glitches": red and cyan copies split apart and jitter for a moment.
 * With [enabled] false it is just plain bold text.
 */
@Composable
fun GlitchText(
    text: String, color: Color, fontSize: TextUnit, enabled: Boolean,
    modifier: Modifier = Modifier, letterSpacing: TextUnit = 0.sp
) {
    var dx by remember { mutableStateOf(0f) }
    var on by remember { mutableStateOf(false) }
    if (enabled) {
        LaunchedEffect(text) {
            val rnd = Random(System.nanoTime())
            // Open with a short burst, then glitch again every few seconds.
            while (true) {
                repeat(6) {
                    dx = (rnd.nextFloat() - 0.5f) * 10f
                    on = true
                    delay(45)
                }
                on = false
                dx = 0f
                delay(2200L + rnd.nextLong(2500))
            }
        }
    }
    Box(modifier) {
        if (enabled && on) {
            Text(
                text, color = GlitchMagenta.copy(alpha = 0.75f), fontSize = fontSize,
                fontWeight = FontWeight.Bold, letterSpacing = letterSpacing,
                modifier = Modifier.offset(x = (-dx).dp, y = 0.dp)
            )
            Text(
                text, color = GlitchCyan.copy(alpha = 0.75f), fontSize = fontSize,
                fontWeight = FontWeight.Bold, letterSpacing = letterSpacing,
                modifier = Modifier.offset(x = dx.dp, y = (dx / 4f).dp)
            )
        }
        Text(
            text, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold,
            letterSpacing = letterSpacing
        )
    }
}

/** Hex/text that "decrypts" from random characters into the real value once, then shows live updates as-is. */
@Composable
fun DecryptText(
    target: String, animate: Boolean, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier
) {
    var done by remember { mutableStateOf(!animate) }
    var shown by remember { mutableStateOf(target) }
    if (!done) {
        LaunchedEffect(Unit) {
            val rnd = Random(System.nanoTime())
            val frames = 18
            for (f in 0..frames) {
                val real = target.length * f / frames
                val sb = StringBuilder(target.length)
                target.forEachIndexed { i, ch ->
                    sb.append(if (i < real || ch == ' ' || ch == '\n') ch else "0123456789ABCDEF"[rnd.nextInt(16)])
                }
                shown = sb.toString()
                delay(40)
            }
            done = true
        }
    }
    Text(
        if (done) target else shown, color = color, fontSize = fontSize,
        fontFamily = Mono, modifier = modifier
    )
}

/**
 * Compose animations are skipped (they jump straight to the end) when the phone's "animator duration scale" is 0 or
 * "remove animations" is on, which made the celebrations and target lock vanish instantly. Running them inside this
 * context keeps them at normal speed regardless.
 */
object FullSpeed : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** Boot-up text sequence shown once when the app opens. Tap to skip. */
@Composable
fun BootSequence(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lines = remember {
        listOf(
            "SIGNAL OPS IRL // FIELD INTEL UNIT",
            "> RADIO SUBSYSTEM ............ ONLINE",
            "> SIGNATURE DATABASE ......... LOADED",
            "> ADVERTISING DECODER ........ ARMED",
            "> SENSOR PARSERS ............. ARMED",
            "> STANDING BY"
        )
    }
    var typed by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        val acc = ArrayList<String>()
        for (line in lines) {
            acc.add("")
            var n = 0
            while (n < line.length) {
                n = minOf(line.length, n + 2)
                acc[acc.lastIndex] = line.substring(0, n)
                typed = acc.toList()
                delay(6)
            }
            delay(45)
        }
        delay(175)
        onDone()
    }
    Box(
        Modifier.fillMaxSize().background(Bg).clickable { onDone() }.padding(24.dp)
    ) {
        Column(Modifier.align(Alignment.TopStart).padding(top = 64.dp)) {
            typed.forEachIndexed { i, l ->
                Text(
                    l + if (i == typed.lastIndex) "\u2588" else "",
                    color = if (i == 0) cs.primary else cs.secondary,
                    fontFamily = Mono, fontSize = if (i == 0) 15.sp else 12.sp,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        Text(
            "TAP TO SKIP", color = cs.onSurfaceVariant, fontSize = 9.sp, fontFamily = Mono,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        )
    }
}

/** A targeting reticle that closes in on the centre of the screen when an alert fires. */
@Composable
fun TargetLock(trigger: Any?, label: String) {
    val prog = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        prog.snapTo(0f)
        withContext(FullSpeed) { prog.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
    }
    val p = prog.value
    if (p >= 1f) return
    val red = AlertRed
    val fade = if (p > 0.7f) (1f - p) / 0.3f else 1f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension * 0.55f
            val r = maxR * (1f - 0.75f * p)
            val sw = 2.dp.toPx()
            drawCircle(red.copy(alpha = 0.9f * fade), r, c, style = Stroke(sw))
            drawCircle(red.copy(alpha = 0.5f * fade), r * 0.6f, c, style = Stroke(sw))
            val gap = r * 0.25f
            val arm = r * 1.3f
            val line = red.copy(alpha = 0.9f * fade)
            drawLine(line, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), sw)
            drawLine(line, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), sw)
            drawLine(line, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), sw)
            drawLine(line, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), sw)
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30 + p * 120f).toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                drawLine(
                    red.copy(alpha = 0.8f * fade),
                    Offset(c.x + r * 0.92f * ca, c.y + r * 0.92f * sa),
                    Offset(c.x + r * 1.08f * ca, c.y + r * 1.08f * sa), sw
                )
            }
        }
        Column(
            Modifier.offset(y = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "TARGET LOCK", color = red.copy(alpha = fade), fontSize = 16.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp, fontFamily = Mono
            )
            Text(
                label.uppercase(), color = Color.White.copy(alpha = fade), fontSize = 11.sp,
                fontFamily = Mono, maxLines = 1
            )
        }
    }
}
