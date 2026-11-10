package io.github.tonyispht.signalopsirl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The shareable session card: a 1080 x 1350 image (the 4:5 size social apps show in full) drawn in the same
 * terminal / CRT look as the standby report, with the session's stats, finds and the operator's level.
 */
object SessionCard {
    private const val W = 1080
    private const val H = 1350

    fun render(r: SessionReport, tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = 0xFF05080C.toInt()
        c.drawColor(bg)

        fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        val monoB = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // soft glow from the top, scanlines and phosphor grain
        p.shader = LinearGradient(0f, 0f, 0f, H * 0.55f, alpha(tint, 0.16f), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null
        p.color = alpha(tint, 0.05f)
        var y = 0f
        while (y < H) { c.drawRect(0f, y, W.toFloat(), y + 2f, p); y += 6f }
        val rnd = Random(r.durationMs xor r.heard.toLong())
        repeat(260) {
            p.color = alpha(tint, 0.06f + rnd.nextFloat() * 0.16f)
            val gx = rnd.nextFloat() * W
            val gy = rnd.nextFloat() * H
            c.drawRect(gx, gy, gx + 3f, gy + 3f, p)
        }

        // cut-corner frame
        val m = 36f
        val cut = 44f
        val frame = Path().apply {
            moveTo(m + cut, m); lineTo(W - m, m); lineTo(W - m, H - m - cut); lineTo(W - m - cut, H - m)
            lineTo(m, H - m); lineTo(m, m + cut); close()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f
        p.color = alpha(tint, 0.18f)
        c.drawPath(frame, p)
        p.strokeWidth = 3f
        p.color = alpha(tint, 0.9f)
        c.drawPath(frame, p)
        p.style = Paint.Style.FILL

        val left = 84f
        val right = W - 84f
        fun text(s: String, x: Float, yy: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, glow: Boolean = false) {
            p.typeface = if (bold) monoB else mono
            p.textSize = size
            p.textAlign = align
            if (glow) {
                p.color = alpha(color, 0.25f)
                p.setShadowLayer(18f, 0f, 0f, alpha(color, 0.8f))
                c.drawText(s, x, yy, p)
                p.clearShadowLayer()
            }
            p.color = color
            c.drawText(s, x, yy, p)
        }
        fun fit(s: String, size: Float, maxW: Float): String {
            p.textSize = size
            if (p.measureText(s) <= maxW) return s
            var t = s
            while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
            return "$t…"
        }
        val dim = alpha(tint, 0.55f)
        val white = 0xFFE8F6FF.toInt()

        // header
        text("SIGNAL OPS IRL", left, 150f, 64f, tint, bold = true, glow = true)
        text("// SESSION REPORT", left, 206f, 34f, dim)
        val date = SimpleDateFormat("d MMM yyyy  HH:mm", Locale.US).format(Date(if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()))
        text(date.uppercase(Locale.US), right, 206f, 30f, dim, align = Paint.Align.RIGHT)
        p.color = alpha(tint, 0.45f)
        c.drawRect(left, 236f, right, 239f, p)

        // big stats: 2 x 2
        val sec = r.durationMs / 1000L
        val time = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
        val cells = listOf("TIME" to time, "DEVICES" to "${r.heard}", "NEW" to "${r.fresh}", "XP" to "+${r.xp}")
        val cw = (right - left - 30f) / 2f
        cells.forEachIndexed { i, (label, value) ->
            val cx = left + (i % 2) * (cw + 30f)
            val cy = 270f + (i / 2) * 190f
            p.color = alpha(tint, 0.07f)
            c.drawRect(RectF(cx, cy, cx + cw, cy + 165f), p)
            p.color = alpha(tint, 0.9f)
            c.drawRect(cx, cy, cx + 6f, cy + 165f, p)
            text(value, cx + 34f, cy + 100f, 76f, white, bold = true)
            text(label, cx + 34f, cy + 145f, 28f, dim)
        }

        // detail lines
        var ly = 690f
        fun row(label: String, value: String, color: Int = white) {
            text(label, left, ly, 32f, dim)
            text(fit(value, 32f, right - left - 360f), right, ly, 32f, color, bold = true, align = Paint.Align.RIGHT)
            ly += 50f
        }
        if (r.level > 0) row("OPERATOR", "LVL ${r.level}  ${r.rank}", tint)
        row("KINDS OF DEVICE", "${r.kinds}")
        row("TARGETS FOUND", "${r.targets}")
        r.peakName?.let { row("CLOSEST", "$it  ${r.peakRssi} dBm") }
        if (r.models.isNotEmpty()) row("MODELS SPOTTED", "${r.models.size}")

        // finds: new Dex types first, then rare ones, with rarity colours
        val finds = ArrayList<Pair<String, Rarity>>()
        r.newTypes.forEach { finds.add("NEW  " + it.title to rarityOf(it.key)) }
        r.rare.forEach { (t, rr) -> if (finds.none { it.first.endsWith(t) }) finds.add(t to rr) }
        ly += 16f
        text(if (finds.isEmpty()) "NO RARE FINDS THIS TIME" else "BEST FINDS", left, ly, 30f, dim)
        ly += 22f
        // as many finds as fit above the footer
        val room = ((H - 230f - ly) / 52f).toInt().coerceIn(0, 5)
        val extra = finds.size - room
        for ((t, rr) in finds.take(room)) {
            ly += 52f
            val badgeW = 230f
            p.color = rr.color.toArgbInt()
            c.drawRect(RectF(right - badgeW, ly - 34f, right, ly + 8f), p)
            text(rr.label, right - badgeW / 2, ly - 3f, 26f, 0xFF000000.toInt(), bold = true, align = Paint.Align.CENTER)
            text(fit(t, 34f, right - left - badgeW - 30f), left, ly, 34f, white)
        }

        if (extra > 0) text("+$extra MORE", left, ly + 44f, 26f, dim)

        // footer
        p.color = alpha(tint, 0.45f)
        c.drawRect(left, H - 150f, right, H - 147f, p)
        text("BLUETOOTH SCANNER GAME FOR ANDROID", left, H - 100f, 26f, dim)
        text("github.com/tonyispht/SignalOpsIRL-for-android", left, H - 64f, 26f, alpha(tint, 0.85f))
        return bmp
    }

    /** Renders the card, saves it to the app cache and opens the Android share sheet. */
    fun share(ctx: Context, r: SessionReport, tint: Int) {
        val dir = File(ctx.cacheDir, "exports/cards").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "signalops-session-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".png")
        val bmp = render(r, tint)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, r.shareText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share session card").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
    val a = (alpha * 255).toInt(); val r = (red * 255).toInt(); val g = (green * 255).toInt(); val b = (blue * 255).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** A small bar above the tab bar after a scan: SESSION CARD READY with VIEW / SHARE. Hides itself after 20 s. */
@Composable
fun SessionCardBar(onView: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val lift = with(density) { WindowInsets.navigationBars.getBottom(density) + (NAV_H + 10.dp).roundToPx() }
    LaunchedEffect(Unit) { delay(20_000); onDismiss() }
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -lift), onDismissRequest = onDismiss) {
        HudCard(Modifier.padding(horizontal = 12.dp), border = cs.primary) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.padding(end = 6.dp)) {
                    Text("SESSION CARD READY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.primary, letterSpacing = 1.sp)
                    Text("Full report and a shareable image", fontSize = 10.sp, color = cs.onSurfaceVariant)
                }
                MiniButton("VIEW", cs.secondary, vPad = 6.dp) { onView() }
                MiniButton("SHARE", cs.primary, vPad = 6.dp) { onShare() }
                MiniButton("X", cs.onSurfaceVariant, vPad = 6.dp) { onDismiss() }
            }
        }
    }
}
