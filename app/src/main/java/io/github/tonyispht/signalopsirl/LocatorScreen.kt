package io.github.tonyispht.signalopsirl

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen hot/cold locator: a big gauge that fills and changes colour as you get closer, the trend, the signal,
 * and the Geiger-style ticks (the same ones the locator card plays; they speed up as the signal gets stronger).
 */
@Composable
fun LocatorScreen(title: String, running: Boolean, topPad: Dp, onClose: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onClose() }
    val track by ScanBus.track.collectAsState()
    val target by ScanBus.trackTarget.collectAsState()
    val muted by ScanBus.trackMuted.collectAsState()
    val live = track?.takeIf { it.address == target && it.seen }
    val raw = if (live != null) ((live.rssi + 100f) / 65f).coerceIn(0f, 1f) else 0f
    val f by animateFloatAsState(raw, tween(500), label = "loc")
    val heat = heatSpectrum(f)
    val pulseMs = when {
        f > 0.8f -> 300
        f > 0.55f -> 600
        f > 0.3f -> 950
        else -> 1400
    }
    val pulse by rememberInfiniteTransition(label = "locp").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(pulseMs, easing = LinearEasing), RepeatMode.Reverse), label = "locpulse"
    )
    val word = when {
        !running -> "OFFLINE"
        live == null -> if ((track?.lastSeen ?: 0L) > 0L) "SIGNAL LOST" else "SEARCHING"
        f > 0.8f -> "HOT"
        f > 0.55f -> "WARM"
        f > 0.3f -> "COOL"
        else -> "COLD"
    }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(top = topPad).navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("// LOCATOR", fontSize = 13.sp, color = cs.onSurfaceVariant, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            MiniButton("CLOSE") { onClose() }
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(0.5f))
        Box(Modifier.fillMaxWidth(0.86f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.07f
                val pad = stroke / 2 + 4f
                val arc = Size(size.width - pad * 2, size.height - pad * 2)
                val tl = Offset(pad, pad)
                drawArc(cs.outline.copy(alpha = 0.5f), 135f, 270f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (live != null) {
                    drawArc(heat, 135f, 270f * f.coerceAtLeast(0.02f), false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    // pulsing ring: faster when hotter
                    drawCircle(heat.copy(alpha = 0.18f * pulse), radius = size.minDimension * 0.30f * (0.8f + 0.2f * pulse))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(word, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (live != null) heat else cs.tertiary,
                    textAlign = TextAlign.Center)
                if (live != null) {
                    Text(
                        when (live.trend) { 1 -> "▲ WARMER"; -1 -> "▼ COLDER"; else -> "— STEADY" },
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = when (live.trend) { 1 -> cs.secondary; -1 -> cs.error; else -> cs.onSurfaceVariant }
                    )
                    Text("${live.rssi} dBm · ${roughDistance(live.rssi)}", fontSize = 13.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.weight(0.5f))
        Text(
            if (!running) "Start a scan to locate this device."
            else "Walk slowly and turn around. The gauge fills and the ticks speed up as you get closer. " +
                "Walls and bodies weaken the signal, so pause at each spot for a second.",
            fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(if (muted) "UNMUTE TICKS" else "MUTE TICKS", null, cs.secondary, Modifier.weight(1f)) {
                ScanBus.trackMuted.value = !muted
            }
            ActionTile("STOP LOCATING", null, cs.error, Modifier.weight(1f)) { onStop() }
        }
        Spacer(Modifier.height(8.dp))
    }
}
