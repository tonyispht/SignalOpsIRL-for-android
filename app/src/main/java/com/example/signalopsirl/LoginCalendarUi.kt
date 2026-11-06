package com.example.signalopsirl

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * The login calendar popup: a 7 x 4 grid of 28 days, each with its XP reward. Collected days are stamped, today's
 * day glows and pulses, and the milestone days (7, 14, 21, 28) are bigger and gold. Shown by itself when the app
 * starts with a reward waiting, and from the gift button on the status strip.
 */
@Composable
fun LoginCalendarDialog(state: LoginState, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val now = rememberNow()
    val left = (state.resetAtMs - now).coerceAtLeast(0L)
    // The day collected while this popup is open: it gets the stamp animation, then the popup closes so the
    // celebration (drawn on the main screen) can be seen.
    var justClaimed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(justClaimed) {
        if (justClaimed != null) {
            delay(1100L)
            onDismiss()
        }
    }
    val today = if (state.canClaim) state.nextDay else null
    val today0 = today ?: justClaimed
    val todayReward = LoginCalendar.rewardFor(today0 ?: state.nextDay)
    val nextMs = LoginCalendar.nextMilestone(if (state.canClaim) state.nextDay else state.claimed + 1)
    val msReward = LoginCalendar.rewardFor(nextMs)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(10.dp)) {
            HudCard(border = GameGold) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(GIFT, fontSize = 16.sp)
                    Text(
                        "  // DAILY LOGIN", color = GameGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                    )
                    MiniButton("CLOSE") { onDismiss() }
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row {
                        Text(
                            "CALENDAR ${state.cycle}", color = cs.primary, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${state.claimed} / ${LoginCalendar.CYCLE} DAYS", color = cs.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (row in 0 until 4) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (col in 0 until 7) {
                                    val day = row * 7 + col + 1
                                    DayCell(
                                        day = day,
                                        collected = day <= state.claimed || day == justClaimed,
                                        isToday = day == today && justClaimed == null,
                                        stamp = day == justClaimed,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Progress to the next big reward.
                    val from = milestoneBefore(nextMs)
                    val have = if (state.canClaim) state.nextDay - 1 else state.claimed
                    val frac = ((have - from).toFloat() / (nextMs - from).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Row {
                        Text(
                            "NEXT BIG REWARD: DAY $nextMs  +${msReward.xp} XP", fontSize = 10.sp,
                            color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                        )
                        val toGo = (nextMs - have).coerceAtLeast(0)
                        Text(
                            if (frac >= 0.8f && toGo > 0) "ALMOST THERE!" else if (toGo > 0) "$toGo TO GO" else "REACHED",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (frac >= 0.8f) GameGold else cs.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    GameBar(frac, height = 6.dp, color = GameGold)
                    Spacer(Modifier.height(10.dp))
                    ClaimButton(
                        canClaim = state.canClaim && justClaimed == null,
                        done = justClaimed != null,
                        label = "COLLECT DAY ${todayReward.day}   +${todayReward.xp} XP",
                        left = left
                    ) {
                        val r = LoginCalendar.claim()
                        if (r != null) justClaimed = r.day
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SKIPPED DAYS NEVER RESET THE CALENDAR: IT WAITS ON YOUR NEXT EMPTY DAY. " +
                            "THE DAY CHANGES AT 04:00. LOGINS TOTAL: ${state.totalDays}.",
                        color = cs.onSurfaceVariant, fontSize = 9.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** The milestone before [m] on the calendar (0 for the first). */
private fun milestoneBefore(m: Int): Int = LoginCalendar.MILESTONES.lastOrNull { it < m } ?: 0

@Composable
private fun DayCell(day: Int, collected: Boolean, isToday: Boolean, stamp: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val big = LoginCalendar.isMilestone(day)
    val reward = LoginCalendar.rewardFor(day)
    val inf = rememberInfiniteTransition(label = "daycell")
    val pulse by inf.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "daypulse"
    )
    // Stamp: the collected day jumps up in size and bounces back, like a stamp landing on the cell.
    var swell by remember { mutableStateOf(false) }
    LaunchedEffect(stamp) {
        if (stamp) {
            swell = true
            delay(70L)
            swell = false
        }
    }
    val pop by animateFloatAsState(
        if (swell) 1.7f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pop"
    )
    val accent = if (big) GameGold else cs.primary
    val bg = when {
        collected -> GameGreen.copy(alpha = 0.16f)
        isToday -> GameGold.copy(alpha = 0.10f + 0.12f * pulse)
        big -> GameGold.copy(alpha = 0.08f)
        else -> cs.surface
    }
    val borderColor = when {
        collected -> GameGreen.copy(alpha = 0.55f)
        isToday -> GameGold.copy(alpha = 0.5f + 0.5f * pulse)
        big -> GameGold.copy(alpha = 0.7f)
        else -> cs.outline
    }
    Box(
        modifier
            .aspectRatio(0.82f)
            .graphicsLayer {
                val k = pop * (if (isToday) 1f + 0.04f * pulse else 1f)
                scaleX = k
                scaleY = k
            }
            .background(bg)
            .border(if (isToday || big) 2.dp else 1.dp, borderColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$day", fontSize = 8.sp, color = if (collected) GameGreen else cs.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 2.dp)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (big) Text(if (day == LoginCalendar.CYCLE) "\u2605" else GIFT, fontSize = 14.sp)
            Text(
                "+${reward.xp}", fontSize = if (big) 9.sp else 10.sp, fontWeight = FontWeight.Bold,
                color = if (collected) GameGreen.copy(alpha = 0.6f) else if (isToday || big) GameGold else accent,
                textAlign = TextAlign.Center, maxLines = 1
            )
            if (!big) Text("XP", fontSize = 7.sp, color = cs.onSurfaceVariant)
        }
        if (collected) {
            Text(
                "\u2714", fontSize = 22.sp, color = GameGreen,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = 0.9f }
            )
        }
    }
}

@Composable
private fun ClaimButton(canClaim: Boolean, done: Boolean, label: String, left: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val inf = rememberInfiniteTransition(label = "claim")
    val pulse by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "claimpulse"
    )
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if (canClaim) Modifier.graphicsLayer {
                val k = 1f + 0.02f * pulse
                scaleX = k
                scaleY = k
            } else Modifier
        )
    Box(
        modifier
            .background(
                when {
                    canClaim -> GameGold
                    done -> GameGreen
                    else -> cs.surfaceVariant
                }
            )
            .then(if (canClaim) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            when {
                canClaim -> label
                done -> "COLLECTED!"
                else -> "COLLECTED TODAY. NEXT REWARD IN ${countdown(left)}"
            },
            color = if (canClaim || done) Color.Black else cs.onSurfaceVariant,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center
        )
    }
}
