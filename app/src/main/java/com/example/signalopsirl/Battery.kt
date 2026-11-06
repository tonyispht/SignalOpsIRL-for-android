package com.example.signalopsirl

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The activity behind a context (Compose gives a wrapped one), or null. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Hides or shows the system bars (status and navigation bar). A swipe from the screen edge brings them back for a
 * moment. With the bars hidden the app also draws into the camera cut-out area, so nothing is letterboxed.
 */
internal fun applyFullscreen(activity: Activity, on: Boolean) {
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val ctl = WindowInsetsControllerCompat(window, window.decorView)
    if (on) {
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        ctl.show(WindowInsetsCompat.Type.systemBars())
    }
    if (Build.VERSION.SDK_INT >= 28) {
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = if (on) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        window.attributes = attrs
    }
}

/**
 * A tiny battery readout ("87%") for the header's right end, so the level stays visible when fullscreen hides the
 * status bar. A bolt shows while charging; the number turns amber at 30% or less and red at 15% or less.
 */
@Composable
fun BatteryIndicator(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var pct by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        fun read(i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) pct = (level * 100 / scale).coerceIn(0, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        // The battery broadcast is sticky: registering returns the current state right away.
        read(
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    if (pct < 0) return
    val cs = MaterialTheme.colorScheme
    val color = when {
        charging -> GameGreen
        pct <= 15 -> AlertRed
        pct <= 30 -> AlertAmber
        else -> cs.onSurfaceVariant
    }
    Text(
        (if (charging) "\u26A1" else "") + "$pct%",
        color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** Battery state for screens that want more than the header's tiny readout. */
class BatteryInfo(val pct: Int, val status: Int, val plugged: Int) {
    val charging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING

    /** "CHARGING (USB)", "FULL", "ON BATTERY"... */
    fun statusText(): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING" + when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> " (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> " (USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (WIRELESS)"
            else -> ""
        }
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "ON BATTERY"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (plugged != 0) "PLUGGED IN, NOT CHARGING" else "ON BATTERY"
        else -> ""
    }
}

/** The phone's battery level and charging state, kept up to date while the caller is on screen (null until known). */
@Composable
fun rememberBatteryInfo(): BatteryInfo? {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<BatteryInfo?>(null) }
    DisposableEffect(ctx) {
        fun read(i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            info = BatteryInfo(
                (level * 100 / scale).coerceIn(0, 100),
                i.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        read(
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return info
}
