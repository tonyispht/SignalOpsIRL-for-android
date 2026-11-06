package com.example.signalopsirl

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal val GameGold = Color(0xFFFFC857)
internal val GameMagenta = Color(0xFFFF2D9A)
internal val GameCyan = Color(0xFF00E5FF)
internal val GameGreen = Color(0xFF39FF88)
internal val GameOrange = Color(0xFFFF8A3D)
internal val GameViolet = Color(0xFF7C4DFF)

internal const val DEX = "\uD83D\uDCD6"
internal const val GIFT = "\uD83C\uDF81"

internal const val FLAME = "\uD83D\uDD25"
private const val TROPHY = "\uD83C\uDFC6"

// ---- easing ----------------------------------------------------------------------------------

private fun easeOut(x: Float): Float {
    val v = 1f - x.coerceIn(0f, 1f)
    return 1f - v * v * v
}

/** Overshoots 1 on the way in, then settles: the "pop" of a slammed-in number or medal. */
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x.coerceIn(0f, 1f) - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

// ---- particles -------------------------------------------------------------------------------

private class Spark(
    val angle: Float, val speed: Float, val size: Float,
    val delay: Float, val life: Float, val gravity: Float, val color: Color
)

private val SparkColors = listOf(GameGold, GameCyan, GameMagenta, GameGreen, Color.White)

private fun makeSparks(n: Int, seed: Long): List<Spark> {
    val r = Random(seed)
    return List(n) {
        Spark(
            angle = r.nextFloat() * 6.2831855f,
            speed = 0.18f + r.nextFloat() * 0.5f,
            size = 2f + r.nextFloat() * 4f,
            delay = r.nextFloat() * 0.12f,
            life = 0.35f + r.nextFloat() * 0.35f,
            gravity = r.nextFloat(),
            color = SparkColors[r.nextInt(SparkColors.size)]
        )
    }
}

/** Draws [sparks] flying out of [origin] at animation time [t] (0..1); [reach] is how far the fastest one travels. */
private fun DrawScope.drawSparks(sparks: List<Spark>, t: Float, origin: Offset, reach: Float, fade: Float) {
    for (s in sparks) {
        val life = (t - s.delay) / s.life
        if (life <= 0f || life >= 1f) continue
        val dist = s.speed * reach * life
        val x = origin.x + cos(s.angle) * dist
        val y = origin.y + sin(s.angle) * dist + s.gravity * reach * 0.5f * life * life
        drawCircle(s.color.copy(alpha = (1f - life) * fade), s.size.dp.toPx() * (1f - 0.5f * life), Offset(x, y))
    }
}

/**
 * One piece of confetti. Positions are worked out from the animation time alone (air drag slows the launch,
 * then the piece flutters down at its own terminal speed), so nothing is allocated or stepped per frame.
 * Distances are in screen heights, times in seconds.
 */
private class Confetto(
    val x0: Float, val y0: Float,
    val vx: Float, val vy: Float,
    val vt: Float, val k: Float,
    val w: Float, val h: Float,
    val spin: Float, val flipF: Float, val rot0: Float,
    val sway: Float, val swayF: Float,
    val delay: Float, val life: Float,
    val color: Color, val round: Boolean
)

private val ConfettiColors = listOf(
    GameGold, GameCyan, GameMagenta, GameGreen, Color.White, GameOrange, GameViolet
)

/**
 * [big] celebrations get a burst from the middle, two cannons from the bottom corners and a rain from the top;
 * small ones only the burst, from the card at the top of the screen.
 */
private fun makeConfetti(n: Int, seed: Long, maxLife: Float, big: Boolean): List<Confetto> {
    val r = Random(seed * 31L + 5L)
    fun rnd(a: Float, b: Float) = a + r.nextFloat() * (b - a)
    fun piece(x0: Float, y0: Float, vx: Float, vy: Float, k: Float, delay: Float): Confetto {
        val streamer = r.nextFloat() < 0.18f
        val round = !streamer && r.nextFloat() < 0.15f
        val w = if (streamer) rnd(2.5f, 4f) else rnd(5f, 9f)
        val h = if (streamer) rnd(16f, 26f) else if (round) w else rnd(8f, 15f)
        return Confetto(
            x0 = x0, y0 = y0, vx = vx, vy = vy, vt = rnd(0.16f, 0.3f), k = k,
            w = w, h = h, spin = rnd(-540f, 540f), flipF = rnd(4f, 9f), rot0 = rnd(0f, 6.2831855f),
            sway = rnd(4f, 22f), swayF = rnd(2f, 5f), delay = delay,
            life = ((maxLife - delay) * rnd(0.8f, 1f)).coerceAtLeast(0.6f),
            color = ConfettiColors[r.nextInt(ConfettiColors.size)], round = round
        )
    }
    return List(n) {
        val roll = r.nextFloat()
        when {
            !big || roll < 0.3f -> {
                val a = rnd(0f, 6.2831855f)
                val sp = rnd(0.35f, 1.15f)
                piece(0.5f, if (big) 0.42f else 0.2f, cos(a) * sp * 0.45f, sin(a) * sp - 0.25f, 2.2f, rnd(0f, 0.08f))
            }
            roll < 0.68f -> {
                val left = r.nextBoolean()
                val a = Math.toRadians((if (left) -65.0 else -115.0) + rnd(-18f, 18f).toDouble()).toFloat()
                val sp = rnd(1.0f, 2.3f)
                piece(if (left) 0f else 1f, 1.02f, cos(a) * sp, sin(a) * sp, 1.6f, rnd(0.05f, 0.35f))
            }
            else -> piece(rnd(0f, 1f), -0.06f, rnd(-0.05f, 0.05f), rnd(0.05f, 0.3f), 1.2f, rnd(0.2f, 1.6f))
        }
    }
}

private fun DrawScope.drawConfetti(list: List<Confetto>, sec: Float) {
    val w = size.width
    val h = size.height
    for (c in list) {
        val s = sec - c.delay
        if (s <= 0f || s >= c.life) continue
        val e = exp(-c.k * s)
        val m = (1f - e) / c.k
        val x = c.x0 * w + c.vx * h * m + sin(s * c.swayF + c.rot0) * c.sway.dp.toPx() * (1f - e)
        val y = c.y0 * h + c.vt * h * s + (c.vy - c.vt) * h * m
        if (x < -40f || x > w + 40f || y > h + 60f) continue
        val col = c.color.copy(alpha = ((c.life - s) / 0.7f).coerceIn(0f, 1f))
        val cw = c.w.dp.toPx()
        val ch = c.h.dp.toPx()
        if (c.round) {
            drawCircle(col, cw / 2f, Offset(x, y))
        } else {
            // The width shrinks and grows as the piece "flips", which is what makes it look like paper.
            val flip = abs(cos(s * c.flipF + c.rot0)).coerceAtLeast(0.2f)
            rotate(c.rot0 * 57.29578f + s * c.spin, Offset(x, y)) {
                drawRect(col, Offset(x - cw * flip / 2f, y - ch / 2f), Size(cw * flip, ch))
            }
        }
    }
}

/** A twinkling four-point star. [x] and [y] are fractions of the screen. */
private class Glint(
    val x: Float, val y: Float, val size: Float, val phase: Float, val period: Float, val color: Color
)

private fun makeGlints(n: Int, seed: Long, yMin: Float, yMax: Float): List<Glint> {
    val r = Random(seed * 17L + 3L)
    return List(n) {
        Glint(
            x = r.nextFloat(),
            y = yMin + r.nextFloat() * (yMax - yMin),
            size = 6f + r.nextFloat() * 12f,
            phase = r.nextFloat(),
            period = 0.7f + r.nextFloat() * 0.9f,
            color = if (r.nextInt(3) == 0) Color.White else SparkColors[r.nextInt(SparkColors.size)]
        )
    }
}

private fun DrawScope.drawGlints(list: List<Glint>, sec: Float, fade: Float) {
    val sw = 1.6.dp.toPx()
    for (g in list) {
        val a = sin((((sec / g.period) + g.phase) % 1f) * 3.1415927f)
        if (a <= 0.03f) continue
        val len = g.size.dp.toPx() * (0.4f + 0.6f * a)
        val cx = g.x * size.width
        val cy = g.y * size.height
        val col = g.color.copy(alpha = a * fade)
        drawLine(col, Offset(cx - len, cy), Offset(cx + len, cy), sw, StrokeCap.Round)
        drawLine(col, Offset(cx, cy - len), Offset(cx, cy + len), sw, StrokeCap.Round)
        val d = len * 0.5f
        val dim = col.copy(alpha = col.alpha * 0.6f)
        drawLine(dim, Offset(cx - d, cy - d), Offset(cx + d, cy + d), sw * 0.7f, StrokeCap.Round)
        drawLine(dim, Offset(cx - d, cy + d), Offset(cx + d, cy - d), sw * 0.7f, StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = a * fade), sw * 1.4f, Offset(cx, cy))
    }
}

// ---- progress bar ----------------------------------------------------------------------------

/**
 * The bar used for XP, quests and achievements. Past 80% it starts to glow and shine, and the glow pulses at
 * the leading edge, so "almost there" is something you can see from across the room.
 * With [animate] false the bar follows [frac] exactly (for callers that animate it themselves).
 */
@Composable
fun GameBar(
    frac: Float,
    modifier: Modifier = Modifier.fillMaxWidth(),
    height: Dp = 6.dp,
    color: Color = GameGold,
    from: Color? = null,
    animate: Boolean = true,
    /** Colour of the empty part; the theme's default is nearly invisible on dark backgrounds. */
    track: Color? = null
) {
    val cs = MaterialTheme.colorScheme
    val f = frac.coerceIn(0f, 1f)
    val anim by animateFloatAsState(f, tween(700), label = "gamebar")
    val shown = if (animate) anim else f
    Box(modifier.height(height).background(track ?: cs.surfaceVariant)) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(from ?: color.copy(alpha = 0.7f), color)))
        )
        if (f >= 0.8f && f < 1f) NearGlow(shown, color)
    }
}

@Composable
private fun NearGlow(fill: Float, color: Color) {
    val inf = rememberInfiniteTransition(label = "near")
    val pulse by inf.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse"
    )
    val sweep by inf.animateFloat(
        -0.25f, 1.25f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * fill
        val half = w * 0.12f
        val sx = w * sweep
        val left = max(0f, sx - half)
        val right = min(w, sx + half)
        if (right > left) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent)),
                Offset(left, 0f), Size(right - left, size.height)
            )
        }
        val r = size.height * (1.2f + pulse)
        val c = Offset(w, size.height / 2f)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.55f * pulse), Color.Transparent), center = c, radius = r),
            r, c
        )
    }
}

// ---- celebrations ----------------------------------------------------------------------------

/** What a celebration shows. [big] ones take over the whole screen; the others are a card that lets taps through. */
private class FxSpec(
    val seed: Long,
    val big: Boolean,
    val accent: Color,
    val kicker: String,
    val glyph: String,
    val title: String,
    val holdMs: Int,
    val confetti: Int,
    val heroNumber: Int? = null,
    val heroFrom: Int = 0,
    val subtitle: String? = null,
    val line3: String? = null,
    val xp: Int = 0,
    val xpLabel: String = "XP",
    val hint: String? = null,
    val glitch: Boolean = false,
    val bar: Boolean = false
)

private fun streakLine(e: GameEvent.StreakUp): String? {
    val parts = ArrayList<String>()
    if (e.bonusPct > 0) parts.add("QUEST XP BONUS +${e.bonusPct}%")
    return if (parts.isEmpty()) null else parts.joinToString("  \u2022  ")
}

private fun specFor(e: GameEvent, rush: Boolean, primary: Color): FxSpec {
    // With more celebrations waiting, each one plays shorter.
    fun hold(ms: Int) = if (rush) (ms * 0.55f).toInt() else ms
    return when (e) {
        is GameEvent.LevelUp -> FxSpec(
            seed = e.id, big = true, accent = GameGold, kicker = "LEVEL UP", glyph = "",
            title = "RANK: ${e.rank}", holdMs = hold(4200), confetti = 200,
            heroNumber = e.level, heroFrom = (e.level - 1).coerceAtLeast(0),
            hint = "TAP TO CONTINUE", glitch = true, bar = true
        )
        is GameEvent.AchievementUnlocked -> {
            val a = e.achievement
            val accent = when {
                a.xp >= 400 -> GameMagenta
                a.xp >= 150 -> GameCyan
                else -> GameGold
            }
            val kicker = when {
                a.xp >= 400 -> "LEGENDARY ACHIEVEMENT"
                a.xp >= 150 -> "RARE ACHIEVEMENT"
                else -> "ACHIEVEMENT UNLOCKED"
            }
            FxSpec(
                seed = e.id, big = true, accent = accent, kicker = kicker, glyph = "\u2605", title = a.name,
                holdMs = hold(3800), confetti = (120 + a.xp / 8).coerceAtMost(240), subtitle = a.desc,
                xp = a.xp, line3 = a.title?.let { "UNLOCKS TITLE: ${Game.titleName(it)}" },
                hint = "TAP TO CONTINUE", glitch = a.xp >= 400
            )
        }
        is GameEvent.TitleUnlocked -> FxSpec(
            seed = e.id, big = true, accent = primary, kicker = "TITLE UNLOCKED", glyph = "\u25C8",
            title = "[ ${e.title.name} ]", holdMs = hold(4800), confetti = 160,
            subtitle = Game.howToUnlock(e.title), hint = "TAP TO EQUIP", glitch = true
        )
        is GameEvent.QuestDone -> FxSpec(
            seed = e.id, big = false, accent = GameGreen, kicker = "QUEST COMPLETE  ${e.index}/${e.total}",
            glyph = "\u2714", title = e.name, holdMs = hold(2600), confetti = 80,
            subtitle = e.desc, xp = e.xp,
            line3 = if (e.bonusPct > 0) "STREAK BONUS +${e.bonusPct}%" else null
        )
        is GameEvent.StreakUp ->
            if (e.milestone) FxSpec(
                seed = e.id, big = true, accent = GameOrange, kicker = "STREAK MILESTONE", glyph = FLAME,
                title = "${e.days}-DAY STREAK", holdMs = hold(4200), confetti = 210,
                subtitle = "${e.days} DAYS PLAYED", line3 = streakLine(e),
                hint = "TAP TO CONTINUE", glitch = true
            ) else FxSpec(
                seed = e.id, big = false, accent = GameOrange, kicker = "STREAK", glyph = FLAME,
                title = "${e.days}-DAY STREAK", holdMs = hold(2600), confetti = 70,
                subtitle = "KEEP IT GOING", line3 = streakLine(e)
            )
        is GameEvent.LoginClaimed ->
            if (e.big) FxSpec(
                seed = e.id, big = true, accent = GameGold, kicker = "LOGIN MILESTONE", glyph = GIFT,
                title = "DAY ${e.day} REWARD", holdMs = hold(4200), confetti = 210,
                subtitle = "LOGIN CALENDAR", xp = e.xp, hint = "TAP TO CONTINUE", glitch = true
            ) else FxSpec(
                seed = e.id, big = false, accent = GameCyan, kicker = "LOGIN REWARD", glyph = GIFT,
                title = "DAY ${e.day} COLLECTED", holdMs = hold(2400), confetti = 70,
                subtitle = "SEE YOU TOMORROW", xp = e.xp
            )
        is GameEvent.DexEntryFound -> FxSpec(
            seed = e.id, big = false, accent = GameViolet, kicker = "NEW DEX ENTRY  \u00B7  ${e.tab}",
            glyph = e.icon.ifEmpty { DEX }, title = e.name, holdMs = hold(2600), confetti = 60,
            subtitle = if (e.total > 0) "SIGNAL DEX  ${e.found}/${e.total}" else "SIGNAL DEX  ${e.found} FOUND",
            xp = e.xp
        )
        is GameEvent.PerfectDay -> FxSpec(
            seed = e.id, big = true, accent = GameGold, kicker = "PERFECT DAY", glyph = TROPHY,
            title = "ALL QUESTS COMPLETE", holdMs = hold(4000), confetti = 220,
            subtitle = "NOTHING LEFT TODAY", xp = e.bonusXp, xpLabel = "XP BONUS",
            hint = "TAP TO CONTINUE", glitch = true
        )
    }
}

/**
 * Plays the waiting celebrations one at a time: level-up, achievement, title, perfect day and streak milestones
 * take over the screen with confetti, sparkles and rays; quests and ordinary streak days are a card at the top.
 */
@Composable
fun GameCelebrations() {
    val queue by Game.pending.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    val cur = queue.firstOrNull() ?: return
    val rush = queue.size > 1
    key(cur.id) {
        LaunchedEffect(Unit) { Game.playFor(cur) }
        val spec = remember { specFor(cur, rush, primary) }
        CelebrationFx(
            spec,
            onTap = {
                if (cur is GameEvent.TitleUnlocked) Game.equip(cur.title.id)
                Game.consume(cur.id)
            },
            onDone = { Game.consume(cur.id) }
        )
    }
    if (queue.size > 1) {
        Box(
            Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 18.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                "SKIP ALL (${queue.size})", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = Mono, letterSpacing = 1.sp,
                modifier = Modifier
                    .clickable { Game.consumeAll() }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun CelebrationFx(spec: FxSpec, onTap: () -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val big = spec.big
    val total = spec.holdMs + 800
    val p = remember { Animatable(0f) }
    val confetti = remember(spec.seed) { makeConfetti(spec.confetti, spec.seed, total / 1000f - 0.35f, big) }
    val glints = remember(spec.seed) {
        if (big) makeGlints(52, spec.seed, 0.02f, 0.98f) else makeGlints(20, spec.seed, 0.06f, 0.34f)
    }
    val sparks = remember(spec.seed) { makeSparks(if (big) 110 else 40, spec.seed + 3L) }
    val gs by Game.state.collectAsState()
    LaunchedEffect(Unit) {
        withContext(FullSpeed) { p.animateTo(1f, tween(total, easing = LinearEasing)) }
        onDone()
    }
    val ms = p.value * total
    val sec = ms / 1000f
    val inP = (ms / 380f).coerceIn(0f, 1f)
    val outP = ((total - ms) / 380f).coerceIn(0f, 1f)
    val fade = min(inP, outP)
    val pop = easeOutBack(ms / 560f)
    val shownXp = (spec.xp * easeOut((ms - 450f) / 1000f)).toInt()

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (big) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { onTap() } else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = if (big) center else Offset(size.width / 2f, size.height * 0.2f)
            if (big) {
                drawRect(Color.Black.copy(alpha = 0.76f * fade))
                if (ms < 160f) drawRect(Color.White.copy(alpha = (1f - ms / 160f) * 0.85f))
            }
            val glowR = size.minDimension * (if (big) 0.95f else 0.5f) * (0.9f + 0.1f * sin(sec * 3f))
            drawCircle(
                Brush.radialGradient(
                    listOf(spec.accent.copy(alpha = (if (big) 0.5f else 0.28f) * fade), Color.Transparent),
                    center = c, radius = glowR
                ),
                glowR, c
            )
            if (big) {
                val rayLen = size.maxDimension * 0.7f * easeOut(ms / 700f)
                for (i in 0 until 24) {
                    val a = Math.toRadians(i * 15.0 + sec * 22.0)
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    drawLine(
                        spec.accent.copy(alpha = 0.2f * fade),
                        Offset(c.x + dx * 60.dp.toPx(), c.y + dy * 60.dp.toPx()),
                        Offset(c.x + dx * rayLen, c.y + dy * rayLen), 3.dp.toPx()
                    )
                }
                val reach = size.maxDimension * 0.6f
                for (k in 0 until 4) {
                    val rp = (sec - k * 0.25f) / 1.4f
                    if (rp > 0f && rp < 1f) {
                        drawCircle(
                            spec.accent.copy(alpha = (1f - rp) * 0.8f), reach * rp, c,
                            style = Stroke(2.dp.toPx() + 6.dp.toPx() * (1f - rp))
                        )
                    }
                }
            }
            drawConfetti(confetti, sec)
            drawSparks(sparks, (sec / 1.4f).coerceIn(0f, 1f), c, size.maxDimension * (if (big) 0.5f else 0.25f), 1f)
            drawGlints(glints, sec, if (big) fade else 1f)
        }

        if (big) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.graphicsLayer { alpha = fade }) {
                    GlitchText(spec.kicker, spec.accent, 15.sp, true, letterSpacing = 3.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.graphicsLayer {
                        scaleX = pop
                        scaleY = pop
                        rotationY = (1f - inP) * 540f
                        cameraDistance = 14f * density
                        alpha = fade
                    },
                    contentAlignment = Alignment.Center
                ) {
                    val hn = spec.heroNumber
                    if (hn != null) {
                        // The old level flips to the new one with a bump, like a slot machine landing.
                        val shownN = if (ms < 420f) spec.heroFrom else hn
                        val bump = if (ms >= 420f) 1f + 0.3f * exp(-(ms - 420f) / 140f) else 1f
                        Text(
                            "$shownN", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                            fontFamily = Mono,
                            modifier = Modifier.graphicsLayer { scaleX = bump; scaleY = bump }
                        )
                    } else {
                        Text(spec.glyph, color = spec.accent, fontSize = 110.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.graphicsLayer { alpha = fade }) {
                    GlitchText(spec.title, Color.White, 22.sp, spec.glitch, letterSpacing = 1.sp)
                }
                spec.subtitle?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it, color = Color.White.copy(alpha = 0.75f * fade), fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
                if (spec.xp > 0) {
                    Spacer(Modifier.height(14.dp))
                    val xpPulse = 1f + 0.06f * sin(sec * 9f)
                    Text(
                        "+$shownXp ${spec.xpLabel}", color = GameGold.copy(alpha = fade), fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.graphicsLayer { scaleX = xpPulse; scaleY = xpPulse }
                    )
                }
                if (spec.bar) {
                    Spacer(Modifier.height(14.dp))
                    val target = if (gs.xpForLevel > 0) {
                        (gs.xpIntoLevel.toFloat() / gs.xpForLevel).coerceIn(0f, 1f)
                    } else 1f
                    val bf = target * easeOut((ms - 800f) / 1100f)
                    Box(Modifier.graphicsLayer { alpha = fade }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            GameBar(bf, Modifier.width(260.dp), 10.dp, GameGold, animate = false)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (gs.level >= Game.MAX_LEVEL) "MAX LEVEL"
                                else "%,d XP TO LEVEL %d".format(gs.xpForLevel - gs.xpIntoLevel, gs.level + 1),
                                color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Mono
                            )
                        }
                    }
                }
                spec.line3?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it, color = spec.accent.copy(alpha = fade), fontSize = 11.sp, letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    )
                }
                spec.hint?.let {
                    Spacer(Modifier.height(18.dp))
                    val a = if (ms > 1200f) (0.35f + 0.25f * sin(sec * 5f)).coerceIn(0f, 1f) else 0f
                    Text(
                        it, color = Color.White.copy(alpha = a * fade), fontSize = 10.sp, fontFamily = Mono,
                        letterSpacing = 1.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().statusBarsPadding().padding(top = 92.dp, start = 12.dp, end = 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                HudCard(
                    modifier = Modifier
                        .graphicsLayer {
                            val sc = 0.7f + 0.3f * pop
                            scaleX = sc
                            scaleY = sc
                            alpha = fade
                            translationY = -(1f - inP) * 40.dp.toPx()
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onTap()
                        },
                    container = Color(0xFF0B0F14), border = spec.accent
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Text(
                                spec.glyph, color = spec.accent, fontSize = 38.sp,
                                modifier = Modifier.graphicsLayer {
                                    rotationY = (1f - inP) * 360f
                                    cameraDistance = 14f * density
                                }
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                spec.kicker, color = spec.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Text(spec.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            spec.subtitle?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 10.sp) }
                            spec.line3?.let {
                                Text(it, color = spec.accent, fontSize = 9.sp, letterSpacing = 1.sp)
                            }
                        }
                        if (spec.xp > 0) {
                            Text("+$shownXp XP", color = GameGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ---- status strip ----------------------------------------------------------------------------

/**
 * One compact card for the top of the main screen (two thin lines):
 * line 1 is level, title and the XP bar (tap for the profile);
 * line 2 is the streak, the three quest bars, what is closest to done, and the login gift button
 * (tap the line for the DAILY tab, the gift for the login calendar).
 */
@Composable
fun StatusStrip(
    state: GameState,
    d: DailyState,
    login: LoginState,
    onProfile: () -> Unit,
    onDaily: () -> Unit,
    onLogin: () -> Unit,
    dexNew: Int = 0,
    onDex: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    // Starts the new day's quests (and opens the next login reward) by itself when the clock passes the reset time.
    LaunchedEffect(Unit) {
        while (true) {
            Daily.refresh()
            LoginCalendar.refresh()
            delay(30_000L)
        }
    }
    val frac = if (state.xpForLevel > 0) (state.xpIntoLevel.toFloat() / state.xpForLevel).coerceIn(0f, 1f) else 1f
    val gain by Game.gain.collectAsState()
    var recent by remember { mutableStateOf<XpGain?>(null) }
    LaunchedEffect(gain?.seq) {
        recent = gain
        if (gain != null) {
            delay(1800)
            recent = null
        }
    }
    val remaining = (state.xpForLevel - state.xpIntoLevel).coerceAtLeast(0L)
    val near = state.level < Game.MAX_LEVEL && frac >= 0.8f
    val hasNew = state.newAchievements.isNotEmpty() || state.newTitles.isNotEmpty()

    val doneCount = d.quests.count { it.done }
    val next = d.quests.filter { !it.done }.maxByOrNull { it.progress.toFloat() / it.def.goal }
    val almost = next != null && next.progress.toFloat() / next.def.goal >= 0.8f
    val nextTxt = next?.let { "${it.def.name} ${it.progress}/${it.def.goal}" }
    val line = when {
        d.quests.isEmpty() -> ""
        d.perfect -> "PERFECT DAY! SEE YOU TOMORROW"
        nextTxt != null -> (if (almost) "ALMOST! " else "NEXT UP: ") + nextTxt
        else -> "ALL QUESTS DONE"
    }

    HudCard(border = if (near || d.perfect) GameGold else cs.outline) {
        // line 1: level, title, XP
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onProfile).padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LV ${state.level}", color = GameGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "[ ${Game.titleName(state.equipped)} ]", color = cs.primary, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f), maxLines = 1
                    )
                    val g = recent
                    if (g != null) {
                        Text("+${g.amount} XP  ${g.why}", color = GameGold, fontSize = 9.sp, maxLines = 1)
                    } else if (near) {
                        Text(
                            if (frac >= 0.95f) "SO CLOSE! %,d XP".format(remaining)
                            else "ALMOST! %,d XP TO LV ${state.level + 1}".format(remaining),
                            color = GameGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                        )
                    } else {
                        Text(
                            "%,d / %,d XP".format(state.xpIntoLevel, state.xpForLevel),
                            color = cs.onSurfaceVariant, fontSize = 9.sp, maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                GameBar(frac, height = 5.dp, color = GameGold, from = cs.primary)
            }
            if (hasNew) {
                Spacer(Modifier.width(8.dp))
                Text("NEW", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(GameGold).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        // line 2: streak, quests, login gift
        Row(
            Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (d.quests.isNotEmpty()) {
                Row(
                    Modifier.weight(1f).clickable(onClick = onDaily).padding(start = 10.dp, top = 3.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(FLAME, fontSize = 12.sp)
                    Text(" ${d.streak}", color = GameOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.width(54.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (q in d.quests) {
                            GameBar(
                                q.progress.toFloat() / q.def.goal, Modifier.weight(1f), 4.dp,
                                if (q.done) GameGreen else GameGold,
                                track = cs.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (almost || d.perfect) GameGold else cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$doneCount/${d.quests.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (d.perfect) GameGold else cs.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            DexButton(dexNew, onDex)
            LoginGiftButton(login.canClaim, onLogin)
        }
    }
}

/** The small book button on the status strip (opens the Signal Dex). A dot shows while there are NEW entries. */
@Composable
private fun DexButton(newCount: Int, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "dexbtn")
    val pulse by inf.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dexpulse"
    )
    Box(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(DEX, fontSize = 14.sp, modifier = Modifier.graphicsLayer { alpha = if (newCount > 0) 0.6f + 0.4f * pulse else 0.55f })
        if (newCount > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(7.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(GameViolet, CircleShape)
            )
        }
    }
}

/** The small gift button on the status strip. It pulses with a dot while today's login reward is waiting. */
@Composable
private fun LoginGiftButton(canClaim: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "gift")
    val pulse by inf.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "giftpulse"
    )
    Box(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(GIFT, fontSize = 14.sp, modifier = Modifier.graphicsLayer { alpha = if (canClaim) 0.6f + 0.4f * pulse else 0.55f })
        if (canClaim) {
            Box(
                Modifier.align(Alignment.TopEnd).size(7.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(GameGold, CircleShape)
            )
        }
    }
}

// ---- daily strip -----------------------------------------------------------------------------

@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

internal fun countdown(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return "${m / 60}H ${m % 60}M"
}

@Composable
private fun QuestRow(q: QuestState) {
    val cs = MaterialTheme.colorScheme
    val frac = q.progress.toFloat() / q.def.goal
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (q.done) "\u2714" else "\u25A1", color = if (q.done) GameGreen else cs.outline,
            fontSize = 20.sp, modifier = Modifier.width(28.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    q.def.name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (q.done) Color.White else cs.onSurface
                )
                Text(
                    "  ${Daily.tierName(q.def.tier)}", fontSize = 8.sp, letterSpacing = 1.sp,
                    color = when (q.def.tier) {
                        0 -> GameGreen
                        1 -> GameCyan
                        else -> GameMagenta
                    }
                )
            }
            Text(q.def.desc, fontSize = 10.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            GameBar(frac, height = 5.dp, color = if (q.done) GameGreen else GameGold)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (q.done) "DONE" else "${q.progress}/${q.def.goal}", fontSize = 10.sp,
                fontWeight = FontWeight.Bold, color = if (q.done) GameGreen else cs.onSurfaceVariant
            )
            Text("+${q.reward} XP", fontSize = 10.sp, color = if (q.done) GameGold else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyPanel(d: DailyState) {
    val cs = MaterialTheme.colorScheme
    val now = rememberNow()
    val left = (d.resetAtMs - now).coerceAtLeast(0L)
    val span = (d.nextMilestone - d.prevMilestone).coerceAtLeast(1)
    val mf = ((d.streak - d.prevMilestone).toFloat() / span).coerceIn(0f, 1f)
    val doneCount = d.quests.count { it.done }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FLAME, fontSize = 34.sp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("${d.streak} DAY STREAK", color = GameOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "SKIPPED DAYS NEVER RESET IT   QUEST XP +${d.bonusPct}%",
                    color = cs.onSurfaceVariant, fontSize = 10.sp
                )
            }
        }
        Column {
            Row {
                Text(
                    "NEXT MILESTONE: ${d.nextMilestone} DAYS", fontSize = 10.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (mf >= 0.8f) "ALMOST THERE!" else "${d.nextMilestone - d.streak} TO GO", fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = if (mf >= 0.8f) GameGold else cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(3.dp))
            GameBar(mf, height = 7.dp, color = GameOrange)
        }
        Text("// TODAY'S QUESTS", color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        for (q in d.quests) QuestRow(q)
        Column {
            Row {
                Text(
                    "PERFECT DAY: FINISH ALL ${d.quests.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (d.perfect) GameGold else cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                Text(
                    if (d.perfect) "DONE" else "+${d.perfectBonus} XP", fontSize = 10.sp,
                    color = if (d.perfect) GameGreen else GameGold
                )
            }
            Spacer(Modifier.height(3.dp))
            GameBar(
                if (d.quests.isEmpty()) 0f else doneCount.toFloat() / d.quests.size,
                height = 6.dp, color = GameGold
            )
        }
        Text(
            "NEW QUESTS IN ${countdown(left)} (04:00).",
            color = cs.onSurfaceVariant, fontSize = 9.sp
        )
        Text(
            "THE FIRST QUEST YOU FINISH EACH DAY COUNTS THE DAY FOR YOUR STREAK. EACH STREAK DAY ADDS 2% TO QUEST XP " +
                "(UP TO ${Daily.MAX_BONUS_PCT}%). SKIPPED DAYS NEVER RESET OR COST YOU ANYTHING: THE STREAK JUST KEEPS " +
                "ADDING UP. DAYS CHANGE AT 04:00.",
            color = cs.onSurfaceVariant, fontSize = 9.sp
        )
        Spacer(Modifier.height(6.dp))
    }
}

// ---- profile screen --------------------------------------------------------------------------

@Composable
fun GameProfileDialog(state: GameState, daily: DailyState, startTab: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(startTab) }
    var confirmReset by remember { mutableStateOf(false) }
    val s = state.stats
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(10.dp)) {
            HudCard(border = GameGold) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("// OPERATOR PROFILE", color = GameGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    MiniButton("CLOSE") { onDismiss() }
                }
                Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    ChipRow(listOf("DAILY", "PROFILE", "ACHIEVEMENTS", "TITLES"), tab, { it }) { tab = it; confirmReset = false }
                }
                LazyColumn(
                    Modifier.heightIn(max = 470.dp).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (tab) {
                        "DAILY" -> {
                            item { DailyPanel(daily) }
                        }
                        "PROFILE" -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text("LEVEL ${state.level}", color = GameGold, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(10.dp))
                                        Text("[ ${Game.titleName(state.equipped)} ]", color = cs.primary, fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                                    }
                                    val frac = (state.xpIntoLevel.toFloat() / state.xpForLevel).coerceIn(0f, 1f)
                                    GameBar(frac, height = 8.dp, color = GameGold, from = cs.primary)
                                    Text(
                                        if (state.level >= Game.MAX_LEVEL) "MAX LEVEL  %,d XP".format(state.xp)
                                        else "%,d / %,d XP TO LEVEL ${state.level + 1}   (TOTAL %,d)".format(
                                            state.xpIntoLevel, state.xpForLevel, state.xp
                                        ),
                                        color = cs.onSurfaceVariant, fontSize = 10.sp
                                    )
                                }
                            }
                            item { StatRow("DEVICES FOUND", "%,d".format(s.devices)) }
                            item { StatRow("KINDS IDENTIFIED", "${s.categories}") }
                            item { StatRow("ALERT-TIER FINDS", "${s.alerts}") }
                            item { StatRow("POSSIBLE-TIER FINDS", "${s.possible}") }
                            item { StatRow("TARGET LIST HITS", "${s.watch}") }
                            item { StatRow("DEVICES LOCATED", "${s.locates}") }
                            item { StatRow("DEVICES NAMED", "${s.named}") }
                            item { StatRow("NAMES READ (GATT)", "${s.idents}") }
                            item { StatRow("SENSORS DECODED", "${s.sensors}") }
                            item { StatRow("SCAN TIME", "${s.scanMin / 60}H ${s.scanMin % 60}M") }
                            item { StatRow("DAILY QUESTS DONE", "${s.quests}") }
                            item { StatRow("PERFECT DAYS", "${s.perfect}") }
                            item { StatRow("STREAK DAYS", "${s.streakBest}") }
                            item { StatRow("LOGIN DAYS", "${s.logins}") }
                            item {
                                Text(
                                    "XP: +10 NEW DEVICE, +25 NEW KIND, +40 POSSIBLE-TIER, +100 ALERT-TIER, +50 TARGET HIT, " +
                                        "+30 LOCATED, +15 SENSOR OR NAME READ, +10 NAMED, +1 PER SCAN MINUTE, DAILY QUESTS +40 / +80 / +150 " +
                                        "(PLUS STREAK BONUS), PERFECT DAY +150, LOGIN CALENDAR +25 TO +40 A DAY (UP TO +1,000 ON DAY 28). DEVICES WITH A " +
                                        "ROTATING ADDRESS COUNT ONCE PER NAME, AND UNNAMED ONES GIVE NO DEVICE XP.",
                                    color = cs.onSurfaceVariant, fontSize = 9.sp
                                )
                            }
                            item {
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                                    MiniButton(if (confirmReset) "TAP AGAIN: ERASE ALL" else "RESET PROGRESS", cs.error) {
                                        if (confirmReset) {
                                            Game.reset()
                                            confirmReset = false
                                        } else {
                                            confirmReset = true
                                        }
                                    }
                                }
                            }
                        }
                        "ACHIEVEMENTS" -> {
                            item {
                                Text("UNLOCKED ${state.achievements.size} / ${Game.ACHIEVEMENTS.size}",
                                    color = GameGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            val closest = Game.ACHIEVEMENTS
                                .filter { !state.achievements.containsKey(it.id) && it.goal > 1 }
                                .map { it to (min(it.progress(s), it.goal).toFloat() / it.goal) }
                                .filter { it.second > 0f }
                                .sortedByDescending { it.second }
                                .take(3)
                            if (closest.isNotEmpty()) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("// ALMOST THERE", color = cs.primary, fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        for ((a, f) in closest) {
                                            Column {
                                                Row {
                                                    Text(a.name, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                        color = Color.White, modifier = Modifier.weight(1f))
                                                    Text("${min(a.progress(s), a.goal)}/${a.goal}", fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (f >= 0.8f) GameGold else cs.onSurfaceVariant)
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                GameBar(f, height = 6.dp, color = GameGold)
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                            items(Game.ACHIEVEMENTS, key = { it.id }) { a ->
                                val done = state.achievements.containsKey(a.id)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (done) "\u2605" else "\u2606", color = if (done) GameGold else cs.outline,
                                        fontSize = 22.sp, modifier = Modifier.width(30.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(a.name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                color = if (done) Color.White else cs.onSurfaceVariant)
                                            if (a.id in state.newAchievements) {
                                                Text("NEW", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(start = 6.dp).background(GameGold)
                                                        .padding(horizontal = 3.dp))
                                            }
                                        }
                                        Text(a.desc, fontSize = 10.sp, color = cs.onSurfaceVariant)
                                        if (a.title != null) {
                                            Text("UNLOCKS TITLE: ${Game.titleName(a.title)}", fontSize = 9.sp, color = cs.primary)
                                        }
                                        if (!done && a.goal > 1) {
                                            Spacer(Modifier.height(3.dp))
                                            GameBar(min(a.progress(s), a.goal).toFloat() / a.goal, height = 4.dp, color = GameGold)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("+${a.xp} XP", fontSize = 10.sp, color = if (done) GameGold else cs.onSurfaceVariant)
                                        if (!done && a.goal > 1) {
                                            Text("${min(a.progress(s), a.goal)}/${a.goal}", fontSize = 10.sp, color = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                        }
                        else -> {
                            item {
                                Text("UNLOCKED ${state.titles.size} / ${Game.TITLES.size}",
                                    color = GameGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            items(Game.TITLES, key = { it.id }) { t ->
                                val owned = t.id in state.titles
                                val on = t.id == state.equipped
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (owned) "[ ${t.name} ]" else "[ ???? ]", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                color = if (on) GameGold else if (owned) Color.White else cs.onSurfaceVariant
                                            )
                                            if (t.id in state.newTitles) {
                                                Text("NEW", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(start = 6.dp).background(GameGold)
                                                        .padding(horizontal = 3.dp))
                                            }
                                        }
                                        Text(Game.howToUnlock(t), fontSize = 9.sp, color = cs.onSurfaceVariant)
                                    }
                                    when {
                                        on -> Text("EQUIPPED", color = cs.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        owned -> MiniButton("EQUIP") { Game.equip(t.id) }
                                        else -> Text("LOCKED", color = cs.outline, fontSize = 10.sp)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
