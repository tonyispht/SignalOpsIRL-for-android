package io.github.tonyispht.signalopsirl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, INSTALLING, ERROR }

/** A published GitHub release of the app. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    /** From the release's update.json; -1 when the release has none (then the tag is compared instead). */
    val versionCode: Long,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String?,
    val notes: String,
    val page: String
)

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null,
    /** Download progress 0..1, -1 = unknown. */
    val progress: Float = -1f,
    val message: String? = null
)

/**
 * In-app updates from the project's GitHub releases. Checks the latest release, downloads its APK, checks the
 * SHA-256 published next to it, and hands it to Android's package installer. Android always shows its own
 * "Update this app?" confirmation for apps installed from a file, and refuses any APK not signed with the same
 * key as the installed app, so a tampered file cannot be installed.
 *
 * The only network requests are to api.github.com and GitHub's release-download servers.
 */
object Updater {
    const val REPO = "tonyispht/SignalOpsIRL-for-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val AUTO_EVERY_MS = 6 * 60 * 60_000L

    val state = MutableStateFlow(UpdateState())
    /** Old-ID bridge only: installing the app under its new ID (see [Migration]). */
    val moveState = MutableStateFlow(UpdateState())

    fun installedName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    fun installedCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    // ---- check -------------------------------------------------------------------------------

    /** Blocking; call off the main thread. On launch at most every few hours when automatic checks are on. */
    fun autoCheck(ctx: Context, store: Store) {
        if (!BuildConfig.SELF_UPDATE || !store.autoUpdateCheckOn) return
        val now = System.currentTimeMillis()
        if (now - store.lastUpdateCheck < AUTO_EVERY_MS && state.value.phase != UpdatePhase.AVAILABLE) return
        store.lastUpdateCheck = now
        check(ctx, quiet = true)
    }

    /** Blocking; call off the main thread. [quiet] = do not report "up to date" or network errors. */
    fun check(ctx: Context, quiet: Boolean = false) {
        val busy = state.value.phase
        if (busy == UpdatePhase.CHECKING || busy == UpdatePhase.DOWNLOADING || busy == UpdatePhase.INSTALLING) return
        if (!quiet) state.value = UpdateState(UpdatePhase.CHECKING)
        try {
            val rel = latest(ctx.packageName)
            state.value = if (rel != null && isNewer(ctx, rel)) UpdateState(UpdatePhase.AVAILABLE, rel)
            else if (quiet) UpdateState() else UpdateState(UpdatePhase.UP_TO_DATE, rel, message = "YOU HAVE THE LATEST VERSION")
        } catch (e: Exception) {
            state.value = if (quiet) UpdateState() else UpdateState(UpdatePhase.ERROR, message = "CHECK FAILED: ${reason(e)}")
        }
    }

    /**
     * The latest release, described for app ID [pkg]: its "update-<pkg>.json" asset when the release has one (a
     * release carries one per app ID), else the plain update.json that 1.1 / 1.2 read.
     */
    private fun latest(pkg: String): AppRelease? {
        val j = JSONObject(getText(API))
        val tag = j.optString("tag_name")
        val assets = j.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var info: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (n == "update-$pkg.json") info = a
            else if (n == "update.json" && info == null) info = a
            else if (n.endsWith(".apk", ignoreCase = true) && apk == null) apk = a
        }
        val meta = info?.let { runCatching { JSONObject(getText(it.getString("browser_download_url"))) }.getOrNull() }
        // update.json may name the APK; fall back to the first .apk asset.
        meta?.optString("apk")?.takeIf { it.isNotEmpty() }?.let { want ->
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == want) apk = a
            }
        }
        val a = apk ?: return null
        return AppRelease(
            tag = tag,
            versionName = meta?.optString("versionName")?.takeIf { it.isNotEmpty() } ?: tag.removePrefix("v"),
            versionCode = meta?.optLong("versionCode", -1L) ?: -1L,
            apkUrl = a.getString("browser_download_url"),
            apkName = a.optString("name"),
            apkSize = a.optLong("size", -1L),
            sha256 = meta?.optString("sha256")?.lowercase()?.takeIf { it.length == 64 },
            notes = j.optString("body").trim(),
            page = j.optString("html_url")
        )
    }

    private fun isNewer(ctx: Context, r: AppRelease): Boolean {
        if (r.versionCode > 0) return r.versionCode > installedCode(ctx)
        val have = semver(installedName(ctx)) ?: return false
        val got = semver(r.versionName) ?: return false
        return compare(got, have) > 0
    }

    internal fun semver(s: String): List<Int>? {
        val m = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(s.trim()) ?: return null
        return (1..3).map { m.groupValues[it].toIntOrNull() ?: 0 }
    }

    internal fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    // ---- install -----------------------------------------------------------------------------

    /** True when Android lets this app hand an APK to the installer (Settings > Install unknown apps). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows this app to install updates. */
    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Old-ID bridge: finds the new-ID APK in the latest release. Blocking. */
    fun checkMove(ctx: Context) {
        moveState.value = UpdateState(UpdatePhase.CHECKING)
        moveState.value = try {
            val rel = latest(Migration.NEW)?.takeIf { it.apkName.isNotEmpty() }
            if (rel != null) UpdateState(UpdatePhase.AVAILABLE, rel)
            else UpdateState(UpdatePhase.ERROR, message = "NEW APP NOT FOUND IN THE LATEST RELEASE")
        } catch (e: Exception) {
            UpdateState(UpdatePhase.ERROR, message = "CHECK FAILED: ${reason(e)}")
        }
    }

    /** Blocking; call off the main thread. Downloads the available update and starts the install. */
    fun downloadAndInstall(ctx: Context) = run(ctx, state, ctx.packageName)

    /** Blocking. Old-ID bridge: downloads and installs the app under its new ID. */
    fun downloadAndInstallMove(ctx: Context) = run(ctx, moveState, Migration.NEW)

    private fun run(ctx: Context, flow: MutableStateFlow<UpdateState>, target: String) {
        val rel = flow.value.release ?: return
        if (!canInstall(ctx)) {
            flow.value = UpdateState(UpdatePhase.NEEDS_PERMISSION, rel,
                message = "ALLOW SIGNAL OPS TO INSTALL APPS, THEN PRESS THE BUTTON AGAIN")
            return
        }
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update.apk")
        try {
            flow.value = UpdateState(UpdatePhase.DOWNLOADING, rel, 0f, "DOWNLOADING ${rel.versionName}...")
            val sha = download(rel.apkUrl, file, rel.apkSize) { p ->
                flow.value = flow.value.copy(progress = p)
            }
            if (rel.sha256 != null && sha != rel.sha256) throw IOException("download damaged (checksum mismatch)")
            checkApk(ctx, file, target)
            flow.value = UpdateState(UpdatePhase.INSTALLING, rel, 1f, "WAITING FOR ANDROID TO CONFIRM...")
            install(ctx, file, target)
        } catch (e: Exception) {
            file.delete()
            flow.value = UpdateState(UpdatePhase.ERROR, rel, message = "INSTALL FAILED: ${reason(e)}")
        }
    }

    /** Refuses a file that is not an APK of [target], or (for a self-update) not newer than the installed version. */
    private fun checkApk(ctx: Context, f: File, target: String) {
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw IOException("not a valid APK")
        if (info.packageName != target) throw IOException("APK is for another app")
        if (target != ctx.packageName) return
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code <= installedCode(ctx)) throw IOException("APK is not newer than the installed version")
    }

    private fun install(ctx: Context, f: File, target: String) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(target)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out, 64 * 1024) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName)
                    .putExtra(UpdateReceiver.EXTRA_MOVE, target != ctx.packageName), flags
            )
            s.commit(pending.intentSender)
        }
        f.delete()
    }

    fun dismiss() {
        val s = state.value
        if (s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.INSTALLING) return
        state.value = UpdateState(UpdatePhase.IDLE, s.release, message = null)
    }

    // ---- network -----------------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "SignalOpsIRL-updater")
            c.setRequestProperty("Accept", if (url.startsWith("https://api.github.com")) "application/vnd.github+json" else "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code")
                c.disconnect()
                u = URL(u, loc)
                if (u.protocol != "https") throw IOException("insecure redirect")
                return@repeat
            }
            if (code == 404) throw IOException("no published release yet")
            if (code == 403) throw IOException("GitHub rate limit, try again later")
            if (code != 200) throw IOException("HTTP $code")
            return c
        }
        throw IOException("too many redirects")
    }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Streams [url] to [dest]; returns the file's SHA-256 (lowercase hex). */
    private fun download(url: String, dest: File, expected: Long, onProgress: (Float) -> Unit): String {
        val c = open(url)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            var lastPct = -1
            c.inputStream.use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun reason(e: Exception) = (e.message ?: e.javaClass.simpleName).uppercase()
}

/** Receives the package installer's result: shows Android's confirmation screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_MOVE = "signalops.move"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val move = intent.getBooleanExtra(EXTRA_MOVE, false)
        val flow = if (move) Updater.moveState else Updater.state
        val s = flow.value
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }.onFailure {
                        flow.value = s.copy(phase = UpdatePhase.ERROR, message = "COULD NOT OPEN THE INSTALLER")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                flow.value = s.copy(phase = UpdatePhase.IDLE, message = if (move) "NEW APP INSTALLED" else "UPDATED")
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                flow.value = s.copy(phase = UpdatePhase.AVAILABLE, message = "CANCELLED")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                flow.value = s.copy(phase = UpdatePhase.ERROR, message = "INSTALL FAILED: ${msg.uppercase()}")
            }
        }
    }
}
