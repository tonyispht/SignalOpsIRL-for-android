package io.github.tonyispht.signalopsirl

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True when a migration card may need to show (cheap check for the main list). */
fun migrationRelevant(ctx: android.content.Context): Boolean =
    Migration.isLegacyBuild(ctx) || Migration.installed(ctx, Migration.LEGACY)

/** The main-screen card that moves players from the old app ID to the new one. Shows nothing when not needed. */
@Composable
fun MigrationCard(store: Store) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    // Installs and uninstalls happen in other screens: look again every couple of seconds while this is on screen.
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    if (Migration.isLegacyBuild(ctx)) {
        val move by Updater.moveState.collectAsState()
        val newInstalled = remember(tick, move.phase) { Migration.installed(ctx, Migration.NEW) }
        HudCard(border = cs.tertiary) {
            Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SIGNAL OPS IRL HAS MOVED", fontSize = 13.sp, color = cs.tertiary, letterSpacing = 1.sp)
                Text(
                    "THE APP HAS A NEW ID SO IT CAN BE LISTED ON F-DROID. THIS VERSION WON'T GET UPDATES ANYMORE. " +
                        "MOVE TO THE NEW APP: YOUR XP, DEX, QUESTS, SETTINGS, NAMES AND TARGETS COME WITH YOU.",
                    fontSize = 9.sp, lineHeight = 11.sp, color = cs.onSurfaceVariant
                )
                if (newInstalled) {
                    Text("NEW APP INSTALLED. STEP 2: OPEN IT TO BRING YOUR PROGRESS.", fontSize = 10.sp, color = cs.secondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MiniButton("OPEN NEW APP & MOVE PROGRESS", cs.tertiary) {
                            Migration.openNewAppIntent(ctx)?.let { runCatching { ctx.startActivity(it) } }
                        }
                    }
                } else {
                    move.message?.let {
                        Text(it, fontSize = 10.sp, color = if (move.phase == UpdatePhase.ERROR) cs.error else cs.secondary)
                    }
                    if (move.phase == UpdatePhase.DOWNLOADING) {
                        if (move.progress >= 0f) {
                            LinearProgressIndicator(progress = { move.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        when (move.phase) {
                            UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING ->
                                Text("WORKING...", fontSize = 11.sp, color = cs.tertiary, modifier = Modifier.padding(8.dp))
                            UpdatePhase.NEEDS_PERMISSION -> {
                                MiniButton("ALLOW INSTALLS", cs.tertiary) {
                                    runCatching { ctx.startActivity(Updater.permissionIntent(ctx)) }
                                }
                                MiniButton("CONTINUE") {
                                    scope.launch(Dispatchers.IO) { Updater.downloadAndInstallMove(ctx.applicationContext) }
                                }
                            }
                            else -> MiniButton(
                                if (move.phase == UpdatePhase.ERROR) "TRY AGAIN" else "STEP 1: INSTALL NEW APP", cs.tertiary
                            ) {
                                scope.launch(Dispatchers.IO) {
                                    val app = ctx.applicationContext
                                    if (Updater.moveState.value.phase != UpdatePhase.AVAILABLE) Updater.checkMove(app)
                                    if (Updater.moveState.value.phase == UpdatePhase.AVAILABLE) Updater.downloadAndInstallMove(app)
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ---- new app --------------------------------------------------------------------------------
    var status by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) {
            status = "MOVING YOUR PROGRESS..."
            ctx.startActivity(
                Intent(ctx, MigrationImportActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            status = "THE OLD APP DID NOT SEND ITS DATA. OPEN THE OLD APP, INSTALL ITS UPDATE, THEN TRY AGAIN."
        }
    }
    val offerImport = remember(tick) { Migration.offerImport(ctx, store) }
    val canExport = remember(tick) { offerImport && Migration.oldAppCanExport(ctx) }
    val offerUninstall = remember(tick) { Migration.offerUninstallOld(ctx, store) }

    // Opened from the old app's "OPEN NEW APP & MOVE PROGRESS": start straight away.
    val act = ctx as? Activity
    LaunchedEffect(canExport) {
        if (canExport && act?.intent?.getBooleanExtra(Migration.EXTRA_IMPORT, false) == true) {
            act.intent.removeExtra(Migration.EXTRA_IMPORT)
            runCatching { launcher.launch(Migration.exportRequest()) }
        }
    }

    if (hidden || (!offerImport && !offerUninstall)) return
    HudCard(border = cs.tertiary) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (offerImport) {
                Text("WELCOME TO THE NEW SIGNAL OPS IRL", fontSize = 13.sp, color = cs.tertiary, letterSpacing = 1.sp)
                Text(
                    if (canExport) "YOUR OLD COPY OF THE APP IS STILL INSTALLED. BRING YOUR XP, DEX, QUESTS, SETTINGS, " +
                        "NAMES AND TARGETS OVER WITH ONE TAP."
                    else "YOUR OLD COPY OF THE APP IS STILL INSTALLED BUT TOO OLD TO SEND ITS PROGRESS. OPEN THE OLD APP, " +
                        "INSTALL THE UPDATE IT OFFERS, THEN COME BACK HERE.",
                    fontSize = 9.sp, lineHeight = 11.sp, color = cs.onSurfaceVariant
                )
                status?.let { Text(it, fontSize = 10.sp, color = cs.secondary) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniButton("START FRESH", cs.onSurfaceVariant) { hidden = true }
                    if (canExport) MiniButton("MOVE MY PROGRESS", cs.tertiary) {
                        status = null
                        runCatching { launcher.launch(Migration.exportRequest()) }
                            .onFailure { status = "COULD NOT REACH THE OLD APP" }
                    }
                }
            } else {
                Text("PROGRESS MOVED ✓", fontSize = 13.sp, color = cs.tertiary, letterSpacing = 1.sp)
                Text(
                    "EVERYTHING IS IN THE NEW APP. THE OLD COPY IS STILL INSTALLED AND NO LONGER NEEDED.",
                    fontSize = 9.sp, lineHeight = 11.sp, color = cs.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniButton("LATER", cs.onSurfaceVariant) { hidden = true }
                    MiniButton("UNINSTALL OLD APP", cs.tertiary) {
                        runCatching { ctx.startActivity(Migration.uninstallOldIntent()) }
                    }
                }
            }
        }
    }
}
