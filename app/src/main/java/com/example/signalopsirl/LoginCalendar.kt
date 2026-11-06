package com.example.signalopsirl

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The login calendar, in the style of the login rewards in RAID: Shadow Legends.
 *
 * It is a 28-day calendar. Opening the app on a new day lets you collect the reward of the next empty day; every
 * day pays XP, and the milestone days (7, 14, 21 and 28) pay a lot more. After day 28 a fresh calendar starts.
 * Like the quest streak, days you skip never reset or cost anything: the calendar simply waits on the next empty
 * day until you come back. One reward per day, and a "day" runs from 04:00 to 04:00 (same clock as [Daily]).
 *
 * Everything here runs under the same lock as [Game].
 */

/** The reward printed on one calendar day. */
data class LoginDay(val day: Int, val xp: Int, val big: Boolean)

data class LoginState(
    /** Which calendar this is (1 for the first 28 days, 2 for the next, ...). */
    val cycle: Int = 1,
    /** Days already collected on the calendar being shown (0..28). */
    val claimed: Int = 0,
    /** The reward for today has not been collected yet. */
    val canClaim: Boolean = false,
    /** Collected days over all calendars. */
    val totalDays: Int = 0,
    /** The calendar day (1..28) that the next collect pays. */
    val nextDay: Int = 1,
    /** When the next reward becomes available (epoch ms). */
    val resetAtMs: Long = 0
)

object LoginCalendar {
    const val CYCLE = 28

    /** Calendar days that pay a big reward. */
    val MILESTONES = intArrayOf(7, 14, 21, 28)

    fun isMilestone(day: Int): Boolean = day in MILESTONES

    /** Reward for [day] (1..28). Ordinary days grow a little each week; milestone days are the jackpots. */
    fun rewardFor(day: Int): LoginDay {
        val d = day.coerceIn(1, CYCLE)
        val xp = when (d) {
            7 -> 150
            14 -> 300
            21 -> 500
            28 -> 1000
            else -> 25 + 5 * ((d - 1) / 7)
        }
        return LoginDay(d, xp, isMilestone(d))
    }

    /** The milestone at or after [day]; the calendar's last day is the final one. */
    fun nextMilestone(day: Int): Int = MILESTONES.firstOrNull { it >= day } ?: CYCLE

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> get() = _state

    private var prefs: SharedPreferences? = null
    private var loaded = false
    private var total = 0
    private var lastDay = 0L

    /** Read by [Game] for the login achievements. */
    val totalDays: Int get() = total

    fun init(context: Context) {
        synchronized(Game) {
            if (loaded) return
            loaded = true
            val p = context.applicationContext.getSharedPreferences("signalops_login", Context.MODE_PRIVATE)
            prefs = p
            total = p.getInt("total", 0)
            lastDay = p.getLong("lastDay", 0L)
            publish()
        }
    }

    fun reset() {
        synchronized(Game) {
            if (!loaded) return
            total = 0
            lastDay = 0L
            prefs?.edit()?.clear()?.apply()
            publish()
        }
    }

    /** Re-reads the clock (the UI calls this now and then, so the calendar opens up when the day changes). */
    fun refresh() {
        synchronized(Game) {
            if (!loaded) return
            val now = _state.value
            if (now.canClaim != (lastDay != Daily.dayNow())) publish()
        }
    }

    /** Collects today's reward. Returns it, or null when it was already collected today. */
    fun claim(): LoginDay? {
        return synchronized(Game) {
            if (!loaded || lastDay == Daily.dayNow()) return@synchronized null
            total++
            lastDay = Daily.dayNow()
            prefs?.edit()?.putInt("total", total)?.putLong("lastDay", lastDay)?.apply()
            val reward = rewardFor((total - 1) % CYCLE + 1)
            publish()
            Game.push(GameEvent.LoginClaimed(Game.nextSeq(), reward.day, reward.xp, reward.big))
            Game.grantXp(reward.xp, "LOGIN DAY ${reward.day}")
            reward
        }
    }

    private fun publish() {
        val can = lastDay != Daily.dayNow()
        val rem = total % CYCLE
        // A finished calendar stays on screen (full) until the next collect starts a new one.
        val full = total > 0 && rem == 0 && !can
        val cycle = if (full) total / CYCLE else total / CYCLE + 1
        val claimed = if (full) CYCLE else rem
        val next = if (full) 1 else rem + 1
        _state.value = LoginState(
            cycle = cycle,
            claimed = claimed,
            canClaim = can,
            totalDays = total,
            nextDay = next,
            resetAtMs = Daily.nextResetMs()
        )
    }
}
