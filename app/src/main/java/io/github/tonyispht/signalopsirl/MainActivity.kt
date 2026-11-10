package io.github.tonyispht.signalopsirl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// ---- palette / shapes ---------------------------------------------------------------------

internal val Bg = Color(0xFF05080C)
internal val AlertRed = Color(0xFFFF2D55)
internal val AlertAmber = Color(0xFFFFB300)
internal val Mono = FontFamily.Monospace
internal val HudShape = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
internal val BtnShape = CutCornerShape(8.dp)

private val SpyColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00161A),
    primaryContainer = Color(0xFF00343C),
    onPrimaryContainer = Color(0xFF00E5FF),
    secondary = Color(0xFF39FF88),
    onSecondary = Color(0xFF00170A),
    tertiary = Color(0xFFFFB300),
    tertiaryContainer = Color(0xFF3A2A00),
    onTertiaryContainer = Color(0xFFFFB300),
    background = Bg,
    onBackground = Color(0xFFCFE9EE),
    surface = Color(0xFF0A1118),
    onSurface = Color(0xFFCFE9EE),
    surfaceVariant = Color(0xFF0F1B26),
    onSurfaceVariant = Color(0xFF7FA3B0),
    outline = Color(0xFF1E4A55),
    outlineVariant = Color(0xFF12303A),
    error = AlertRed,
    onError = Color(0xFF1A0006),
    errorContainer = Color(0xFF3A0A14),
    onErrorContainer = Color(0xFFFF8FA3)
)

private val SpyShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = HudShape,
    large = HudShape,
    extraLarge = HudShape
)

private fun monoTypography(): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = Mono),
        displayMedium = b.displayMedium.copy(fontFamily = Mono),
        displaySmall = b.displaySmall.copy(fontFamily = Mono),
        headlineLarge = b.headlineLarge.copy(fontFamily = Mono),
        headlineMedium = b.headlineMedium.copy(fontFamily = Mono),
        headlineSmall = b.headlineSmall.copy(fontFamily = Mono),
        titleLarge = b.titleLarge.copy(fontFamily = Mono),
        titleMedium = b.titleMedium.copy(fontFamily = Mono, letterSpacing = 1.sp),
        titleSmall = b.titleSmall.copy(fontFamily = Mono),
        bodyLarge = b.bodyLarge.copy(fontFamily = Mono),
        bodyMedium = b.bodyMedium.copy(fontFamily = Mono),
        bodySmall = b.bodySmall.copy(fontFamily = Mono),
        labelLarge = b.labelLarge.copy(fontFamily = Mono, letterSpacing = 1.sp),
        labelMedium = b.labelMedium.copy(fontFamily = Mono),
        labelSmall = b.labelSmall.copy(fontFamily = Mono, letterSpacing = 1.sp)
    )
}

@Composable
fun SignalOpsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpyColors, shapes = SpyShapes, typography = monoTypography(), content = content)
}

/** A tapped row in the Signals list, waiting for the user to choose LOCATE or CONNECT. */
private data class Picked(
    val d: SeenDevice, val count: Int, val title: String,
    val linked: Int = 1, val sameName: Int = 0
)

// ---- activity -----------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val store = Store(applicationContext)
        fullscreenStore = store
        Game.init(applicationContext)
        setContent { SignalOpsTheme { App(store) } }
        applyFullscreen(this, store.fullscreenOn)
    }

    private var fullscreenStore: Store? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and permission prompts can bring the system bars back; hide them again.
        if (hasFocus) fullscreenStore?.let { if (it.fullscreenOn) applyFullscreen(this, true) }
    }

    override fun onStart() {
        super.onStart()
        ScanBus.appVisible = true
        OverlayManager.hide()
    }

    override fun onResume() {
        super.onResume()
        ScanBus.overlayGranted.value = Settings.canDrawOverlays(this)
    }

    override fun onStop() {
        ScanBus.appVisible = false
        Game.flush(true)
        super.onStop()
    }
}

private fun openOverlaySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Fires a fake alert after 5 s so you can leave the app and see the overlay. */
private fun testAlert(ctx: Context, store: Store) {
    Handler(Looper.getMainLooper()).postDelayed({
        ScanBus.alerts.tryEmit(AlertEvent("TEST TARGET", "00:00:00:00:00:00", -48, System.currentTimeMillis()))
        if (store.soundOn) AlertEffects.playSound(store.alertSoundType)
        if (store.vibrateOn) AlertEffects.vibrate(ctx)
        if (store.overlayOn && !ScanBus.appVisible && Settings.canDrawOverlays(ctx)) {
            OverlayManager.show(ctx.applicationContext)
        }
    }, 5000)
}

// ---- screen -------------------------------------------------------------------------------

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val phase by ScanBus.phase.collectAsState()
    val devices by ScanBus.devices.collectAsState()
    // Only a boolean is read by the list's content block, so the whole list is not rebuilt on every device update.
    val hasSensors by remember {
        derivedStateOf { devices.values.any { ScanBus.rawAds[it.address]?.sensor != null } }
    }
    val error by ScanBus.error.collectAsState()
    val overlayGranted by ScanBus.overlayGranted.collectAsState()
    val note by ScanBus.note.collectAsState()
    val scanInfo by ScanBus.scanInfo.collectAsState()
    val speed by ScanBus.speedMph.collectAsState()
    var statusOpen by rememberSaveable { mutableStateOf(false) }
    var speedOpen by rememberSaveable { mutableStateOf(false) }
    var speedColor by remember { mutableStateOf(store.speedColor) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var extSeen by remember { mutableStateOf(ScanBus.extCount to ScanBus.codedCount) }
    LaunchedEffect(Unit) {
        while (true) {
            extSeen = ScanBus.extCount to ScanBus.codedCount
            delay(1000)
        }
    }
    val game by Game.state.collectAsState()
    val daily by Daily.state.collectAsState()
    val login by LoginCalendar.state.collectAsState()
    val running = phase != Phase.IDLE
    val trackTarget by ScanBus.trackTarget.collectAsState()
    val linkLog by ScanBus.linkLog.collectAsState()
    var linkLogOpen by remember { mutableStateOf(false) }
    val track by ScanBus.track.collectAsState()
    val trackMuted by ScanBus.trackMuted.collectAsState()
    val vendorCount by Vendors.count.collectAsState()
    val vendorStatus by Vendors.status.collectAsState()
    val vendorBusy by Vendors.busy.collectAsState()
    val vendorBundled by Vendors.bundled.collectAsState()
    val vendorExtra by Vendors.extra.collectAsState()
    val scope = rememberCoroutineScope()

    val watch = remember { mutableStateListOf<String>().also { it.addAll(store.loadWatchlist()) } }
    var mode by remember { mutableStateOf(store.mode) }
    var scanSec by remember { mutableIntStateOf(store.burstScanSec) }
    var pauseSec by remember { mutableIntStateOf(store.burstPauseSec) }
    var sound by remember { mutableStateOf(store.soundOn) }
    var fullscreen by remember { mutableStateOf(store.fullscreenOn) }
    var exitConfirm by remember { mutableStateOf(false) }
    var alertSoundType by remember { mutableIntStateOf(store.alertSoundType) }
    var vibrate by remember { mutableStateOf(store.vibrateOn) }
    var flash by remember { mutableStateOf(store.flashOn) }
    var overlayOn by remember { mutableStateOf(store.overlayOn) }
    var keepAwake by remember { mutableStateOf(store.keepAwakeOn) }
    var autoIdentify by remember { mutableStateOf(store.autoIdentifyOn) }
    var fx by remember { mutableStateOf(store.fxOn) }
    var gameFx by remember { mutableStateOf(store.gameFxOn) }
    var rainFx by remember { mutableStateOf(store.rainOn) }
    var scopeFx by remember { mutableStateOf(store.scopeOn) }
    var crtPowerFx by remember { mutableStateOf(store.crtPowerOn) }
    var curveFx by remember { mutableStateOf(store.curvatureOn) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var profileTab by rememberSaveable { mutableStateOf("PROFILE") }
    var loginOpen by rememberSaveable { mutableStateOf(false) }
    val dex by Dex.state.collectAsState()
    var loginAutoShown by rememberSaveable { mutableStateOf(false) }
    var mergeRot by remember { mutableStateOf(store.mergeRotatingOn) }
    var extScan by remember { mutableStateOf(store.extScanOn) }
    var classicUi by remember { mutableStateOf(store.classicOn) }
    var rawList by remember { mutableStateOf(store.rawListOn) }
    var groupAnon by remember { mutableStateOf(store.groupAnonOn) }
    var autoStart by remember { mutableStateOf(store.autoStartOn) }
    var autoStartDone by rememberSaveable { mutableStateOf(false) }
    remember(store) { ScanBus.ensureNicknames(store); true }
    val nickGen by ScanBus.nicknameGen.collectAsState()
    val nicks: Map<String, String> = remember(nickGen) { ScanBus.nicknames.toMap() }
    var renaming by remember { mutableStateOf<Picked?>(null) }
    var namesOpen by rememberSaveable { mutableStateOf(true) }
    var onePerName by remember { mutableStateOf(store.onePerNameOn) }
    var booted by rememberSaveable { mutableStateOf(!store.fxOn) }
    var termsOk by remember { mutableStateOf(store.termsAcceptedVersion >= TERMS_VERSION) }
    var termsOpen by remember { mutableStateOf(false) }
    // Nothing starts by itself (scan, login calendar) until the terms are accepted and the boot sequence is done.
    val ready = booted && termsOk
    var adv by remember { mutableStateOf<Picked?>(null) }
    var sensorsOpen by rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var radarOpen by rememberSaveable { mutableStateOf(false) }
    var watching by remember { mutableStateOf<Picked?>(null) }
    var pendingConnect by remember { mutableStateOf<String?>(null) }
    val gattInfo by GattProbe.info.collectAsState()
    val view = LocalView.current
    var pairCheck by remember { mutableStateOf(store.pairCheckOn) }
    var trackerOn by remember { mutableStateOf(store.trackerOn) }
    var speedUi by remember { mutableStateOf(store.speedOn) }
    var locTick by remember { mutableIntStateOf(0) }
    var trackersOpen by rememberSaveable { mutableStateOf(true) }
    val trackers by TrackerWatch.status.collectAsState()
    val factsGen by PairingCheck.gattFactsGen.collectAsState()
    var newSound by remember { mutableStateOf(store.newSoundOn) }
    var newSoundType by remember { mutableIntStateOf(store.newSoundType) }
    var alertNew by remember { mutableStateOf(store.alertNewOn) }
    var skipAnon by remember { mutableStateOf(store.newSkipAnonOn) }
    var cfgSummary by remember { mutableStateOf<String?>(null) }
    var useUsb by remember { mutableStateOf(store.useUsb) }
    var usbId by remember { mutableStateOf(store.usbId) }
    val usbDevices = remember { mutableStateListOf<UsbDevice>() }
    var fwSummary by remember { mutableStateOf(RtlFw.summary(ctx)) }
    var adapterNote by remember { mutableStateOf<String?>(null) }
    var adapterOpen by rememberSaveable { mutableStateOf(false) }

    fun refreshUsb() {
        usbDevices.clear()
        usbDevices.addAll(UsbAdapters.list(ctx))
    }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var inputNote by remember { mutableStateOf<String?>(null) }
    var activeAlert by remember { mutableStateOf<AlertEvent?>(null) }
    var targetsOpen by rememberSaveable { mutableStateOf(false) }
    // PIN lock for the target list: pinFlow = null | "unlock" | "new" | "confirm"
    var pinSet by remember { mutableStateOf(PinLock.isSet(store)) }
    var targetsUnlocked by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf<String?>(null) }
    var pinFirst by remember { mutableStateOf("") }
    var screenOffScan by remember { mutableStateOf(store.screenOffScanOn) }
    var signalsOpen by rememberSaveable { mutableStateOf(true) }
    var newMin by rememberSaveable { mutableStateOf(0) }   // 0 = all signals, else "new in the last N minutes"
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var modeOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(MainTab.SCAN) }
    val settingsOpen = tab == MainTab.SETTINGS
    var pinned by remember { mutableStateOf(store.pinned) }
    // devices hidden from Signals (see Hidden); hiddenGen bumps whenever the list changes
    remember(store) { Hidden.load(store); true }
    var hiddenGen by remember { mutableIntStateOf(0) }
    val hiddenKeys = remember(hiddenGen) { Hidden.keys() }
    var hiddenOpen by remember { mutableStateOf(false) }
    var textSize by remember { mutableIntStateOf(store.textSize) }
    var tourOpen by remember { mutableStateOf(false) }
    var sigQuery by rememberSaveable { mutableStateOf("") }
    // "" = everything, "ALERT" / "POSSIBLE" = that tier, "PINNED", "NAMED", or a category key
    var sigFilter by rememberSaveable { mutableStateOf("") }
    var sigSort by rememberSaveable { mutableIntStateOf(0) }
    var sigToolsOpen by rememberSaveable { mutableStateOf(false) }
    var locatorOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<SessionReport?>(null) }
    var reportOn by remember { mutableStateOf(store.sessionReportOn) }
    var pickTargets by remember { mutableStateOf(false) }
    var setPage by rememberSaveable { mutableStateOf("ALERTS") }
    var setQuery by rememberSaveable { mutableStateOf("") }
    var mineTick by remember { mutableIntStateOf(0) }
    val alertsMuted by ScanBus.alertsMuted.collectAsState()
    /** Settings page filter: the chosen page, or every page while searching. */
    fun pg(name: String) = setQuery.isNotBlank() || setPage == "ALL" || setPage == name
    fun togglePin(addr: String) {
        pinned = if (addr in pinned) pinned - addr else pinned + addr
        store.pinned = pinned
    }
    var prevTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (prevTab == MainTab.DEX && tab != MainTab.DEX) Dex.markViewed()
        prevTab = tab
    }
    var vendorsOpen by rememberSaveable { mutableStateOf(false) }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var autoUpd by remember { mutableStateOf(store.autoUpdateCheckOn) }
    var updDismissed by rememberSaveable { mutableStateOf(false) }
    val upd by Updater.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Updater.autoCheck(ctx.applicationContext, store) } }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Vendors.load(ctx.applicationContext) } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Classifier.loadAssets(ctx.applicationContext) }
        cfgSummary = Classifier.summary() ?: Classifier.loadError?.let { "CONFIG ERROR: $it" }
    }
    // Session report for the standby screen: what changed between INITIATE SCAN and STANDBY.
    var sessionStart by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sessionXp by remember { mutableLongStateOf(0L) }
    var sessionAlerts by remember { mutableIntStateOf(0) }
    var sessionKnown by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { ScanBus.alerts.collect { activeAlert = it; sessionAlerts++ } }
    LaunchedEffect(adapterOpen) { refreshUsb() }
    // One-second clock so the "last seen" seconds and the age fade keep moving between scan updates.
    LaunchedEffect(signalsOpen) {
        while (signalsOpen) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(activeAlert) {
        if (activeAlert != null) {
            delay(10_000)
            activeAlert = null
        }
    }
    // CRT power effects: starting a scan powers the screen on; stopping powers it down, then back up to standby.
    var crtEvent by remember { mutableStateOf<CrtEvent?>(null) }
    var prevPhase by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        val was = prevPhase
        prevPhase = phase
        if (was == Phase.IDLE && phase != Phase.IDLE) {
            sessionStart = System.currentTimeMillis()
            sessionXp = game.xp
            sessionAlerts = 0
            sessionKnown = devices.keys.toSet()
        }
        if (was != Phase.IDLE && phase == Phase.IDLE) {
            val dur = System.currentTimeMillis() - sessionStart
            if (reportOn && dur >= 20_000L) {
                val seen = devices.values.filter { it.lastSeen >= sessionStart }
                val ds = Dex.state.value
                val cats = Classifier.categories()
                val peak = seen.maxByOrNull { it.rssi }
                report = SessionReport(
                    durationMs = dur,
                    heard = seen.size,
                    fresh = seen.count { it.address !in sessionKnown },
                    alerts = sessionAlerts,
                    xp = (game.xp - sessionXp).coerceAtLeast(0L),
                    newTypes = ds.categories.filter { c -> ds.entries.any { it.key == "T:" + c.key && it.first >= sessionStart } },
                    rare = seen.mapNotNull { it.cls?.category }.distinct()
                        .map { it to rarityOf(it) }.filter { it.second.ordinal >= Rarity.RARE.ordinal }
                        .sortedByDescending { it.second.ordinal }
                        .map { (k, r) -> (cats.firstOrNull { it.key == k }?.title ?: k) to r },
                    models = seen.mapNotNull { d -> d.cls?.takeIf { isModelReason(it.reason) }?.label }.distinct(),
                    peakName = peak?.let { nicks.nick(it.address) ?: it.name ?: it.cls?.label },
                    peakRssi = peak?.rssi
                )
            }
        }
        if (!fx || !crtPowerFx || !booted) return@LaunchedEffect
        if (was == Phase.IDLE && phase != Phase.IDLE) crtEvent = CrtEvent(offFirst = false)
        else if (was != Phase.IDLE && phase == Phase.IDLE) {
            val seen = devices.values.filter { it.lastSeen >= sessionStart }
            val peak = seen.maxByOrNull { it.rssi }
            crtEvent = CrtEvent(
                offFirst = true,
                summary = if (report != null) null else SessionSummary(
                    durationMs = System.currentTimeMillis() - sessionStart,
                    heard = seen.size,
                    fresh = seen.count { it.address !in sessionKnown },
                    targets = seen.count { it.matched },
                    alerts = sessionAlerts,
                    peakRssi = peak?.rssi,
                    peakName = peak?.let { it.name ?: it.address },
                    xp = (game.xp - sessionXp).coerceAtLeast(0L)
                )
            )
        }
    }

    // Keep the display on only while a scan is actually running.
    DisposableEffect(keepAwake && running) {
        view.keepScreenOn = keepAwake && running
        onDispose { view.keepScreenOn = false }
    }

    // ---- permissions ----
    val scanPerm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
    else Manifest.permission.ACCESS_FINE_LOCATION

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun beginScan() {
        if (store.useUsb) {
            val dev = UsbAdapters.find(ctx, store.usbId)
            if (dev == null) {
                ScanBus.error.value = "USB adapter not found. Plug it in and pick it under Settings > Adapter"
                return
            }
            if (!UsbAdapters.hasPermission(ctx, dev)) {
                UsbAdapters.requestPermission(ctx, dev)
                ScanBus.error.value = "Allow USB access in the popup, then tap INITIATE SCAN again"
                return
            }
        } else if (Build.VERSION.SDK_INT < 31) {
            // Before Android 12, BLE scans return nothing while Location services are off.
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(lm)) {
                ScanBus.error.value = "Turn on Location (needed for BLE scans on this Android version)"
                return
            }
        }
        ScanService.start(ctx)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (granted(scanPerm)) beginScan()
        else ScanBus.error.value = "Scan permission denied"
    }

    fun startScanning() {
        val needed = buildList {
            add(scanPerm)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !granted(it) }
        if (needed.isEmpty()) beginScan() else launcher.launch(needed.toTypedArray())
    }

    // Opens the login calendar by itself once per launch, after the boot sequence, when today's reward is waiting.
    LaunchedEffect(ready) { if (ready && !store.tourDone) tourOpen = true }
    LaunchedEffect(ready, login.canClaim, tourOpen) {
        if (ready && !tourOpen && store.tourDone && login.canClaim && !loginAutoShown) {
            loginAutoShown = true
            loginOpen = true
        }
    }
    // Start scanning by itself once, after the boot sequence, when the app is opened.
    LaunchedEffect(ready) {
        if (ready && !autoStartDone) {
            autoStartDone = true
            if (store.autoStartOn && ScanBus.phase.value == Phase.IDLE) startScanning()
        }
    }

    val connectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val addr = pendingConnect
        pendingConnect = null
        if (addr != null) {
            if (ok) GattProbe.connect(ctx, addr)
            else GattProbe.info.value = GattInfo(addr, GattPhase.FAILED, "Nearby devices permission denied")
        }
    }

    val identLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (!ok) {
            autoIdentify = false
            store.autoIdentifyOn = false
            ScanBus.error.value = "Nearby devices permission denied, so auto-identify is off"
        }
    }

    val locLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locTick++
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            ScanBus.error.value = "Location denied: no speed readout, and tracker detection can only flag trackers that stay near you 20+ minutes"
        }
    }
    val locOk = remember(locTick) {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Opens the device info dialog and connects (asking for the Android 12+ connect permission first). */
    fun startConnect(addr: String) {
        NameResolver.cancel()
        GattProbe.info.value = GattInfo(addr, GattPhase.CONNECTING)
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            pendingConnect = addr
            connectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            GattProbe.connect(ctx, addr)
        }
    }

    fun addEntry(raw: String): Boolean {
        val v = raw.trim()
        if (v.isEmpty()) return false
        val entry = Matcher.normalize(v)
        Matcher.check(entry)?.let { inputError = it; return false }
        if (watch.any { it.equals(entry, ignoreCase = true) }) {
            inputError = "Already in the list"
            return false
        }
        watch.add(entry)
        store.saveWatchlist(watch.toList())
        inputError = null
        return true
    }

    /** Stops any scan and closes the app (the X button in the header). */
    fun exitApp() {
        if (running) ScanService.stop(ctx)
        Game.flush(true)
        ctx.findActivity()?.finishAndRemoveTask()
    }

    /** Removes targets from the main screen (the -N / -MAC buttons), same as the X buttons in Settings. */
    fun removeEntries(entries: List<String>) {
        if (entries.isEmpty()) return
        watch.removeAll(entries)
        store.saveWatchlist(watch.toList())
        inputError = null
        inputNote = "REMOVED: " + entries.joinToString(", ")
    }

    /** Adds a list of entries, skipping duplicates. */
    fun addList(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        var added = 0
        var skipped = 0
        val bad = ArrayList<String>()
        for (p in parts) {
            val entry = Matcher.normalize(p)
            val err = Matcher.check(entry)
            if (err != null) bad.add("$entry ($err)")
            else if (watch.any { it.equals(entry, ignoreCase = true) }) skipped++
            else { watch.add(entry); added++ }
        }
        if (added > 0) store.saveWatchlist(watch.toList())
        inputError = if (bad.isEmpty()) null else "REJECTED: " + bad.joinToString("; ")
        inputNote = "ADDED $added" + (if (skipped > 0) ", SKIPPED $skipped DUPLICATE(S)" else "") +
            (if (bad.isNotEmpty()) ", REJECTED ${bad.size}" else "")
        return added > 0
    }

    /** Adds several entries at once: split on new lines, commas or semicolons. */
    fun addMany(raw: String): Boolean =
        addList(raw.split(Regex("[\n,;]+")).map { it.trim() }.filter { it.isNotEmpty() })

    // ---- target list backup / import (plain text, one entry per line) ----
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(watch.joinToString("\n") + "\n")
                } != null
            }.getOrDefault(false)
            inputError = null
            inputNote = if (ok) "EXPORTED ${watch.size} TARGETS" else "EXPORT FAILED"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                inputNote = "IMPORT FAILED"
            } else {
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                if (!addList(lines)) inputNote = "NOTHING NEW TO IMPORT"
            }
        }
    }

    // ---- Realtek firmware import (for USB adapters such as the TP-Link UB500) ----
    fun importFirmware(uri: android.net.Uri?, config: Boolean) {
        if (uri == null) return
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { RtlFw.import(ctx.applicationContext, it, config) }
                        ?: "COULD NOT OPEN FILE"
                }.getOrElse { "IMPORT FAILED: ${it.message ?: it.javaClass.simpleName}" }
            }
            adapterNote = msg
            fwSummary = RtlFw.summary(ctx)
            if (running && useUsb) ScanService.start(ctx)
        }
    }
    val fwLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFirmware(it, false) }
    val cfgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFirmware(it, true) }

    // ---- vendor database import ----
    val vendorImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                Vendors.busy.value = true
                Vendors.status.value = "IMPORTING..."
                Vendors.status.value = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { Vendors.importStream(ctx.applicationContext, it) }
                        ?: "COULD NOT OPEN FILE"
                }.getOrElse { "IMPORT FAILED: ${it.message ?: it.javaClass.simpleName}" }
                Vendors.busy.value = false
            }
        }
    }

    // Fullscreen draws under the camera cut-out, so push everything down about one line (more if the cut-out is taller).
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val fsTop = if (fullscreen) maxOf(cutoutTop, 24.dp) else 0.dp
    val crt: Modifier = if (fx) Modifier.crtOverlay(SpyColors.primary) else Modifier
    val baseDensity = LocalDensity.current
    val textScale = when (textSize) { 1 -> 1.15f; 2 -> 1.3f; else -> 1f }
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .drawBehind {
                val step = 36.dp.toPx()
                val line = SpyColors.primary.copy(alpha = 0.05f)
                var x = 0f
                while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f
                while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            }
            .then(crt)
    ) {
        Scaffold(containerColor = Color.Transparent) { inner ->
          Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding() + fsTop)) {
            // Header: it stays put while the list scrolls. Title, battery percent, exit button.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                GlitchText("SIGNAL OPS IRL", cs.primary, 20.sp, fx, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "FIELD INTEL UNIT", fontSize = 9.sp, color = cs.onSurfaceVariant, letterSpacing = 1.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(bottom = 3.dp)
                )
                BatteryIndicator(Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "X", color = cs.error, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(bottom = 1.dp)
                        .border(1.dp, cs.error, CutCornerShape(4.dp))
                        .clickable { if (running) exitConfirm = true else exitApp() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            if (fx && scopeFx) ScopeStrip(phase, Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp, vertical = 3.dp))
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 10.dp, end = 10.dp,
                    top = 6.dp,
                    bottom = inner.calculateBottomPadding() + 12.dp + NAV_H
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                // Level / XP bar, streak and quests, login gift: one compact card
                item {
                    StatusStrip(
                        game, daily, login,
                        onProfile = { profileTab = "PROFILE"; profileOpen = true },
                        onDaily = { profileTab = "DAILY"; profileOpen = true },
                        onLogin = { loginOpen = true },
                        dexNew = dex.newCount,
                        onDex = { tab = MainTab.DEX }
                    )
                }

                // Update available (from the automatic or manual check)
                if (!updDismissed && upd.release != null && upd.phase != UpdatePhase.IDLE &&
                    upd.phase != UpdatePhase.UP_TO_DATE && upd.phase != UpdatePhase.CHECKING
                ) {
                    item {
                        HudCard(border = cs.tertiary) {
                            Column(Modifier.padding(10.dp).fillMaxWidth()) {
                                Text("UPDATE AVAILABLE \u00B7 v${upd.release!!.versionName}", fontSize = 12.sp,
                                    color = cs.tertiary, letterSpacing = 1.sp)
                                UpdateControls(upd, onLater = { updDismissed = true; Updater.dismiss() })
                            }
                        }
                    }
                }

                // Alert banner
                activeAlert?.let { a ->
                    item {
                        HudCard(container = cs.errorContainer, border = cs.error) {
                            Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    GlitchText("!! TARGET ACQUIRED !!", cs.error, 13.sp, fx)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(a.label, fontSize = 12.sp, color = cs.onErrorContainer)
                                        SignalBars(a.rssi, cs.error, Modifier.padding(start = 8.dp))
                                    }
                                }
                                if (a.label.startsWith("TRACKER")) {
                                    MiniButton("MINE", cs.secondary) {
                                        TrackerWatch.markMine(store, a.address, a.address); mineTick++; activeAlert = null
                                    }
                                    MiniButton("VIEW", cs.error) { tab = MainTab.TRACKERS; activeAlert = null }
                                }
                                MiniButton("DISMISS", cs.error) { activeAlert = null }
                            }
                        }
                    }
                }

                // Hot / cold tracker
                trackTarget?.let { addr ->
                    item {
                        val seenName = devices[addr]?.name
                        val vend = if (Vendors.isLocal(addr)) null else Vendors.lookup(addr)
                        val live = track?.takeIf { it.address == addr && it.seen }
                        val f = if (live != null) ((live.rssi + 100f) / 65f).coerceIn(0f, 1f) else 0f
                        val heatColor = heatSpectrum(f)
                        val pulseMs = when {
                            f > 0.8f -> 350
                            f > 0.55f -> 650
                            f > 0.3f -> 1000
                            else -> 1500
                        }
                        val pulse by rememberInfiniteTransition(label = "trk").animateFloat(
                            initialValue = 0.35f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(pulseMs, easing = LinearEasing), RepeatMode.Reverse),
                            label = "trkPulse"
                        )
                        HudCard(border = if (fx && live != null) heatColor.copy(alpha = pulse) else cs.secondary) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("// HOT/COLD TRACKER", modifier = Modifier.weight(1f), fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = cs.secondary)
                                MiniButton("FULL SCREEN", cs.secondary) { locatorOpen = true }
                                MiniButton(if (trackMuted) "UNMUTE" else "MUTE", cs.secondary) {
                                    ScanBus.trackMuted.value = !trackMuted
                                }
                                MiniButton("STOP", cs.error) { ScanBus.trackTarget.value = null }
                            }
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    nicks.nick(addr) ?: track?.name ?: seenName ?: devices[addr]?.cls?.label ?: vend?.let { "[$it]" } ?: "(NO NAME)",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(addr, fontSize = 10.sp, color = cs.onSurfaceVariant)
                                devices[addr]?.cls?.let { c ->
                                    Text(
                                        Classifier.tag(c).uppercase(), fontSize = 9.sp,
                                        color = when (c.tier) {
                                            Tier.ALERT -> AlertRed
                                            Tier.POSSIBLE -> AlertAmber
                                            else -> cs.secondary
                                        }
                                    )
                                }
                                when {
                                    !running -> Text("START SCAN TO TRACK", fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold, color = cs.tertiary)
                                    live == null -> Text(
                                        if ((track?.lastSeen ?: 0L) > 0L) "SIGNAL LOST" else "SEARCHING...",
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.tertiary
                                    )
                                    else -> Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            when {
                                                f > 0.8f -> "HOT"
                                                f > 0.55f -> "WARM"
                                                f > 0.3f -> "COOL"
                                                else -> "COLD"
                                            },
                                            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = heatColor
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            when (live.trend) {
                                                1 -> "\u25B2 WARMER"
                                                -1 -> "\u25BC COLDER"
                                                else -> "\u2014 STEADY"
                                            },
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            color = when (live.trend) {
                                                1 -> cs.secondary
                                                -1 -> cs.error
                                                else -> cs.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(bottom = 3.dp)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text("${live.rssi} DBM", fontSize = 10.sp, color = cs.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 3.dp))
                                    }
                                }
                                // proximity meter
                                Box(
                                    Modifier.fillMaxWidth().height(8.dp)
                                        .border(1.dp, cs.outline, BtnShape)
                                        .padding(1.dp)
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).height(6.dp)
                                            .background(heatColor, BtnShape)
                                    )
                                }
                            }
                        }
                    }
                }

                // Radar + status + start/stop
                item {
                    HudCard(border = if (running) cs.primary else cs.outline) {
                      Box {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Radar(phase, devices.values, Modifier.size(58.dp).clickable { radarOpen = true }, fx = fx)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // Line 1: state and counts. Tap it to show or hide the scan details.
                                val hasDetail = running && scanInfo.isNotEmpty()
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = hasDetail) { statusOpen = !statusOpen }
                                        .padding(end = 58.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        when (phase) {
                                            Phase.IDLE -> "OFFLINE"
                                            Phase.SCANNING -> "SCANNING"
                                            Phase.WAITING -> "STANDBY"
                                        },
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = if (running) cs.primary else cs.onSurfaceVariant
                                    )
                                    if (hasDetail) {
                                        Text(if (statusOpen) " \u25B4" else " \u25BE", fontSize = 9.sp, color = cs.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("${devices.size} TRACKED \u00B7 ${watch.size} TARGETS", fontSize = 10.sp, maxLines = 1)
                                }
                                error?.let { Text("ERR: $it", fontSize = 10.sp, color = cs.error) }
                                note?.let { Text(it, fontSize = 10.sp, color = cs.tertiary) }
                                if (alertsMuted) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("ALERTS MUTED", fontSize = 10.sp, color = AlertAmber, modifier = Modifier.weight(1f))
                                        MiniButton("UNMUTE", AlertAmber, vPad = 2.dp) { ScanBus.alertsMuted.value = false }
                                    }
                                }
                                // Line 2: radio counters on the left, current speed on the right (only while moving).
                                val counters = if (running && scanInfo.isNotEmpty())
                                    "EXT ${extSeen.first}" +
                                        (if (extSeen.second > 0) " \u00B7 CODED ${extSeen.second}" else "") +
                                        (if (classicUi) " \u00B7 CLASSIC ${ScanBus.classicAddrs.size}" + (if (ScanBus.classicActive) "*" else "") else "")
                                else ""
                                if (counters.isNotEmpty() || speedUi) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(counters, fontSize = 9.sp, color = cs.onSurfaceVariant, maxLines = 1,
                                            modifier = Modifier.weight(1f))
                                        if (speedUi) {
                                            // Always shown; tap for the full-screen digital speed.
                                            Text(
                                                (speed?.toString() ?: "--") + " MPH",
                                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                color = if ((speed ?: 0) > 0) cs.secondary else cs.onSurfaceVariant,
                                                modifier = Modifier
                                                    .clickable { speedOpen = true }
                                                    .padding(start = 10.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
                                            )
                                        }
                                    }
                                }
                                if (statusOpen && hasDetail) {
                                    Text(scanInfo, fontSize = 9.sp, color = cs.onSurfaceVariant)
                                }
                                Button(
                                    onClick = { if (running) ScanService.stop(ctx) else startScanning() },
                                    modifier = Modifier.fillMaxWidth().height(30.dp),
                                    shape = BtnShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (running) cs.error else cs.primary,
                                        contentColor = if (running) cs.onError else cs.onPrimary
                                    )
                                ) { Text(if (running) "STANDBY" else "INITIATE SCAN", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        // Shield: one-tap switch for ALERT ON NEW DEVICE (same setting as in Settings).
                        IconButton(
                            onClick = { alertNew = !alertNew; store.alertNewOn = alertNew },
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp).size(28.dp)
                        ) {
                            val shieldColor = if (alertNew) cs.primary else cs.onSurfaceVariant
                            Canvas(Modifier.size(18.dp).semantics {
                                contentDescription = if (alertNew) "Alert on new device: on" else "Alert on new device: off"
                            }) {
                                val w = size.width
                                val h = size.height
                                val shield = Path().apply {
                                    moveTo(w * 0.5f, h * 0.04f)
                                    lineTo(w * 0.90f, h * 0.20f)
                                    lineTo(w * 0.90f, h * 0.50f)
                                    cubicTo(w * 0.90f, h * 0.76f, w * 0.72f, h * 0.90f, w * 0.5f, h * 0.98f)
                                    cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.76f, w * 0.10f, h * 0.50f)
                                    lineTo(w * 0.10f, h * 0.20f)
                                    close()
                                }
                                if (alertNew) {
                                    drawPath(shield, shieldColor)
                                    val tick = Path().apply {
                                        moveTo(w * 0.32f, h * 0.50f)
                                        lineTo(w * 0.45f, h * 0.63f)
                                        lineTo(w * 0.69f, h * 0.36f)
                                    }
                                    drawPath(tick, Color.Black, style = Stroke(width = w * 0.11f))
                                } else {
                                    drawPath(shield, shieldColor, style = Stroke(width = w * 0.09f))
                                }
                            }
                        }
                        IconButton(
                            onClick = { tab = MainTab.SETTINGS },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = cs.primary,
                                modifier = Modifier.size(17.dp))
                        }
                      }
                    }
                }

                // Trackers that keep showing up: a short warning here, the full list on the TRACKERS tab
                if (trackerOn && trackers.any { it.level != TrackLevel.WATCHING }) {
                    item {
                        val bad = trackers.filter { it.level != TrackLevel.WATCHING }
                        val worst = bad.first().level
                        val wc = if (worst == TrackLevel.FOLLOWING) AlertRed else AlertAmber
                        HudCard(border = wc) {
                            Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("\u26A0 TRACKER ${worst.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = wc)
                                    Text(
                                        if (bad.size == 1) "${bad.first().label} keeps showing up around you."
                                        else "${bad.size} trackers keep showing up around you.",
                                        fontSize = 12.sp, color = cs.onSurface
                                    )
                                }
                                MiniButton("VIEW", wc) { tab = MainTab.TRACKERS }
                            }
                        }
                    }
                }

                // Sensors decoded from advertisements
                if (hasSensors) {
                    item {
                        val sensorRows = devices.values
                            .mapNotNull { d -> ScanBus.rawAds[d.address]?.sensor?.let { sr -> d to sr } }
                            .sortedByDescending { it.first.rssi }
                        if (sensorRows.isNotEmpty()) HudCard(border = cs.secondary) {
                            SectionHeader("Sensors (${sensorRows.size})", sensorsOpen, { sensorsOpen = !sensorsOpen })
                            if (sensorsOpen) {
                                Text("Tap a sensor for its full advertising data. Graphs show the last 2 hours.", fontSize = 11.sp,
                                    color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
                                sensorRows.take(20).forEach { (d, sr) ->
                                    val sTitle = nicks.nick(d.address) ?: d.name ?: d.cls?.label ?: "(NO NAME) ${d.address.takeLast(5)}"
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clickable { adv = Picked(d, 1, sTitle) }
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(sTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            SignalBars(d.rssi, cs.secondary, Modifier.padding(start = 6.dp))
                                        }
                                        Text(sr.kind, fontSize = 9.sp, color = cs.secondary, letterSpacing = 1.sp)
                                        Text(
                                            sr.values.take(5).joinToString("   ") { "${it.first} ${it.second}" },
                                            fontSize = 12.sp, fontFamily = Mono, color = cs.primary
                                        )
                                        val hist = ScanBus.sensorSeries(d.address)
                                        val useT = hist.count { !it.second.isNaN() } >= 3
                                        val series = hist.map { (if (useT) it.second else it.third).toFloat() }
                                        if (series.count { !it.isNaN() } >= 3) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                                Sparkline(series, if (useT) GameOrange else cs.secondary, Modifier.weight(1f).height(30.dp))
                                                val mins = (hist.last().first - hist.first().first) / 60_000L
                                                Text("  ${if (useT) "\u00B0F" else "%RH"} \u00B7 ${mins}m", fontSize = 10.sp, color = cs.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Signals
                item {
                    // Raw list: every address its own row, nothing merged, grouped or filtered, and a much longer list.
                    val mergeEff = mergeRot && !rawList
                    val q = sigQuery.trim()
                    val hideOn = !rawList && hiddenKeys.isNotEmpty()
                    val hiddenNow = if (hideOn) devices.values.count { Hidden.matches(hiddenKeys, it) } else 0
                    val sigPred: ((SeenDevice) -> Boolean)? = if (rawList || (q.isEmpty() && sigFilter.isEmpty() && !hideOn)) null else { dd ->
                        val okQ = q.isEmpty() || listOfNotNull(
                            nicks.nick(dd.address), dd.name, dd.cls?.label, dd.cls?.title, dd.appleModel, dd.address,
                            if (Vendors.isLocal(dd.address)) null else Vendors.lookup(dd.address)
                        ).any { it.contains(q, ignoreCase = true) } ||
                            dd.companyIds.any { Classifier.companyName(it)?.contains(q, ignoreCase = true) == true }
                        val okF = when (sigFilter) {
                            "" -> true
                            "ALERT" -> dd.cls?.tier == Tier.ALERT
                            "POSSIBLE" -> dd.cls?.tier == Tier.POSSIBLE
                            "HIDDEN" -> Hidden.matches(hiddenKeys, dd)
                            "PINNED" -> dd.address in pinned
                            "NAMED" -> dd.name != null || nicks.nick(dd.address) != null
                            else -> dd.cls?.category == sigFilter
                        }
                        // hidden devices only show under the HIDDEN filter
                        val okH = sigFilter == "HIDDEN" || !hideOn || !Hidden.matches(hiddenKeys, dd)
                        okQ && okF && okH
                    }
                    val signalRows = buildSignalRows(
                        devices.values, mergeEff, onePerName && !rawList, nowMs, nicks,
                        limit = if (rawList) 500 else 40,
                        newWithinMs = if (rawList) 0L else newMin * 60_000L, groupAnon = groupAnon && !rawList,
                        filter = sigPred, sort = if (rawList) 0 else sigSort, pinned = pinned
                    )
                    val prunedN = ScanBus.pruned.get()
                    val hiddenParts = listOfNotNull(
                        signalRows.merged.takeIf { it > 0 }?.let { "MERGED $it" },
                        signalRows.grouped.takeIf { it > 0 }?.let { "GROUPED $it" },
                        signalRows.sameName.takeIf { it > 0 }?.let { "SAME NAME $it" },
                        signalRows.filteredNew.takeIf { it > 0 }?.let { "NEW FILTER $it" },
                        hiddenNow.takeIf { it > 0 && sigFilter != "HIDDEN" }?.let { "HIDDEN BY YOU $it" },
                        (signalRows.searched - if (sigFilter != "HIDDEN") hiddenNow else 0).takeIf { it > 0 }?.let { "SEARCH/FILTER $it" },
                        signalRows.cut.takeIf { it > 0 }?.let { "LIST LIMIT $it" }
                    )
                    // Rows that leave the list dissolve out as "ghosts" instead of just vanishing.
                    val ghosts = remember { mutableStateListOf<RowGhost>() }
                    val rowInfo = remember { HashMap<String, GhostInfo>() }
                    val prevAddrs = remember { ArrayList<String>() }
                    LaunchedEffect(signalsOpen) {
                        if (!signalsOpen) { prevAddrs.clear(); ghosts.clear() }
                    }
                    HudCard {
                        SectionHeader(
                            "Signals (${signalRows.total})", signalsOpen, { signalsOpen = !signalsOpen },
                            summary = if (signalRows.hidden > 0) "HIDDEN ${signalRows.hidden}" else null
                        ) {
                            MiniButton(if (rawList) "RAW ON" else "RAW", if (rawList) AlertAmber else cs.onSurfaceVariant) {
                                rawList = !rawList; store.rawListOn = rawList
                            }
                            MiniButton("CLEAR") { ScanBus.clearDevices() }
                        }
                        if (signalsOpen) {
                            val shown = signalRows.rows
                            val addrs = shown.map { it.d.address }
                            LaunchedEffect(addrs) {
                                if (fx && prevAddrs.isNotEmpty()) {
                                    // Only a device that is really gone from the scanner counts; a filter change does not.
                                    val live = ScanBus.devices.value
                                    val gone = ArrayList<Int>()
                                    prevAddrs.forEachIndexed { i, a -> if (a !in addrs && !live.containsKey(a)) gone.add(i) }
                                    if (gone.isNotEmpty() && gone.size <= 6) {
                                        gone.forEach { i ->
                                            rowInfo[prevAddrs[i]]?.let { ghosts.add(RowGhost(System.nanoTime(), i, it.title, it.color)) }
                                        }
                                    }
                                }
                                prevAddrs.clear()
                                prevAddrs.addAll(addrs)
                            }
                            if (!rawList) Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = sigQuery,
                                        onValueChange = { sigQuery = it.take(40) },
                                        singleLine = true,
                                        textStyle = TextStyle(color = cs.onSurface, fontFamily = Mono, fontSize = 13.sp),
                                        cursorBrush = SolidColor(cs.primary),
                                        modifier = Modifier.weight(1f),
                                        decorationBox = { inner2 ->
                                            Box(Modifier.border(1.dp, if (sigQuery.isNotEmpty()) cs.primary else cs.outline, BtnShape)
                                                .padding(horizontal = 10.dp, vertical = 7.dp)) {
                                                if (sigQuery.isEmpty()) Text("\u2315 Search name, maker, type...", fontSize = 13.sp, color = cs.onSurfaceVariant)
                                                inner2()
                                            }
                                        }
                                    )
                                    if (sigQuery.isNotEmpty()) MiniButton("X", cs.onSurfaceVariant) { sigQuery = "" }
                                    val active = newMin > 0 || sigFilter.isNotEmpty() || sigSort != 0
                                    MiniButton(
                                        (if (active) "FILTERS \u2022" else "FILTERS") + if (sigToolsOpen) " \u25B4" else " \u25BE",
                                        if (active) cs.tertiary else cs.primary
                                    ) { sigToolsOpen = !sigToolsOpen }
                                }
                                if (sigToolsOpen) {
                                    LabeledChips("SHOW", listOf(0, 1, 5, 15), newMin, { if (it == 0) "ALL" else "NEW ${it}M" }) { newMin = it }
                                    LabeledChips("SORT", listOf(0, 1, 2), sigSort, {
                                        when (it) { 0 -> "SIGNAL"; 1 -> "NEWEST"; else -> "NAME" }
                                    }) { sigSort = it }
                                    val catsPresent = devices.values.mapNotNull { it.cls }.filter { it.category != null }
                                        .groupBy { it.category!! }.map { (k, v) -> Triple(k, (v.first().icon + " " + v.first().title).trim(), v.size) }
                                        .sortedByDescending { it.third }
                                    val keys = (listOf("", "PINNED", "NAMED", "POSSIBLE") + (if (hiddenKeys.isNotEmpty()) listOf("HIDDEN") else emptyList()) + catsPresent.map { it.first } +
                                        listOf(sigFilter)).distinct()
                                    ChipRow(keys, sigFilter, { k ->
                                        when (k) {
                                            "" -> "ALL"
                                            "PINNED" -> "\u2605 PINNED"
                                            "NAMED" -> "NAMED"
                                            "POSSIBLE" -> "POSSIBLE"
                                            "HIDDEN" -> "HIDDEN ${hiddenKeys.size}"
                                            else -> catsPresent.firstOrNull { it.first == k }?.let { "${it.second} ${it.third}" } ?: k
                                        }
                                    }) { sigFilter = it }
                                    if (newMin > 0 || sigFilter.isNotEmpty() || sigSort != 0) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            MiniButton("RESET FILTERS", cs.onSurfaceVariant, vPad = 4.dp) { newMin = 0; sigFilter = ""; sigSort = 0 }
                                        }
                                    }
                                }
                            }
                            // Everything the scanner holds that has no row of its own, and why: one line, tap for details.
                            val statsHot = signalRows.hidden > 0 || prunedN > 0
                            val statsColor = if (statsHot) AlertAmber else cs.onSurfaceVariant
                            Column(
                                Modifier.fillMaxWidth().clickable { statsOpen = !statsOpen }
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "HEARD ${signalRows.all} \u00B7 SHOWN ${shown.size} \u00B7 NOT SHOWN ${signalRows.hidden}" +
                                        (if (statsOpen) "  \u25B4" else "  \u25BE"),
                                    fontSize = 9.sp, maxLines = 1, color = statsColor
                                )
                                if (statsOpen) {
                                    if (hiddenParts.isNotEmpty()) {
                                        Text("NOT SHOWN: " + hiddenParts.joinToString(" \u00B7 "), fontSize = 9.sp, color = statsColor)
                                    }
                                    if (prunedN > 0) {
                                        Text("CLEANED UP $prunedN OLD DEVICES", fontSize = 9.sp, color = statsColor)
                                    }
                                    if (signalRows.hidden > 0 && !rawList) {
                                        Text("TAP RAW TO SEE EVERY ADDRESS", fontSize = 9.sp, color = statsColor)
                                    }
                                    Text("TAP A SIGNAL FOR ITS DETAILS", fontSize = 9.sp, color = cs.onSurfaceVariant)
                                }
                            }
                            if (shown.isEmpty()) {
                                val msg = when {
                                    devices.isEmpty() && !running ->
                                        "Nothing heard yet. Tap INITIATE SCAN above to start listening for the Bluetooth devices around you."
                                    devices.isEmpty() -> "Listening... devices usually appear within a few seconds. Make sure Bluetooth is on."
                                    !rawList && (sigQuery.isNotBlank() || sigFilter.isNotEmpty()) -> "No device matches your search or filter."
                                    newMin > 0 && !rawList -> "No new signals in the last $newMin min."
                                    else -> "No signals."
                                }
                                if (devices.isEmpty()) {
                                    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                                        ListeningRadar(active = running && fx)
                                    }
                                }
                                Text(msg, fontSize = 13.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
                                    textAlign = if (devices.isEmpty()) TextAlign.Center else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp))
                            }
                            mergeGhosts(shown, ghosts).forEach { entry ->
                              if (entry is RowGhost) {
                                key("ghost-${entry.key}") { GhostRow(entry) { ghosts.remove(entry) } }
                                return@forEach
                              }
                              val sd = entry as ShownDevice
                              key(sd.d.address) {
                                val d = sd.d
                                val count = sd.count
                                val tracked = d.address == trackTarget
                                val red = d.matched && !tracked
                                val local = Vendors.isLocal(d.address)
                                val vendor = if (local) null else Vendors.lookup(d.address)
                                val cls = d.cls
                                // Name only; unnamed devices get the last bytes of the MAC so they can be told apart.
                                val nick = if (count > 1) null else nicks.nick(d.address)
                                val appleModel = if (count == 1) d.appleModel else null
                                val appleGeneric = if (count == 1 && d.name == null && cls == null && 0x004C in d.companyIds)
                                    "Apple device  " + anonTag(d) else null
                                val baseTitle = nick ?: d.name ?: appleModel ?: cls?.label ?: appleGeneric
                                    ?: vendor?.let { "[$it]" }
                                    ?: "(NO NAME) ${d.address.takeLast(5)}"
                                // Several unnamed devices of one kind now get their own rows, so tell them apart.
                                val anonOne = count == 1 && nick == null && isAnonRow(d)
                                val title = if (anonOne) anonLabel(baseTitle) + "  " + anonTag(d) else baseTitle
                                val iconTitle = cls?.icon?.takeIf { it.isNotEmpty() }?.let { "$it $title" } ?: title
                                val shownTitle = if (count > 1) "$iconTitle  \u00D7$count"
                                else iconTitle +
                                    (if (sd.sameName > 0) "  \u2261${sd.sameName + 1}" else "") +
                                    (if (mergeEff && sd.linked > 1) "  \u21BB${sd.linked}" else "") +
                                    (when (d.transport) { 2 -> "  [BT]"; 3 -> "  [LE+BT]"; else -> "" })
                                val titleColor = when {
                                    red -> cs.onErrorContainer
                                    cls?.tier == Tier.ALERT -> AlertRed
                                    cls?.tier == Tier.POSSIBLE -> AlertAmber
                                    nick != null -> cs.secondary
                                    else -> cs.onSurface
                                }
                                rowInfo[d.address] = GhostInfo(shownTitle, titleColor)
                                val rarityColor = cls?.category?.let { rarityOf(it) }?.let { r ->
                                    r.color.copy(alpha = when (r) { Rarity.COMMON -> 0.35f; Rarity.UNCOMMON -> 0.6f; else -> 0.95f })
                                }
                                // Age: rows dim as the device goes quiet (full strength up to 5 s, fading to 35% by 60 s).
                                val ageMs = (nowMs - d.lastSeen).coerceAtLeast(0L)
                                val fade = if (tracked) 1f
                                else 1f - 0.65f * ((ageMs / 1000f - 5f).coerceIn(0f, 55f) / 55f)
                                // New device: a highlight that fades out over ~2 s.
                                val isFresh = d.firstSeen > 0L && System.currentTimeMillis() - d.firstSeen < 2500L
                                val flash = remember { Animatable(if (isFresh) 1f else 0f) }
                                LaunchedEffect(Unit) { if (flash.value > 0f) withContext(FullSpeed) { flash.animateTo(0f, tween(2200)) } }
                                // Entrance: the row opens up and a bright edge wipes it into view.
                                val enter = remember { Animatable(if (fx && isFresh) 0f else 1f) }
                                LaunchedEffect(Unit) {
                                    if (enter.value < 1f) withContext(FullSpeed) { enter.animateTo(1f, tween(ENTRY_MS, easing = LinearEasing)) }
                                }
                                Row(
                                    Modifier
                                        .scanEntry { enter.value }
                                        .fillMaxWidth()
                                        .alpha(fade)
                                        .clickable { picked = Picked(d, count, shownTitle, if (mergeEff) sd.linked else 1, sd.sameName) }
                                        .background(
                                            when {
                                                tracked -> cs.primaryContainer
                                                red -> cs.errorContainer
                                                else -> Color.Transparent
                                            }
                                        )
                                        .drawBehind {
                                            // rarity edge: the colour of the device type's Dex rarity (brighter for rarer types)
                                            if (rarityColor != null) {
                                                drawRect(rarityColor, size = Size(3.dp.toPx(), size.height))
                                                drawRect(
                                                    Brush.horizontalGradient(
                                                        listOf(rarityColor.copy(alpha = 0.10f), Color.Transparent),
                                                        startX = 0f, endX = size.width * 0.35f
                                                    )
                                                )
                                            }
                                            val f = flash.value
                                            if (f > 0f) {
                                                drawRect(cs.primary.copy(alpha = 0.30f * f))
                                                drawRect(cs.primary.copy(alpha = f), size = Size(2.dp.toPx(), size.height))
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            shownTitle, fontSize = 12.sp, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (d.matched) FontWeight.Bold else FontWeight.Normal,
                                            color = titleColor
                                        )
                                        if (count == 1 && d.name == null && 0x004C in d.companyIds && d.appleTypes.isNotEmpty()) {
                                            Text(
                                                "APPLE: " + AdDecoder.appleKinds(d.appleTypes) +
                                                    (d.appleState?.let { " \u00B7 $it" } ?: ""),
                                                fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (count == 1) {
                                            ScanBus.rawAds[d.address]?.sensor?.let { sr ->
                                                Text(sr.summary, fontSize = 9.sp, fontFamily = Mono,
                                                    color = cs.secondary, maxLines = 1)
                                            }
                                        }
                                        if (count == 1 && pairCheck) {
                                            val pv = remember(
                                                d.address, d.clsKey, d.name, factsGen,
                                                ScanBus.connectable.contains(d.address), ScanBus.rawAds.containsKey(d.address)
                                            ) { PairingCheck.assess(d, ScanBus.rawAds[d.address]) }
                                            if (pv.level != PairLevel.NONE) {
                                                Text(
                                                    if (pv.level == PairLevel.OPEN) "PAIRING: OPEN" else "PAIRING: JUST WORKS (LIKELY)",
                                                    fontSize = 9.sp, fontFamily = Mono, maxLines = 1,
                                                    color = if (pv.level == PairLevel.OPEN) AlertRed else AlertAmber
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        roughDistance(d.rssi), fontSize = 9.sp, fontFamily = Mono,
                                        color = cs.onSurfaceVariant, maxLines = 1, softWrap = false,
                                        textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 30.dp).padding(start = 4.dp)
                                    )
                                    Text(
                                        ageText(ageMs), fontSize = 9.sp, fontFamily = Mono,
                                        color = if (ageMs < 5_000L) cs.secondary else cs.onSurfaceVariant,
                                        maxLines = 1, softWrap = false,
                                        textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 24.dp).padding(start = 4.dp)
                                    )
                                    if (count == 1 && d.address in pinned) {
                                        Text("\u2605", fontSize = 13.sp, color = GameGold, modifier = Modifier.padding(start = 4.dp))
                                    }
                                    SignalBars(d.rssi, if (d.matched) AlertRed else cs.primary,
                                        Modifier.padding(start = 6.dp, end = 2.dp))
                                }
                              }
                            }
                        }
                    }
                }
            }
        }

        // Data rain: real packet bytes falling over the main screen while scanning
        if (fx && rainFx) DataRain(active = running, modifier = Modifier.fillMaxSize())

        // ---- settings panel: slides in over the main screen ----
        BackHandler(enabled = tab != MainTab.SCAN) { tab = MainTab.SCAN }
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bg)
                    .statusBarsPadding()
                    .padding(top = fsTop)
                    .navigationBarsPadding()
                    .padding(bottom = NAV_H + 1.dp)
                    .imePadding()
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlitchText("// SETTINGS", cs.primary, 16.sp, fx, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { tab = MainTab.SCAN }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close settings", tint = cs.primary,
                            modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)
                ) {
                  CompositionLocalProvider(LocalSettingsQuery provides setQuery) {
                    HudCard {
                        Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp)) {
                            ActionRow(
                                "☕ SUPPORT THE DEVELOPER",
                                "ENJOYING SIGNAL OPS IRL? BUY ME A COFFEE (OPENS IN YOUR BROWSER)",
                                cs.tertiary
                            ) {
                                try {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(SUPPORT_URL)
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: android.content.ActivityNotFoundException) {
                                    android.widget.Toast.makeText(ctx, "No browser found. Visit $SUPPORT_URL", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = setQuery,
                                onValueChange = { setQuery = it.take(30) },
                                singleLine = true,
                                textStyle = TextStyle(color = cs.onSurface, fontFamily = Mono, fontSize = 13.sp),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner3 ->
                                    Box(Modifier.border(1.dp, if (setQuery.isNotEmpty()) cs.primary else cs.outline, BtnShape)
                                        .padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        if (setQuery.isEmpty()) Text("\u2315 Search settings", fontSize = 13.sp, color = cs.onSurfaceVariant)
                                        inner3()
                                    }
                                }
                            )
                            if (setQuery.isNotEmpty()) MiniButton("X", cs.onSurfaceVariant) { setQuery = "" }
                        }
                        if (setQuery.isBlank()) Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)) {
                            ChipRow(
                                listOf("ALERTS", "SCANNING", "LIST", "DISPLAY", "SECURITY", "DATA", "ABOUT", "ALL"),
                                setPage, { it }
                            ) { setPage = it }
                        } else {
                            Text("Showing every setting that matches \"$setQuery\".", fontSize = 11.sp, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp, top = 4.dp))
                        }
                        if (pg("ALERTS")) {
                        GroupLabel("ALERT")
                        SettingToggle(
                            "SOUND", sound,
                            hint = "THE SOUND FOR TARGET AND OTHER FULL ALERTS. TAP ONE BELOW TO HEAR IT."
                        ) { sound = !sound; store.soundOn = sound }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                            ChipRow(
                                AlertEffects.ALERT_SOUND_NAMES.indices.toList(), alertSoundType,
                                { AlertEffects.ALERT_SOUND_NAMES[it] }
                            ) {
                                alertSoundType = it
                                store.alertSoundType = it
                                AlertEffects.playSound(it)
                            }
                        }
                        SettingToggle("VIBRATE", vibrate) { vibrate = !vibrate; store.vibrateOn = vibrate }
                        SettingToggle("BORDER FLASH", flash) { flash = !flash; store.flashOn = flash }
                        SettingToggle("OVERLAY", overlayOn) {
                            overlayOn = !overlayOn
                            store.overlayOn = overlayOn
                            if (overlayOn && !overlayGranted) openOverlaySettings(ctx)
                        }
                        if (overlayOn && !overlayGranted) {
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("OVERLAY NEEDS PERMISSION", fontSize = 10.sp, color = cs.error,
                                    modifier = Modifier.weight(1f))
                                MiniButton("GRANT", vPad = 4.dp) { openOverlaySettings(ctx) }
                            }
                        }
                        }
                        if (pg("SCANNING")) {
                        GroupLabel("SCREEN")
                        SettingToggle(
                            "SCAN WITH SCREEN OFF", screenOffScan,
                            hint = "ANDROID PAUSES UNFILTERED SCANS WHEN THE SCREEN TURNS OFF. WITH THIS ON, THE SCAN SWITCHES TO FILTERS BUILT FROM " +
                                "YOUR TARGETS (MACS, COMPANY IDS, UUIDS, NAMES ALREADY SEEN), AND KEEPS THE CPU AWAKE " +
                                "WHILE SCANNING. NEW-DEVICE ALERTS AND NAME-PART TARGETS NOT SEEN YET CANNOT WAKE IT. IT RETURNS TO A FULL SCAN WHEN THE SCREEN TURNS ON."
                        ) { screenOffScan = !screenOffScan; store.screenOffScanOn = screenOffScan }
                        if (screenOffScan) {
                            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                            val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (exempt) "BATTERY OPTIMIZATION: OFF (GOOD)" else "BATTERY OPTIMIZATION MAY STOP THE SCAN",
                                    fontSize = 10.sp, color = if (exempt) cs.onSurfaceVariant else cs.error,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!exempt) MiniButton("ALLOW", vPad = 4.dp) {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            }
                        }
                        SettingToggle("KEEP ON WHILE SCANNING", keepAwake) {
                            keepAwake = !keepAwake; store.keepAwakeOn = keepAwake
                        }
                        }
                        if (pg("DISPLAY")) {
                        GroupLabel("DISPLAY")
                        if (settingMatches("TEXT SIZE", "font", "bigger", "larger", "readable")) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                                LabeledChips("TEXT SIZE", listOf(0, 1, 2), textSize, {
                                    when (it) { 0 -> "NORMAL"; 1 -> "LARGE"; else -> "EXTRA LARGE" }
                                }) { textSize = it; store.textSize = it }
                            }
                        }
                        SettingToggle(
                            "SESSION REPORT", reportOn,
                            "A SUMMARY OF NEW DEVICE TYPES, RARE FINDS, MODELS AND XP AFTER EACH SCAN, WITH A SHARE BUTTON."
                        ) { reportOn = !reportOn; store.sessionReportOn = reportOn }
                        SettingToggle(
                            "FULLSCREEN", fullscreen,
                            hint = "HIDES THE STATUS AND NAVIGATION BARS. SWIPE FROM THE SCREEN EDGE TO SEE THEM FOR A MOMENT. " +
                                "THE BATTERY PERCENT STAYS VISIBLE AT THE TOP RIGHT."
                        ) {
                            fullscreen = !fullscreen
                            store.fullscreenOn = fullscreen
                            ctx.findActivity()?.let { applyFullscreen(it, fullscreen) }
                        }
                        SettingToggle("MOVIE FX", fx) { fx = !fx; store.fxOn = fx }
                        SettingToggle(
                            "DATA RAIN", rainFx,
                            "FALLING HEX OF REAL PACKET BYTES WHILE SCANNING. NEEDS MOVIE FX."
                        ) { rainFx = !rainFx; store.rainOn = rainFx }
                        SettingToggle(
                            "OSCILLOSCOPE", scopeFx,
                            "SCROLLING PACKET TRACE UNDER THE TITLE. NEEDS MOVIE FX."
                        ) { scopeFx = !scopeFx; store.scopeOn = scopeFx }
                        SettingToggle(
                            "CRT POWER ON/OFF", crtPowerFx,
                            "SCREEN POWERS UP WHEN A SCAN STARTS AND DOWN WHEN IT STOPS. NEEDS MOVIE FX."
                        ) { crtPowerFx = !crtPowerFx; store.crtPowerOn = crtPowerFx }
                        SettingToggle(
                            "SCREEN CURVATURE", curveFx,
                            "CURVED-GLASS LOOK: DARKENED EDGES, ROUNDED BEZEL CORNERS AND A FAINT GLARE OVER THE WHOLE SCREEN. TOUCHES PASS THROUGH."
                        ) { curveFx = !curveFx; store.curvatureOn = curveFx }
                        SettingToggle(
                            "GAME EFFECTS", gameFx,
                            "CONFETTI CELEBRATIONS FOR LEVEL-UPS, ACHIEVEMENTS, TITLES, QUESTS AND STREAKS, WITH SOUNDS"
                        ) { gameFx = !gameFx; store.gameFxOn = gameFx }
                        }
                        if (pg("SCANNING")) {
                        GroupLabel("START")
                        SettingToggle("SCAN WHEN APP OPENS", autoStart) {
                            autoStart = !autoStart; store.autoStartOn = autoStart
                        }
                        GroupLabel("NAMES")
                        SettingToggle(
                            "AUTO-IDENTIFY UNNAMED", autoIdentify,
                            hint = "BRIEFLY CONNECTS TO ONE UNNAMED DEVICE AT A TIME TO READ ITS NAME. USES BATTERY."
                        ) {
                            autoIdentify = !autoIdentify
                            store.autoIdentifyOn = autoIdentify
                            if (autoIdentify && Build.VERSION.SDK_INT >= 31 &&
                                !granted(Manifest.permission.BLUETOOTH_CONNECT)
                            ) identLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        GroupLabel("RADIO")
                        SettingToggle(
                            "BLE 5 EXTENDED ADVERTS", extScan,
                            hint = "ALSO HEARS DEVICES USING BLE 5 EXTENDED ADVERTISING (AND LONG-RANGE CODED PHY ON A USB ADAPTER) THAT A LEGACY SCAN MISSES. RESTART THE SCAN AFTER CHANGING."
                        ) { extScan = !extScan; store.extScanOn = extScan }
                        SettingToggle(
                            "CLASSIC BLUETOOTH DISCOVERY", classicUi,
                            hint = "ALSO RUNS CLASSIC (BR/EDR) DISCOVERY NEXT TO THE BLE SCAN, TO LIST HEADSETS, SPEAKERS, CAR KITS, PHONES AND PCS THAT NEVER ADVERTISE OVER BLE. ROWS ARE TAGGED [BT]. USES MORE BATTERY AND CAN SLOW THE BLE SCAN A LITTLE. IN BURST MODE IT ONLY RUNS DURING SCAN WINDOWS (USE 15 S OR MORE). RESTART THE SCAN AFTER CHANGING."
                        ) { classicUi = !classicUi; store.classicOn = classicUi }
                        }
                        if (pg("LIST")) {
                        GroupLabel("LIST")
                        SettingToggle(
                            "SHOW EVERYTHING RAW", rawList,
                            hint = "NO MERGING OF ROTATING ADDRESSES, NO GROUPING, NO ONE-ROW-PER-NAME, NO NEW-ONLY FILTER, NO CLEAN-UP OF OLD DEVICES, AND A LIST OF UP TO 500 ROWS. EVERY ADDRESS HEARD GETS ITS OWN ROW. THE HIDDEN COUNTER UNDER SIGNALS SHOWS WHAT THE NORMAL LIST LEAVES OUT."
                        ) { rawList = !rawList; store.rawListOn = rawList }
                        SettingToggle(
                            "MERGE ROTATING ADDRESSES", mergeRot,
                            hint = "LINKS ONE DEVICE'S CHANGING RANDOM ADDRESSES (\u21BBN = N SEEN)."
                        ) { mergeRot = !mergeRot; store.mergeRotatingOn = mergeRot }
                        Text(
                            "LINK LOG (${linkLog.size})" + if (linkLogOpen) "  \u25B4" else "  \u25BE",
                            fontSize = 10.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { linkLogOpen = !linkLogOpen }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                        if (linkLogOpen) {
                            if (linkLog.isEmpty()) {
                                Text("NO LINKS YET. HANDOVER = SAME KIND OF ADVERT TOOK OVER WITHIN SECONDS, CORRELATED = A NEW KIND STARTED RIGHT AFTER ANOTHER CHANGED, SPLIT = AN OLD ADDRESS CAME BACK SO THE LINK WAS UNDONE, SKIP = TWO DEVICES FIT, NO GUESS.",
                                    fontSize = 9.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp))
                            }
                            linkLog.forEach { line ->
                                Text(line, fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp))
                            }
                        }
                        SettingToggle(
                            "GROUP UNNAMED DEVICES (\u00D7N)", groupAnon,
                            hint = "ON = UNNAMED DEVICES OF ONE KIND (APPLE, FIND MY...) SHARE ONE ROW WITH A COUNT, SHOWING ONLY THE STRONGEST. OFF = EVERY DEVICE GETS ITS OWN ROW."
                        ) { groupAnon = !groupAnon; store.groupAnonOn = groupAnon }
                        SettingToggle(
                            "ONE ROW PER NAME", onePerName,
                            hint = "ONLY THE STRONGEST DEVICE WHEN NAMES MATCH (\u2261N = N TOTAL)."
                        ) { onePerName = !onePerName; store.onePerNameOn = onePerName }
                        }
                        if (pg("ALERTS")) {
                        GroupLabel("NEW DEVICES")
                        SettingToggle(
                            "NEW-DEVICE SOUND", newSound,
                            hint = "A SHORT SOUND THE FIRST TIME EACH DEVICE IS HEARD. ALSO NEEDS SOUND ON. TAP A SOUND TO HEAR IT."
                        ) { newSound = !newSound; store.newSoundOn = newSound }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                            ChipRow(
                                AlertEffects.NEW_SOUND_NAMES.indices.toList(), newSoundType,
                                { AlertEffects.NEW_SOUND_NAMES[it] }
                            ) {
                                newSoundType = it
                                store.newSoundType = it
                                AlertEffects.playNewDevice(it)
                            }
                        }
                        SettingToggle(
                            "ALERT ON NEW DEVICE", alertNew,
                            hint = "FULL ALERT (SOUND, VIBRATION, BORDER, NOTIFICATION) FOR ANY DEVICE FIRST HEARD AFTER THIS IS TURNED ON. " +
                                "DEVICES ALREADY IN THE LIST DON'T COUNT. IF ON AT SCAN START OR AFTER CLEAR, THE FIRST 8 S ARE LEARNED SILENTLY."
                        ) { alertNew = !alertNew; store.alertNewOn = alertNew }
                        SettingToggle(
                            "SKIP ANONYMOUS RANDOM", skipAnon,
                            hint = "IGNORE UNNAMED RANDOM-ADDRESS DEVICES (FIND MY ETC.) THAT NEVER STOP APPEARING."
                        ) { skipAnon = !skipAnon; store.newSkipAnonOn = skipAnon }
                        }
                        if (pg("SECURITY")) {
                        GroupLabel("SECURITY")
                        SettingToggle(
                            "PAIRING CHECK", pairCheck,
                            hint = "MARKS DEVICES THAT LIKELY PAIR AS JUST WORKS (NO PIN OR CONFIRMATION). AN ESTIMATE FROM WHAT THE DEVICE IS. NOTHING IS SENT TO IT."
                        ) { pairCheck = !pairCheck; store.pairCheckOn = pairCheck }
                        SettingToggle(
                            "SHOW SPEED (MPH)", speedUi,
                            hint = "SHOWS YOUR CURRENT SPEED IN THE STATUS CARD AT ALL TIMES. TAP IT FOR A FULL-SCREEN DIGITAL SPEED (TAP ANYWHERE TO CLOSE). USES GPS, ABOUT ONCE A SECOND, ONLY WHILE THE SCREEN IS ON AND A SCAN IS RUNNING. NOTHING IS STORED."
                        ) {
                            speedUi = !speedUi
                            store.speedOn = speedUi
                            if (speedUi && !locOk) locLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        }
                        SettingToggle(
                            "TRACKER-FOLLOW DETECTION", trackerOn,
                            hint = "WARNS WHEN AN AIRTAG, TILE OR SIMILAR TRACKER KEEPS SHOWING UP AROUND YOU. USES YOUR LOCATION (KEPT IN MEMORY ONLY) TO TELL IF IT MOVES WITH YOU. RESTART THE SCAN AFTER CHANGING THIS."
                        ) {
                            trackerOn = !trackerOn
                            store.trackerOn = trackerOn
                            if (trackerOn && !locOk) locLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        }
                        if (trackerOn && !locOk) {
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("LOCATION OFF: ONLY TRACKERS NEAR YOU 20+ MIN CAN BE FLAGGED", fontSize = 10.sp, color = cs.error,
                                    modifier = Modifier.weight(1f))
                                MiniButton("GRANT", vPad = 4.dp) {
                                    locLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                }
                            }
                        }
                        }
                        if (pg("ALERTS")) {
                        GroupLabel("AUTO-ALERT")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            MiniButton("TEST GAME FX", vPad = 4.dp) {
                                if (gameFx) Game.testEffects()
                                else android.widget.Toast.makeText(ctx, "Turn on GAME EFFECTS first", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            MiniButton("TEST ALERT (5S)", vPad = 4.dp) { testAlert(ctx, store) }
                        }
                        }
                        if (pg("ABOUT")) {
                        GroupLabel("ABOUT")
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            ActionRow("TERMS OF USE", "READ THE TERMS OF RESPONSIBLE USE YOU ACCEPTED", cs.primary) {
                                termsOpen = true
                            }
                        }
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            ActionRow("REPLAY THE TOUR", "THE SHORT WALKTHROUGH SHOWN ON FIRST LAUNCH", cs.secondary) {
                                tourOpen = true
                            }
                        }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (pg("SCANNING") && settingMatches("Adapter", "usb", "radio", "firmware", "realtek")) {
                        SectionHeader(
                            "Adapter", adapterOpen, { adapterOpen = !adapterOpen },
                            summary = if (useUsb) "USB ${usbId.ifEmpty { "?" }}" else "PHONE"
                        )
                        if (adapterOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    readable("WHICH RADIO SCANS. ANDROID ONLY EXPOSES ITS OWN BLUETOOTH, SO AN EXTERNAL " +
                                        "USB ADAPTER IS DRIVEN DIRECTLY OVER USB (OTG)."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                                val usbIds = (listOf("") + usbDevices.map { UsbAdapters.id(it) } + usbId).distinct()
                                LabeledChips(
                                    "RADIO", usbIds, if (useUsb) usbId else "",
                                    { id ->
                                        if (id.isEmpty()) "PHONE"
                                        else usbDevices.firstOrNull { UsbAdapters.id(it) == id }
                                            ?.let { UsbAdapters.label(it) } ?: "$id (UNPLUGGED)"
                                    }
                                ) { sel ->
                                    if (sel.isEmpty()) {
                                        useUsb = false
                                        store.useUsb = false
                                    } else {
                                        useUsb = true
                                        usbId = sel
                                        store.useUsb = true
                                        store.usbId = sel
                                        usbDevices.firstOrNull { UsbAdapters.id(it) == sel }?.let {
                                            if (!UsbAdapters.hasPermission(ctx, it)) UsbAdapters.requestPermission(ctx, it)
                                        }
                                    }
                                    if (running) ScanService.start(ctx)
                                }
                                if (usbDevices.isEmpty()) {
                                    Text("NO USB BLUETOOTH ADAPTER DETECTED. PLUG IT IN, THEN REFRESH.",
                                        fontSize = 10.sp, color = cs.onSurfaceVariant)
                                }
                                Text(fwSummary, fontSize = 10.sp, color = cs.secondary)
                                adapterNote?.let { Text(it, fontSize = 10.sp, color = cs.tertiary) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    MiniButton("REFRESH") { refreshUsb() }
                                    MiniButton("IMPORT FW") { fwLauncher.launch(arrayOf("*/*")) }
                                    MiniButton("IMPORT CFG") { cfgLauncher.launch(arrayOf("*/*")) }
                                    MiniButton("CLEAR FW", cs.error) {
                                        RtlFw.clear(ctx)
                                        fwSummary = RtlFw.summary(ctx)
                                        adapterNote = "FIRMWARE CLEARED"
                                    }
                                }
                                Text(
                                    readable("REALTEK ADAPTERS (TP-LINK UB500 = RTL8761B) LOSE THEIR FIRMWARE WHEN UNPLUGGED, " +
                                        "SO IT IS LOADED AT EACH SCAN START. IMPORT rtl8761bu_fw.bin (AND OPTIONALLY " +
                                        "rtl8761bu_config.bin) FROM /usr/lib/firmware/rtl_bt/ ON LINUX. IF THE FILES END " +
                                        "IN .zst, RUN zstd -d ON THEM FIRST."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                            }
                        }
                        }
                        if (pg("SCANNING") && settingMatches("Scan mode", "burst", "continuous", "battery")) {
                        SectionHeader(
                            "Scan mode", modeOpen, { modeOpen = !modeOpen },
                            summary = if (mode == ScanMode.BURST) "BURST ${scanSec}S/${pauseSec}S" else "CONTINUOUS"
                        )
                        if (modeOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LabeledChips("MODE", ScanMode.values().toList(), mode,
                                    { if (it == ScanMode.CONTINUOUS) "CONTINUOUS" else "BURST" }) {
                                    mode = it
                                    store.mode = it
                                    if (running) ScanService.start(ctx)
                                }
                                if (mode == ScanMode.BURST) {
                                    LabeledChips("SCAN", listOf(3, 5, 10, 15), scanSec, { "${it}S" }) {
                                        scanSec = it; store.burstScanSec = it
                                    }
                                    LabeledChips("PAUSE", listOf(10, 30, 60, 120), pauseSec, { "${it}S" }) {
                                        pauseSec = it; store.burstPauseSec = it
                                    }
                                }
                            }
                        }
                        }
                        if (pg("ALERTS") && settingMatches("Target list", "target", "watch", "pin", "mac")) {
                        val targetsLocked = pinSet && !targetsUnlocked
                        SectionHeader(
                            if (targetsLocked) "Target list \uD83D\uDD12" else "Target list (${watch.size})",
                            targetsOpen && !targetsLocked,
                            {
                                if (targetsLocked) pinFlow = "unlock" else targetsOpen = !targetsOpen
                            }
                        )
                        if (targetsOpen && !targetsLocked) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (pinSet) "PIN LOCK: ON" else "PIN LOCK: OFF",
                                        fontSize = 10.sp, letterSpacing = 1.sp,
                                        color = if (pinSet) cs.primary else cs.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    MiniButton(if (pinSet) "CHANGE PIN" else "SET PIN", vPad = 4.dp) { pinFlow = "new" }
                                    if (pinSet) MiniButton("REMOVE", cs.error, vPad = 4.dp) {
                                        PinLock.clear(store); pinSet = false
                                    }
                                }
                                Text(
                                    readable("NAME, MAC, PREFIX (00:25:DF), OR A RULE LIKE CID:004C, UUID:FE9F, MFG:004C:1005. ONE PER LINE OR COMMA-SEPARATED. TAP A SIGNAL > WATCH FOR... TO BUILD ONE FROM A REAL DEVICE."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BasicTextField(
                                        value = input,
                                        onValueChange = { input = it; inputError = null; inputNote = null },
                                        textStyle = TextStyle(color = cs.onSurface, fontFamily = Mono, fontSize = 13.sp),
                                        cursorBrush = SolidColor(cs.primary),
                                        maxLines = 4,
                                        modifier = Modifier.weight(1f),
                                        decorationBox = { innerField ->
                                            Box(
                                                Modifier
                                                    .border(1.dp, if (inputError != null) cs.error else cs.outline, BtnShape)
                                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                if (input.isEmpty()) {
                                                    Text("NAMES / MACS / RULES", fontSize = 13.sp,
                                                        color = cs.onSurfaceVariant)
                                                }
                                                innerField()
                                            }
                                        }
                                    )
                                    MiniButton("ADD") { if (addMany(input)) input = "" }
                                }
                                (inputError ?: inputNote)?.let {
                                    Text(it, fontSize = 10.sp,
                                        color = if (inputError != null) cs.error else cs.onSurfaceVariant)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    MiniButton("PICK FROM SEEN", cs.secondary) { pickTargets = true }
                                    MiniButton("IMPORT") { importLauncher.launch(arrayOf("*/*")) }
                                    MiniButton("EXPORT") { exportLauncher.launch("signal-ops-targets.txt") }
                                }
                                if (watch.isEmpty()) {
                                    Text("NO TARGETS REGISTERED.", fontSize = 10.sp, color = cs.onSurfaceVariant)
                                }
                                watch.toList().forEach { entry ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                        Text(
                                            Matcher.kind(entry),
                                            fontSize = 9.sp, color = cs.onSurfaceVariant
                                        )
                                        MiniButton("  X  ", cs.error, vPad = 12.dp) {
                                            watch.remove(entry)
                                            store.saveWatchlist(watch.toList())
                                            inputError = null
                                            inputNote = "REMOVED: $entry"
                                        }
                                    }
                                }
                            }
                        }
                        }
                        if (pg("LIST") && settingMatches("Device names", "name", "rename", "nickname")) {
                        SectionHeader("Device names (${nicks.size})", namesOpen, { namesOpen = !namesOpen })
                        if (namesOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (nicks.isEmpty()) {
                                    Text("NO NAMES YET. TAP A SIGNAL AND CHOOSE NAME IT.", fontSize = 10.sp,
                                        color = cs.onSurfaceVariant)
                                }
                                nicks.entries.sortedBy { it.value.lowercase() }.forEach { (a, n) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(n, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, color = cs.secondary)
                                            Text(a, fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant)
                                        }
                                        MiniButton("EDIT", cs.secondary, vPad = 10.dp) {
                                            val dev = devices.values.firstOrNull { it.address.equals(a, ignoreCase = true) }
                                                ?: SeenDevice(a, null, -100, 0L, false)
                                            renaming = Picked(dev, 1, n)
                                        }
                                        MiniButton("  X  ", cs.error, vPad = 12.dp) {
                                            ScanBus.setNickname(store, a, null)
                                        }
                                    }
                                }
                            }
                        }
                        }
                        if (pg("LIST") && settingMatches("Hidden devices", "hide", "hidden", "ignore", "unhide")) {
                        SectionHeader("Hidden devices (${hiddenKeys.size})", hiddenOpen, { hiddenOpen = !hiddenOpen })
                        if (hiddenOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "Hidden devices leave the Signals list and stop new-device alerts. They still count for XP and the Dex, " +
                                        "your target list still alerts on them, and tracker warnings are never hidden. " +
                                        "To hide one, tap it in Signals and choose HIDE. The HIDDEN filter in Signals shows them.",
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                                val list = remember(hiddenGen) { Hidden.list() }
                                if (list.isEmpty()) {
                                    Text("Nothing hidden.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                list.forEach { e ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(e.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            Text((if (e.byName) "BY NAME: " else "BY ADDRESS: ") + e.value, fontSize = 9.sp,
                                                fontFamily = Mono, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        MiniButton("UNHIDE", cs.secondary, vPad = 10.dp) {
                                            Hidden.unhideKey(store, e.key); hiddenGen++
                                        }
                                    }
                                }
                                if (list.size > 1) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        MiniButton("UNHIDE ALL", cs.error, vPad = 6.dp) {
                                            Hidden.clear(store); hiddenGen++
                                            if (sigFilter == "HIDDEN") sigFilter = ""
                                        }
                                    }
                                }
                            }
                        }
                        }
                        if (pg("DATA") && settingMatches("App updates", "update", "version", "github")) {
                        SectionHeader(
                            "App updates", updatesOpen, { updatesOpen = !updatesOpen },
                            summary = "v" + remember { Updater.installedName(ctx) } +
                                if (upd.phase == UpdatePhase.AVAILABLE) " \u00B7 NEW v${upd.release?.versionName}" else ""
                        )
                        if (updatesOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    readable("UPDATES COME FROM THE OFFICIAL GITHUB RELEASES. THE APP DOWNLOADS THE NEW VERSION, " +
                                        "CHECKS IT, AND ANDROID ASKS YOU TO CONFIRM. YOUR DATA AND SETTINGS ARE KEPT."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                            }
                            SettingToggle(
                                "CHECK FOR UPDATES AUTOMATICALLY", autoUpd,
                                "WHEN THE APP OPENS, AT MOST EVERY 6 HOURS. ONLY CONTACTS GITHUB."
                            ) {
                                autoUpd = !autoUpd
                                store.autoUpdateCheckOn = autoUpd
                            }
                            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                                UpdateControls(upd, showCheck = true)
                            }
                        }
                        }
                        if (pg("DATA") && settingMatches("Bluetooth DB", "vendor", "database", "export", "unidentified", "oui", "model")) {
                        SectionHeader(
                            "Bluetooth DB", vendorsOpen, { vendorsOpen = !vendorsOpen },
                            summary = if (vendorCount > 0) "BUILT-IN \u00B7 %,d OUI".format(vendorCount) else "LOADING..."
                        )
                        if (vendorsOpen) {
                            Column(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "PRE-INSTALLED AND FULLY OFFLINE. NOTHING TO DOWNLOAD.",
                                    fontSize = 10.sp, color = cs.primary
                                )
                                Text(
                                    "BLUETOOTH SIG COMPANIES %,d \u00B7 SERVICE UUIDS %,d \u00B7 DEVICE TYPES %,d".format(
                                        BtDb.companyCount, BtDb.uuidCount, BtDb.appearanceCount
                                    ),
                                    fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant
                                )
                                Text(
                                    "MAC PREFIXES (IEEE) %,d BUILT-IN".format(vendorBundled) +
                                        (if (vendorExtra > 0) " + %,d FROM UPDATES".format(vendorExtra) else "") +
                                        (BtDb.built?.let { " \u00B7 LIST DATE $it" } ?: ""),
                                    fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant
                                )
                                Text(
                                    "FAST PAIR EARBUD / SPEAKER MODELS %,d".format(BtDb.fastPairCount),
                                    fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant
                                )
                                val unknownCount = remember(devices) { devices.values.count { UnknownExport.isUnidentified(it) } }
                                Text(
                                    readable("UNIDENTIFIED THIS SESSION: $unknownCount. EXPORT SENDS THEIR ADVERT CLUES (NO FULL MAC " +
                                        "ADDRESSES, PERSONAL NAMES REMOVED) SO NEW RULES CAN BE WRITTEN FOR THEM."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    MiniButton("EXPORT UNIDENTIFIED ($unknownCount)", cs.tertiary) {
                                        Vendors.status.value = runCatching { UnknownExport.share(ctx, devices.values.toList()) }
                                            .getOrElse { "EXPORT FAILED: ${it.message}" }
                                    }
                                }
                                Text(
                                    readable("RANDOMIZED ADDRESSES (MOST PHONES, WATCHES, TRACKERS) HAVE NO MAC ENTRY; THOSE ARE " +
                                        "IDENTIFIED FROM THEIR COMPANY ID, SERVICE UUIDS AND DEVICE TYPE INSTEAD. " +
                                        "OPTIONAL: UPDATE ONLINE OR IMPORT AN IEEE oui.csv / WIRESHARK manuf FILE FOR NEWER PREFIXES."),
                                    fontSize = 11.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant
                                )
                                vendorStatus?.let { Text(it, fontSize = 10.sp, color = cs.secondary) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    if (vendorBusy) {
                                        Text("WORKING...", fontSize = 11.sp, color = cs.tertiary,
                                            modifier = Modifier.padding(8.dp))
                                    } else {
                                        MiniButton("UPDATE ONLINE") {
                                            scope.launch(Dispatchers.IO) {
                                                Vendors.busy.value = true
                                                Vendors.status.value = "CONNECTING..."
                                                Vendors.status.value = Vendors.download(ctx.applicationContext)
                                                Vendors.busy.value = false
                                            }
                                        }
                                        MiniButton("IMPORT FILE") { vendorImportLauncher.launch(arrayOf("*/*")) }
                                        if (vendorExtra > 0) MiniButton("REMOVE UPDATES", cs.error) {
                                            scope.launch(Dispatchers.IO) { Vendors.clear(ctx.applicationContext) }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                  }
                }
            }
          }
        }

        // ---- DEX and TRACKERS tabs, and the bottom navigation ----
        if (tab == MainTab.DEX) TabPage("// SIGNAL DEX", fsTop, fx) { DexContent(dex) }
        if (tab == MainTab.TRACKERS) TabPage("// TRACKERS", fsTop, fx) {
            val mineCount = remember(mineTick, trackers) { TrackerWatch.mineCount }
            TrackersContent(
                trackerOn = trackerOn, locationOk = locOk, running = running, trackers = trackers, mineCount = mineCount,
                onEnable = {
                    trackerOn = true
                    store.trackerOn = true
                    if (!locOk) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                onGrantLocation = {
                    locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                onMine = { t -> TrackerWatch.markMine(store, t.key, t.address); mineTick++ },
                onLocate = { t -> ScanBus.trackTarget.value = t.address; locatorOpen = true },
                onClearMine = { TrackerWatch.clearMine(store); mineTick++ }
            )
        }
        val flagged = trackers.count { it.level != TrackLevel.WATCHING }
        MainNavBar(
            tab,
            buildMap {
                if (dex.newCount > 0) put(MainTab.DEX, "${dex.newCount}")
                if (trackerOn && trackers.isNotEmpty()) put(MainTab.TRACKERS, if (flagged > 0) "!$flagged" else "${trackers.size}")
                if (upd.phase == UpdatePhase.AVAILABLE) put(MainTab.SETTINGS, "1")
            },
            Modifier.align(Alignment.BottomCenter)
        ) { tab = it }

        if (activeAlert != null && flash) FlashingBorder()
        if (fx) activeAlert?.let { a -> TargetLock(a, a.label) }
        // ---- PIN prompts for the target list ----
        LaunchedEffect(settingsOpen) {
            if (!settingsOpen) {
                targetsUnlocked = false
                if (pinSet) targetsOpen = false
            }
        }
        when (pinFlow) {
            "unlock" -> PinDialog(
                title = "TARGET LIST LOCKED",
                hint = "ENTER YOUR PIN TO VIEW OR CHANGE THE TARGET LIST.",
                onSubmit = { p ->
                    val wait = PinLock.waitMs(store)
                    if (wait > 0L) "TOO MANY TRIES. WAIT ${(wait + 999L) / 1000L} S"
                    else if (PinLock.verify(store, p)) {
                        targetsUnlocked = true; targetsOpen = true; pinFlow = null; null
                    } else {
                        val w = PinLock.waitMs(store)
                        if (w > 0L) "WRONG PIN. WAIT ${(w + 999L) / 1000L} S" else "WRONG PIN"
                    }
                },
                onCancel = { pinFlow = null }
            )
            "new" -> PinDialog(
                title = if (pinSet) "NEW PIN" else "SET A PIN",
                hint = "CHOOSE ${PinLock.MIN_LEN} TO ${PinLock.MAX_LEN} DIGITS. IT IS ASKED BEFORE THE TARGET LIST CAN BE VIEWED. " +
                    "IF YOU FORGET IT, ONLY CLEARING THE APP'S DATA REMOVES IT (AND THE LIST).",
                onSubmit = { p -> pinFirst = p; pinFlow = "confirm"; null },
                onCancel = { pinFlow = null }
            )
            "confirm" -> PinDialog(
                title = "CONFIRM PIN",
                hint = "ENTER THE SAME PIN AGAIN.",
                onSubmit = { p ->
                    if (p == pinFirst) {
                        PinLock.set(store, p); pinSet = true; targetsUnlocked = true; pinFlow = null; pinFirst = ""; null
                    } else "PINS DO NOT MATCH"
                },
                onCancel = { pinFlow = null; pinFirst = "" }
            )
        }
        if (radarOpen) {
            BackHandler { radarOpen = false }
            RadarScreen(
                phase = phase,
                devices = devices.values,
                nameOf = { d -> nicks.nick(d.address) ?: d.name ?: d.cls?.label ?: "(NO NAME) ${d.address.takeLast(5)}" },
                trackTarget = trackTarget,
                onOpen = { d ->
                    val t = nicks.nick(d.address) ?: d.name ?: d.cls?.label ?: "(NO NAME) ${d.address.takeLast(5)}"
                    picked = Picked(d, 1, t)
                },
                onClose = { radarOpen = false }
            )
        }
        if (speedOpen) {
            BackHandler { speedOpen = false }
            SpeedScreen(
                speed = speed,
                phase = phase,
                devices = devices.values,
                trackerCount = if (trackerOn) trackers.size else 0,
                trackerFollowing = trackerOn && trackers.any { it.level == TrackLevel.FOLLOWING },
                colorIndex = speedColor,
                onColorChange = { speedColor = it; store.speedColor = it }
            ) { speedOpen = false }
        }
        crtEvent?.let { ev -> CrtPowerOverlay(ev, sound) { crtEvent = null } }
        if (curveFx) CurvedGlass()

        // ---- tapped a signal: the device detail screen ----
        picked?.let { p ->
            val d = p.d
            val single = p.count == 1
            val n = if (single) d.name else null
            val nameHits = if (n == null) emptyList() else watch.filter {
                Matcher.isPlain(it) && !Matcher.isMac(it) && !Matcher.isMacPrefix(it) &&
                    Matcher.kind(it) == "NAME" && Matcher.matches(it, n, d.address)
            }
            val macHit = if (!single) null else watch.firstOrNull { Matcher.isMac(it) && Matcher.matches(it, null, d.address) }
            DeviceDetailScreen(
                d = d, title = p.title, count = p.count, linked = p.linked, sameName = p.sameName,
                nick = nicks.nick(d.address), isTracked = d.address == trackTarget, pinned = d.address in pinned,
                pairCheck = pairCheck,
                tracker = if (trackerOn) trackers.firstOrNull { it.address == d.address } else null,
                nameTargetOn = nameHits.isNotEmpty(), macTargetOn = macHit != null, topPad = fsTop,
                a = DeviceActions(
                    onClose = { picked = null },
                    onLocate = {
                        if (d.address == trackTarget) ScanBus.trackTarget.value = null
                        else { ScanBus.trackTarget.value = d.address; locatorOpen = true }
                        picked = null
                    },
                    onConnect = if (single && d.transport != 2) ({ startConnect(d.address) }) else null,
                    onRename = if (single) ({ renaming = p }) else null,
                    onRaw = { adv = p },
                    onWatchFor = { watching = p },
                    onPin = if (single) ({ togglePin(d.address) }) else null,
                    onNameTarget = if (n != null) ({ if (nameHits.isNotEmpty()) removeEntries(nameHits) else addEntry(n) }) else null,
                    onMacTarget = if (single) ({ if (macHit != null) removeEntries(listOf(macHit)) else addEntry(d.address) }) else null,
                    onMine = if (trackerOn && single && TrackerWatch.isTracker(d)) ({
                        TrackerWatch.markMine(store, if (d.identity != 0) "id${d.identity}" else d.address, d.address)
                        mineTick++
                        picked = null
                    }) else null,
                    onHide = if (single) ({
                        if (Hidden.isHidden(d)) Hidden.unhide(store, d)
                        else Hidden.hide(store, d, nicks.nick(d.address) ?: d.name ?: p.title)
                        hiddenGen++
                        picked = null
                    }) else null,
                    isHidden = single && Hidden.matches(hiddenKeys, d)
                )
            )
        }

        // ---- full-screen locator ----
        trackTarget?.let { addr ->
            if (locatorOpen) {
                LocatorScreen(
                    title = nicks.nick(addr) ?: track?.name ?: devices[addr]?.name ?: devices[addr]?.cls?.label ?: addr,
                    running = running, topPad = fsTop,
                    onClose = { locatorOpen = false },
                    onStop = { ScanBus.trackTarget.value = null; locatorOpen = false }
                )
            }
        }

        // ---- watch for: build a target from what this device advertises ----
        watching?.let { p ->
            val d = p.d
            val sugg = remember(d.address, d.companyIds, d.services, d.appleTypes) {
                Targets.suggest(d, ScanBus.rawAds[d.address])
            }
            AlertDialog(
                onDismissRequest = { watching = null },
                containerColor = cs.surface,
                shape = HudShape,
                title = {
                    Text("WATCH FOR...", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "A TARGET FOUND BY COMPANY ID OR SERVICE ALSO CATCHES THE OTHER DEVICES OF THAT KIND AND THE ONES THAT CHANGE ADDRESS. TAP ONE TO ADD IT.",
                            fontSize = 9.sp, color = cs.onSurfaceVariant
                        )
                        sugg.forEach { sg ->
                            val have = watch.any { it.equals(sg.entry, ignoreCase = true) }
                            ActionRow(
                                if (have) "ADDED" else "+ " + sg.label.substringBefore("  "),
                                sg.label.substringAfter("  ", sg.label) + "\n" + sg.entry,
                                if (have) cs.onSurfaceVariant else cs.secondary
                            ) {
                                if (!have) {
                                    addEntry(sg.entry)
                                    inputNote = "ADDED: ${sg.entry}"
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { MiniButton("DONE", cs.onSurfaceVariant) { watching = null } }
            )
        }

        // ---- name a device ----
        renaming?.let { p ->
            val addr = p.d.address
            val existing = nicks.nick(addr)
            var text by remember(addr) { mutableStateOf(existing ?: "") }
            val firstOctet = addr.take(2).toIntOrNull(16) ?: 0
            val randomAddr = (firstOctet and 0x40) != 0 && Vendors.lookup(addr) == null
            AlertDialog(
                onDismissRequest = { renaming = null },
                containerColor = cs.surface,
                shape = HudShape,
                title = { Text("// NAME DEVICE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(addr, fontSize = 10.sp, fontFamily = Mono, color = cs.onSurfaceVariant)
                        val adv = p.d.name ?: p.d.cls?.label
                        if (adv != null) Text("SEEN AS: $adv", fontSize = 10.sp, color = cs.onSurfaceVariant)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                            placeholder = { Text("YOUR NAME FOR IT", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "SHOWN IN PLACE OF ITS OWN NAME IN THE LIST, ALERTS AND LOCATOR, AND NEVER HIDDEN BY ONE ROW PER NAME. " +
                                "IT DOES NOT CHANGE TARGET MATCHING.",
                            fontSize = 9.sp, color = cs.onSurfaceVariant
                        )
                        if (randomAddr) {
                            Text(
                                "THIS DEVICE USES A RANDOM ADDRESS. ITS NAME FOLLOWS IT WHEN THE ADDRESS CHANGES WHILE THE APP " +
                                    "RUNS, BUT IT IS SAVED PER ADDRESS, SO AFTER A RESTART A NEW ADDRESS MAY NEED NAMING AGAIN.",
                                fontSize = 9.sp, color = cs.tertiary
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (existing != null) {
                            MiniButton("REMOVE", cs.error) {
                                ScanBus.setNickname(store, addr, null)
                                renaming = null
                            }
                        }
                        MiniButton("SAVE", cs.secondary) {
                            ScanBus.setNickname(store, addr, text)
                            renaming = null
                        }
                    }
                },
                dismissButton = { MiniButton("CANCEL", cs.onSurfaceVariant) { renaming = null } }
            )
        }

        // ---- advertising data viewer ----
        adv?.let { p -> AdvDialog(p.d.address, p.title, fx) { adv = null } }

        // ---- connection result ----
        gattInfo?.let { gi ->
            val seen = devices[gi.address]
            val vend = if (Vendors.isLocal(gi.address)) null else Vendors.lookup(gi.address)
            val finished = gi.phase == GattPhase.DONE || gi.phase == GattPhase.FAILED
            AlertDialog(
                onDismissRequest = { GattProbe.close() },
                containerColor = cs.surface,
                shape = HudShape,
                title = {
                    Text("// DEVICE INFO", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(seen?.name ?: seen?.cls?.label ?: vend?.let { "[$it]" } ?: "(NO NAME)",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(gi.address, fontSize = 10.sp, color = cs.onSurfaceVariant)
                        Text(
                            when (gi.phase) {
                                GattPhase.CONNECTING -> "CONNECTING..."
                                GattPhase.DISCOVERING -> "DISCOVERING SERVICES..."
                                GattPhase.READING -> "READING DETAILS..."
                                GattPhase.DONE -> "DONE. DISCONNECTED."
                                GattPhase.FAILED -> "FAILED: ${gi.message ?: "UNKNOWN ERROR"}"
                            },
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = when (gi.phase) {
                                GattPhase.FAILED -> cs.error
                                GattPhase.DONE -> cs.secondary
                                else -> cs.tertiary
                            }
                        )
                        if (useUsb) {
                            Text("CONNECTING USES THE PHONE'S BLUETOOTH, EVEN WHILE SCANNING VIA USB.",
                                fontSize = 9.sp, color = cs.onSurfaceVariant)
                        }

                        // identity values that were read
                        val known = gi.services.flatMap { it.chars }.filter { gi.values.containsKey(it.uuid) }
                        if (known.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("IDENTITY", fontSize = 10.sp, color = cs.primary, letterSpacing = 1.sp)
                            known.forEach { c ->
                                Row {
                                    Text((GattProbe.charName(c.uuid) ?: GattProbe.shortUuid(c.uuid)).uppercase(),
                                        fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(0.45f))
                                    Text(gi.values[c.uuid] ?: "", fontSize = 11.sp, modifier = Modifier.weight(0.55f))
                                }
                            }
                        }

                        // full service / characteristic list
                        if (gi.services.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("SERVICES (${gi.services.size})", fontSize = 10.sp, color = cs.primary,
                                letterSpacing = 1.sp)
                            Text("R READ  W WRITE  w WRITE-NO-RSP  N NOTIFY  I INDICATE  S SIGNED",
                                fontSize = 8.sp, color = cs.onSurfaceVariant)
                            gi.services.forEach { s ->
                                Text(
                                    GattProbe.shortUuid(s.uuid) + (GattProbe.serviceName(s.uuid)?.let { "  ${it.uppercase()}" } ?: ""),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.secondary,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                                s.chars.forEach { c ->
                                    Text(
                                        "  " + GattProbe.shortUuid(c.uuid) +
                                            (GattProbe.charName(c.uuid)?.let { "  $it" } ?: "") +
                                            "  [${c.props}]",
                                        fontSize = 9.sp, color = cs.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (finished) MiniButton("RETRY", cs.secondary) { startConnect(gi.address) }
                        MiniButton("CLOSE") { GattProbe.close() }
                    }
                }
            )
        }
        if (exitConfirm) {
            AlertDialog(
                onDismissRequest = { exitConfirm = false },
                containerColor = cs.surface,
                shape = HudShape,
                title = { Text("// EXIT", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.error) },
                text = { Text("A SCAN IS RUNNING. EXITING STOPS IT AND CLOSES THE APP.", fontSize = 11.sp) },
                confirmButton = { MiniButton("EXIT", cs.error) { exitConfirm = false; exitApp() } },
                dismissButton = { MiniButton("CANCEL") { exitConfirm = false } }
            )
        }
        if (gameFx) GameCelebrations()
        if (loginOpen) LoginCalendarDialog(login) { loginOpen = false }
        if (profileOpen) GameProfileDialog(game, daily, profileTab) { profileOpen = false; Game.markViewed() }
        if (termsOpen) TermsDialog { termsOpen = false }
        if (!termsOk) {
            TermsGate(
                onAccept = {
                    store.termsAcceptedVersion = TERMS_VERSION
                    // Auto-identify connects to other people's devices, so it starts off for everyone on acceptance,
                    // including existing installs that had switched it on. It can be turned back on in Settings > NAMES.
                    autoIdentify = false
                    store.autoIdentifyOn = false
                    termsOk = true
                },
                onDecline = { exitApp() }
            )
        } else if (!booted) BootSequence { booted = true }
        if (tourOpen && termsOk && booted) TourDialog { tourOpen = false; store.tourDone = true }
        report?.let { r -> SessionReportDialog(r, onShare = { shareText(ctx, r.shareText()) }, onDismiss = { report = null }) }
        if (pickTargets) PickTargetsDialog(
            devices.values, nicks, watch.toList(),
            onAddName = { addEntry(it) }, onAddMac = { addEntry(it) }
        ) { pickTargets = false }
    }
    }
}

// ---- components ---------------------------------------------------------------------------

@Composable
fun ActionRow(title: String, sub: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, color.copy(alpha = 0.7f)), BtnShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.sp)
        Text(sub, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The cut-corner panel used everywhere. The shape is only painted (fill + outline), never used to clip the
 * content: a clipped non-rectangular shape also becomes the touch region, and on a very tall card (Settings with
 * a long target list) taps further down stopped registering.
 */
@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    border: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides contentColorFor(container)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind {
                    val cut = 10.dp.toPx()
                    val w = size.width
                    val h = size.height
                    // same outline as the old CutCornerShape: top-right and bottom-left corners cut
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w - cut, 0f)
                        lineTo(w, cut)
                        lineTo(w, h)
                        lineTo(cut, h)
                        lineTo(0f, h - cut)
                        close()
                    }
                    drawPath(path, container)
                    drawPath(path, border, style = Stroke(1.dp.toPx()))
                },
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String, open: Boolean, onToggle: () -> Unit,
    summary: String? = null, trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "// ${title.uppercase()}", modifier = Modifier.weight(1f),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary
        )
        if (summary != null) {
            Text(summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.padding(end = 4.dp))
        }
        trailing()
        Text(
            if (open) "[-]" else "[+]", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun MiniButton(
    text: String, color: Color = MaterialTheme.colorScheme.primary,
    vPad: androidx.compose.ui.unit.Dp = 8.dp, onClick: () -> Unit
) {
    Text(
        text,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = vPad),
        color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp
    )
}

/** Small group label used between blocks of settings. */
@Composable
fun GroupLabel(text: String) {
    if (LocalSettingsQuery.current.isNotBlank()) return
    Text(
        text, fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 1.dp)
    )
}

/** Tiny square-cornered switch that matches the HUD look. */
@Composable
fun MiniSwitch(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    val shape = CutCornerShape(3.dp)
    Box(
        Modifier
            .size(width = 28.dp, height = 14.dp)
            .background(if (on) cs.primaryContainer else Color.Transparent, shape)
            .border(1.dp, if (on) cs.primary else cs.outline, shape)
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .size(8.dp)
                .background(if (on) cs.primary else cs.onSurfaceVariant, CutCornerShape(2.dp))
        )
    }
}

/**
 * One settings line: label on the left, switch on the right. Short hints show under the label; long ones open with
 * the (i) button (all hints open while searching). Hides itself when it doesn't match the Settings search.
 */
@Composable
fun SettingToggle(label: String, on: Boolean, hint: String? = null, onToggle: () -> Unit) {
    if (!settingMatches(label, hint)) return
    val searching = LocalSettingsQuery.current.isNotBlank()
    var open by remember(label) { mutableStateOf(false) }
    val longHint = hint != null && hint.length > 80
    val showHint = hint != null && (!longHint || open || searching)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(label, fontSize = 12.sp, letterSpacing = 0.5.sp)
            if (showHint) {
                Text(readable(hint!!), fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (longHint && !searching) {
            Text(
                if (open) "\u24D8\u25B4" else "\u24D8", fontSize = 14.sp,
                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { open = !open }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        MiniSwitch(on)
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = CutCornerShape(4.dp)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { o ->
            val sel = o == selected
            Text(
                label(o), fontSize = 10.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                color = if (sel) cs.primary else cs.onSurfaceVariant, maxLines = 1,
                modifier = Modifier
                    .background(if (sel) cs.primaryContainer else Color.Transparent, shape)
                    .border(1.dp, if (sel) cs.primary else cs.outline, shape)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun <T> LabeledChips(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp, modifier = Modifier.width(48.dp))
        Box(Modifier.weight(1f)) { ChipRow(options, selected, text, onSelect) }
    }
}

@Composable
fun Radar(phase: Phase, devices: Collection<SeenDevice>, modifier: Modifier = Modifier, fx: Boolean = true) {
    val cyan = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep"
    )
    val dim = if (phase == Phase.IDLE) 0.4f else 1f
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        for (i in 1..4) {
            drawCircle(cyan.copy(alpha = 0.3f * dim), radius = r * i / 4f, center = c,
                style = Stroke(1.dp.toPx()))
        }
        drawLine(cyan.copy(alpha = 0.2f * dim), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(cyan.copy(alpha = 0.2f * dim), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

        if (phase == Phase.SCANNING) {
            for (i in 0 until 24) {
                val a = Math.toRadians((angle - i * 2.5f).toDouble())
                drawLine(
                    cyan.copy(alpha = 0.6f * (1f - i / 24f)), c,
                    Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()),
                    2.dp.toPx()
                )
            }
        }

        if (fx) {
            // compass ticks around the rim
            for (i in 0 until 36) {
                val a = Math.toRadians((i * 10).toDouble())
                val len = if (i % 9 == 0) 0.14f else 0.07f
                drawLine(
                    cyan.copy(alpha = 0.45f * dim),
                    Offset(c.x + r * (1f - len) * cos(a).toFloat(), c.y + r * (1f - len) * sin(a).toFloat()),
                    Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 1.dp.toPx()
                )
            }
        }

        devices.forEach { d ->
            val deg = ((d.address.hashCode() and 0x7FFFFFFF) % 3600) / 10f
            val frac = ((-d.rssi - 35) / 60f).coerceIn(0.08f, 0.95f)
            val rad = Math.toRadians(deg.toDouble())
            val p = Offset(c.x + r * frac * cos(rad).toFloat(), c.y + r * frac * sin(rad).toFloat())
            if (fx && phase == Phase.SCANNING) {
                // phosphor effect: a blip flares when the sweep passes over it, then fades until the next pass
                val behind = (((angle - deg) % 360f) + 360f) % 360f
                val fresh = 1f - behind / 360f
                val glow = fresh * fresh * fresh
                val col = if (d.matched) AlertRed else cyan
                drawCircle(col.copy(alpha = 0.18f + 0.82f * fresh), radius = (2f + 2.5f * glow).dp.toPx(), center = p)
                if (glow > 0.15f) {
                    drawCircle(col.copy(alpha = 0.35f * glow), radius = (5f + 6f * glow).dp.toPx(), center = p)
                }
                if (d.matched) {
                    // expanding alarm ping
                    val ping = ((angle * 2f) % 360f) / 360f
                    drawCircle(
                        AlertRed.copy(alpha = 0.7f * (1f - ping)), radius = (4f + 12f * ping).dp.toPx(),
                        center = p, style = Stroke(1.5.dp.toPx())
                    )
                }
            } else if (d.matched) {
                drawCircle(AlertRed, radius = 4.dp.toPx(), center = p)
            } else {
                drawCircle(cyan.copy(alpha = 0.8f * dim), radius = 2.dp.toPx(), center = p)
            }
        }
    }
}

@Composable
fun FlashingBorder() {
    val t = rememberInfiniteTransition(label = "flash")
    val alpha by t.animateFloat(
        initialValue = 0.15f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Canvas(Modifier.fillMaxSize()) {
        val g = 26.dp.toPx()
        val core = 5.dp.toPx()
        drawRect(AlertRed.copy(alpha = alpha * 0.28f), Offset(g / 2, g / 2),
            Size(size.width - g, size.height - g), style = Stroke(g))
        drawRect(AlertRed.copy(alpha = alpha), Offset(core / 2, core / 2),
            Size(size.width - core, size.height - core), style = Stroke(core))

        val len = 56.dp.toPx()
        val inset = 10.dp.toPx()
        val w = 4.dp.toPx()
        val col = Color.White.copy(alpha = alpha)
        fun br(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(col, Offset(x, y), Offset(x + dx * len, y), w)
            drawLine(col, Offset(x, y), Offset(x, y + dy * len), w)
        }
        br(inset, inset, 1f, 1f)
        br(size.width - inset, inset, -1f, 1f)
        br(inset, size.height - inset, 1f, -1f)
        br(size.width - inset, size.height - inset, -1f, -1f)
    }
}

/** Maps RSSI (dBm) to 1-5 bars. */
fun rssiToBars(rssi: Int): Int = when {
    rssi >= -55 -> 5
    rssi >= -65 -> 4
    rssi >= -75 -> 3
    rssi >= -85 -> 2
    else -> 1
}

/** Heat-map colour for a 0..1 strength: cold blue, cyan, green, amber, hot red. */
private val HEAT_STOPS = listOf(
    0.00f to Color(0xFF2979FF),
    0.25f to Color(0xFF00E5FF),
    0.50f to Color(0xFF39E86B),
    0.75f to Color(0xFFFFC107),
    1.00f to Color(0xFFFF2D55)
)

fun heatSpectrum(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    for (i in 1 until HEAT_STOPS.size) {
        val (p1, c1) = HEAT_STOPS[i]
        if (x <= p1) {
            val (p0, c0) = HEAT_STOPS[i - 1]
            return lerp(c0, c1, (x - p0) / (p1 - p0))
        }
    }
    return HEAT_STOPS.last().second
}

/**
 * Five signal bars coloured like a heat map: each lit bar has its own colour from cold blue (weak, lowest bar)
 * to hot red (strongest), so a strong signal lights the whole spectrum and a weak one only the cool end.
 * [color] is only used when [heat] is false.
 */
@Composable
fun SignalBars(rssi: Int, color: Color, modifier: Modifier = Modifier, heat: Boolean = true) {
    val bars = rssiToBars(rssi)
    val empty = MaterialTheme.colorScheme.outline
    Canvas(modifier.size(width = 24.dp, height = 14.dp)) {
        val bw = 3.5.dp.toPx()
        val gap = 1.5.dp.toPx()
        val corner = CornerRadius(1.2.dp.toPx())
        for (i in 0 until 5) {
            val h = size.height * (0.3f + 0.7f * i / 4f)
            val x = i * (bw + gap)
            val top = size.height - h
            if (i < bars) {
                val c = if (heat) heatSpectrum((i + 0.5f) / 5f) else color
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(c, c.copy(alpha = 0.6f)), startY = top, endY = size.height
                    ),
                    topLeft = Offset(x, top), size = Size(bw, h), cornerRadius = corner
                )
            } else {
                drawRoundRect(empty, Offset(x, top), Size(bw, h), corner, style = Stroke(1.dp.toPx()))
            }
        }
    }
}


/** Status line and buttons for in-app updates (Settings > App updates and the main-screen banner). */
@Composable
fun UpdateControls(upd: UpdateState, showCheck: Boolean = false, onLater: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = ctx.applicationContext
    upd.message?.let {
        Text(it, fontSize = 10.sp, color = if (upd.phase == UpdatePhase.ERROR) cs.error else cs.secondary)
    }
    if (upd.phase == UpdatePhase.DOWNLOADING) {
        if (upd.progress >= 0f) {
            LinearProgressIndicator(progress = { upd.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("${(upd.progress * 100).toInt()}%", fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
    }
    val rel = upd.release
    if (rel != null && (upd.phase == UpdatePhase.AVAILABLE || upd.phase == UpdatePhase.NEEDS_PERMISSION ||
            (upd.phase == UpdatePhase.ERROR && rel.apkUrl.isNotEmpty()))
    ) {
        Text(
            "INSTALLED v${remember { Updater.installedName(ctx) }} \u2192 NEW v${rel.versionName}" +
                (if (rel.apkSize > 0) " \u00B7 %.1f MB".format(rel.apkSize / 1_048_576.0) else ""),
            fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant
        )
        if (rel.notes.isNotEmpty()) {
            Text(
                rel.notes.lines().filter { it.isNotBlank() && !it.startsWith("## Install") }.take(6).joinToString("\n")
                    .replace("**", ""),
                fontSize = 9.sp, lineHeight = 11.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        when (upd.phase) {
            UpdatePhase.CHECKING -> Text("CHECKING...", fontSize = 11.sp, color = cs.tertiary, modifier = Modifier.padding(8.dp))
            UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING -> {}
            UpdatePhase.NEEDS_PERMISSION -> {
                MiniButton("ALLOW INSTALLS", cs.tertiary) { runCatching { ctx.startActivity(Updater.permissionIntent(ctx)) } }
                MiniButton("UPDATE") { scope.launch(Dispatchers.IO) { Updater.downloadAndInstall(app) } }
            }
            UpdatePhase.AVAILABLE -> {
                onLater?.let { MiniButton("LATER", cs.onSurfaceVariant) { it() } }
                MiniButton("UPDATE NOW", cs.tertiary) { scope.launch(Dispatchers.IO) { Updater.downloadAndInstall(app) } }
            }
            UpdatePhase.ERROR -> {
                onLater?.let { MiniButton("LATER", cs.onSurfaceVariant) { it() } }
                if (rel != null && rel.apkUrl.isNotEmpty()) MiniButton("RETRY") { scope.launch(Dispatchers.IO) { Updater.downloadAndInstall(app) } }
                if (showCheck) MiniButton("CHECK NOW") { scope.launch(Dispatchers.IO) { Updater.check(app) } }
            }
            else -> if (showCheck) MiniButton("CHECK NOW") { scope.launch(Dispatchers.IO) { Updater.check(app) } }
        }
    }
}
