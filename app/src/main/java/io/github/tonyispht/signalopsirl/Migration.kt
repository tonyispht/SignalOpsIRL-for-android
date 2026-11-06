package io.github.tonyispht.signalopsirl

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moves players from the app's original ID (com.example.signalopsirl) to the new one (io.github.tonyispht.signalopsirl).
 *
 * Android treats a new app ID as a different app, so it cannot simply update. Instead:
 *  1. The old app gets one last in-app update: the same app built with the old ID (the "bridge"). It shows a
 *     "Signal OPS IRL has moved" card that installs the new app.
 *  2. The new app asks the old one for its data through [MigrationExportActivity]. Both sides check that the other
 *     app is signed with the same key, so no other app can request or inject the data.
 *  3. [MigrationImportActivity] (its own process) stops the new app's main process, copies the files in, and
 *     restarts it. XP, Dex, quests, settings, names and targets all come across.
 *  4. The new app offers to uninstall the old one.
 */
object Migration {
    val LEGACY: String = BuildConfig.LEGACY_ID
    val NEW: String = BuildConfig.NEW_ID
    const val EXTRA_IMPORT = "signalops.import_from_old_app"
    private const val EXPORT_CLASS = "io.github.tonyispht.signalopsirl.MigrationExportActivity"

    /** This copy is the old-ID bridge. */
    fun isLegacyBuild(ctx: Context) = ctx.packageName == LEGACY

    fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun sameSigner(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)

    /** Explicit request to the old app's export screen. */
    fun exportRequest(): Intent = Intent().setClassName(LEGACY, EXPORT_CLASS)

    /** The old app is installed and new enough (the bridge) to hand over its data. */
    fun oldAppCanExport(ctx: Context): Boolean =
        installed(ctx, LEGACY) && ctx.packageManager.queryIntentActivities(exportRequest(), 0).isNotEmpty()

    /** New app: should the "bring my progress over" card show? */
    fun offerImport(ctx: Context, store: Store): Boolean =
        !isLegacyBuild(ctx) && !store.migratedFromLegacy && installed(ctx, LEGACY) && sameSigner(ctx, LEGACY)

    /** New app, after importing: the old app is still installed. */
    fun offerUninstallOld(ctx: Context, store: Store): Boolean =
        !isLegacyBuild(ctx) && store.migratedFromLegacy && installed(ctx, LEGACY)

    fun uninstallOldIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$LEGACY"))

    /** Old app: open the new app and ask it to pull the data across. */
    fun openNewAppIntent(ctx: Context): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(NEW)?.putExtra(EXTRA_IMPORT, true)

    // ---- packing -----------------------------------------------------------------------------

    /** Zips shared_prefs/ and the top-level files of files/ (game, Dex, quests, firmware, vendor updates). */
    fun pack(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "migrate.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun add(prefix: String, d: File) {
                d.listFiles()?.filter { it.isFile }?.forEach { f ->
                    z.putNextEntry(ZipEntry(prefix + f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            add("shared_prefs/", File(ctx.dataDir, "shared_prefs"))
            add("files/", ctx.filesDir)
        }
        return out
    }

    /** Unzips a [pack] archive into this app's own folders. Rejects any path that is not a plain file name. */
    internal fun unpack(ctx: Context, input: java.io.InputStream): Int {
        val prefs = File(ctx.dataDir, "shared_prefs").apply { mkdirs() }
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val parts = e.name.split('/')
                if (e.isDirectory || parts.size != 2 || parts[1].isEmpty() || parts[1].startsWith(".") ||
                    parts[1].contains('\\')
                ) continue
                val dest = when (parts[0]) {
                    "shared_prefs" -> if (parts[1].endsWith(".xml")) File(prefs, parts[1]) else null
                    "files" -> File(ctx.filesDir, parts[1])
                    else -> null
                } ?: continue
                val tmp = File(dest.parentFile, dest.name + ".import")
                tmp.outputStream().use { z.copyTo(it) }
                if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
                n++
            }
        }
        return n
    }
}

/**
 * Old app only: hands its data to the new app. Started by the new app with startActivityForResult, so the caller's
 * package is known; it must be the new ID and signed with the same key.
 */
class MigrationExportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (!Migration.isLegacyBuild(this) || caller != Migration.NEW || !Migration.sameSigner(this, caller)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val result = runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", Migration.pack(this))
            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.getOrNull()
        setResult(if (result != null) RESULT_OK else RESULT_CANCELED, result)
        finish()
    }
}

/**
 * New app only, in its own process (":migrate"): stops the main process, copies the old app's data in, marks the
 * move as done, and starts the app again so everything is read fresh.
 */
class MigrationImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        // The URI's authority belongs to the old app (authorities are unique on a device) and its signer must match.
        if (Migration.isLegacyBuild(this) || uri == null || uri.authority != Migration.LEGACY + ".files" ||
            !Migration.sameSigner(this, Migration.LEGACY)
        ) {
            finish()
            return
        }
        // Stop the main process first so it cannot write its old in-memory state over the imported files.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.filter { it.pid != Process.myPid() && it.uid == Process.myUid() }
            ?.forEach { Process.killProcess(it.pid) }

        val n = runCatching { contentResolver.openInputStream(uri)?.use { Migration.unpack(this, it) } ?: 0 }.getOrDefault(-1)
        if (n > 0) {
            getSharedPreferences("signalops", MODE_PRIVATE).edit().putBoolean("migratedFromLegacy", true).commit()
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("signalops.import_result", n)
        )
        finish()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }
}
