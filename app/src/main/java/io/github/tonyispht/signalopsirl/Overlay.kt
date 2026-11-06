package io.github.tonyispht.signalopsirl

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/** Shows a flashing red HUD border above every app. Needs the "draw over other apps" permission. */
object OverlayManager {
    private val handler = Handler(Looper.getMainLooper())
    private var view: BorderOverlayView? = null
    private val autoHide = Runnable { hide() }

    fun show(ctx: Context, durationMs: Long = 10_000L) {
        val app = ctx.applicationContext
        handler.removeCallbacks(autoHide)
        if (view == null) {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = BorderOverlayView(app)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                wm.addView(v, lp)
                v.start()
                view = v
            } catch (e: Exception) {
                return
            }
        }
        handler.postDelayed(autoHide, durationMs)
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        val v = view ?: return
        view = null
        v.stop()
        runCatching {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }
}

class BorderOverlayView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val red = Color.parseColor("#FF2D55")
    private var a = 1f

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 26 * d; color = red
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; color = red
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.WHITE
        strokeCap = Paint.Cap.SQUARE
    }

    private val anim = ValueAnimator.ofFloat(0.15f, 1f).apply {
        duration = 350
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { a = it.animatedValue as Float; invalidate() }
    }

    fun start() = anim.start()
    fun stop() = anim.cancel()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        glow.alpha = (a * 70).toInt()
        val g = glow.strokeWidth / 2
        c.drawRect(g, g, w - g, h - g, glow)

        core.alpha = (a * 255).toInt()
        val k = core.strokeWidth / 2
        c.drawRect(k, k, w - k, h - k, core)

        bracket.alpha = (a * 255).toInt()
        val len = 56 * d
        val inset = 10 * d
        fun br(x: Float, y: Float, dx: Float, dy: Float) {
            c.drawLine(x, y, x + dx * len, y, bracket)
            c.drawLine(x, y, x, y + dy * len, bracket)
        }
        br(inset, inset, 1f, 1f)
        br(w - inset, inset, -1f, 1f)
        br(inset, h - inset, 1f, -1f)
        br(w - inset, h - inset, -1f, -1f)
    }
}
