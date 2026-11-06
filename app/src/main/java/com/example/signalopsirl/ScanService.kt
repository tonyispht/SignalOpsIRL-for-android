package com.example.signalopsirl

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ScanService : Service() {

    companion object {
        const val ACTION_START = "com.example.signalopsirl.START"
        const val ACTION_STOP = "com.example.signalopsirl.STOP"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_STATUS = 1
        private const val COOLDOWN_MS = 15_000L
        private const val STICKY_MS = 20_000L
        private const val RAW_CAP = 5000
        private const val LEARN_MS = 8_000L
        private const val NEW_SOUND_GAP_MS = 300L
        private const val MAX_FILTERS = 60

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ScanService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScanService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var watch: List<String> = emptyList()
    private val lastAlert = HashMap<String, Long>()

    // New-device sound / alert: keys (address or "idN" for a linked device) already heard this run.
    // Devices in here when ALERT ON NEW DEVICE is switched on are the ones that "were already there".
    private val heardKeys = HashSet<String>()
    private var lastNewSound = 0L
    /** While scanning starts or the list was cleared with the alert on, new devices are learned silently until then. */
    private var learnUntil = 0L

    // The service keeps its own device map and publishes a snapshot to the UI a few times a second,
    // instead of rebuilding and re-rendering the whole list on every advertisement.
    private val devs = HashMap<String, SeenDevice>()
    private var devsDirty = false
    private var clearSeen = 0
    private var btReceiver: BroadcastReceiver? = null
    private var usb: UsbHciScanner? = null

    // Screen-off scanning: Android 8.1+ stops delivering results of an unfiltered scan while the screen is off,
    // so the scan is restarted with filters built from the targets, and a partial wake lock keeps the CPU running.
    private var screenOff = false
    private var screenRx: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * How much of the BLE 5 scan feature set the phone's radio accepted: 0 = extended adverts + all PHYs,
     * 1 = extended adverts only, 2 = legacy only (the old behaviour). Drops a level when the radio refuses.
     */
    private var extLevel = 0
    private var loopGen = 0

    // links the rotating addresses of one device together
    private val identities = IdentityTracker().also { it.log = { m -> ScanBus.noteLink(m) } }
    // Apple model / activity per linked device, so every address of it shows the same label
    private val identApple = HashMap<Int, Pair<String?, String?>>()

    // Byte-pattern targets (MFG:, ADV:, SVCDATA:...) can match one packet of a device and miss the next one,
    // so a hit is kept alive for a few seconds: address -> keep-until time.
    private val stickyUntil = HashMap<String, Long>()

    // auto-identify: address -> (attempts, time of last attempt)
    private val idTries = HashMap<String, Pair<Int, Long>>()

    // hot/cold tracking
    private var trackAddr: String? = null
    private var trackName: String? = null
    private var trackEma = 0f
    private var trackSeen = 0L
    private var lastTick = 0L
    private val trackHist = ArrayDeque<Pair<Long, Float>>()

    // Held as a field because SharedPreferences keeps listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_WATCH) { watch = store.loadWatchlist(); stickyUntil.clear() }
        if (key == Store.KEY_TRACKER || key == Store.KEY_SPEED) syncLocation()
    }

    // Location is used by tracker-follow detection (to tell whether you are moving) and for the speed readout.
    // It stays in memory.
    private var locOn = false

    /** True while the GPS is asked for a fix every second (speed readout on and the screen is on). */
    private var locFast = false
    private var speedEma = 0f
    private var speedAt = 0L

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            TrackerWatch.setFix(l)
            if (l.provider == LocationManager.GPS_PROVIDER) noteSpeed(l)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Turns a GPS fix into the mph shown in the status card: smoothed, and hidden while standing still. */
    private fun noteSpeed(l: Location) {
        if (!store.speedOn || !l.hasSpeed()) return
        // An unreliable speed (large error estimate) is ignored rather than shown.
        if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy() && l.speedAccuracyMetersPerSecond > 2.5f) return
        val now = System.currentTimeMillis()
        val raw = l.speed * 2.23694f                          // metres per second to mph
        speedEma = if (now - speedAt > 4000L) raw else speedEma * 0.5f + raw * 0.5f
        speedAt = now
        // GPS speed jitters around 0-1 mph at rest, so anything under 2 mph reads as standing still (0).
        ScanBus.speedMph.value = if (speedEma >= 2f) Math.round(speedEma) else 0
    }

    /** Called from the 300 ms loop: no fix for a few seconds means the readout is no longer true. */
    private fun expireSpeed(now: Long) {
        if (ScanBus.speedMph.value != null && now - speedAt > 5000L) ScanBus.speedMph.value = null
    }

    @SuppressLint("MissingPermission")
    private fun syncLocation() {
        val mgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val perm = hasLocationPermission()
        val wantTrack = store.trackerOn && perm
        // A fix every second is only worth the battery while you can see the screen.
        val wantFast = store.speedOn && perm && !screenOff
        val want = wantTrack || wantFast
        if (locOn && (!want || wantFast != locFast)) {
            runCatching { mgr.removeUpdates(locListener) }
            locOn = false
        }
        if (!store.speedOn || !perm) ScanBus.speedMph.value = null
        if (want && !locOn) {
            try {
                var any = false
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (!mgr.isProviderEnabled(p)) continue
                    // The network provider has no speed, so it is only used for tracker-follow.
                    if (p == LocationManager.NETWORK_PROVIDER && !wantTrack) continue
                    val fast = wantFast && p == LocationManager.GPS_PROVIDER
                    mgr.requestLocationUpdates(p, if (fast) 1_000L else 10_000L, if (fast) 0f else 10f, locListener, Looper.getMainLooper())
                    mgr.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 60_000L) TrackerWatch.setFix(it) }
                    any = true
                }
                locOn = any
                locFast = wantFast
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Game.init(this)
        ScanBus.ensureNicknames(store)
        TrackerWatch.load(store)
        store.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        createChannels()
        devs.putAll(ScanBus.devices.value)
        for (d in devs.values) { heardKeys.add(d.address); if (d.identity != 0) heardKeys.add("id${d.identity}") }
        clearSeen = ScanBus.clearGen
        registerBtReceiver()
        registerScreenReceiver()
        // Parse the bundled scanner_config.json off the main thread; devices seen before it finishes
        // are classified the next time they advertise (the version number is part of the cache key).
        val app = applicationContext
        Thread { Classifier.loadAssets(app) }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch = store.loadWatchlist()
        if (store.alertNewOn) learnUntil = System.currentTimeMillis() + LEARN_MS
        // Location access in the background needs the service to be declared as a location service too.
        var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 29 && (store.trackerOn || store.speedOn) && hasLocationPermission()) {
            fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIF_STATUS, statusNotification(statusText()), fgType)
        syncLocation()
        beginLoop()
        handler.removeCallbacks(trackLoop)
        handler.post(trackLoop)
        handler.removeCallbacks(publishLoop)
        handler.post(publishLoop)
        handler.removeCallbacks(autoIdLoop)
        handler.postDelayed(autoIdLoop, 3000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(locListener) }
        locOn = false
        handler.removeCallbacksAndMessages(null)
        NameResolver.cancel()
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        btReceiver = null
        screenRx?.let { runCatching { unregisterReceiver(it) } }
        screenRx = null
        publishDevices()
        Game.flush(true)
        stopScanNow()
        releaseWakeLock()
        closeUsb()
        ScanBus.note.value = null
        ScanBus.scanInfo.value = ""
        ScanBus.speedMph.value = null
        ScanBus.phase.value = Phase.IDLE
        ScanBus.track.value = null
        OverlayManager.hide()
        super.onDestroy()
    }

    // ---- scanning -------------------------------------------------------------------------

    private fun beginLoop() {
        handler.removeCallbacksAndMessages(null)
        stopScanNow()
        closeUsb()
        ScanBus.error.value = null
        ScanBus.note.value = null
        extLevel = if (store.extScanOn) 0 else 2
        val gen = ++loopGen

        if (store.useUsb) {
            val dev = UsbAdapters.find(this, store.usbId)
            if (dev == null) {
                fail("USB adapter not found (plug it in and pick it under Settings > Adapter)")
                return
            }
            if (!UsbAdapters.hasPermission(this, dev)) {
                UsbAdapters.requestPermission(this, dev)
                fail("USB access not granted. Allow it in the popup, then start again")
                return
            }
            // Opening can take several seconds (Realtek adapters download firmware), so do it off the main thread.
            ScanBus.phase.value = Phase.WAITING
            updateStatus()
            val s = UsbHciScanner(
                applicationContext, dev,
                extended = store.extScanOn,
                onAdvert = { handle(it) },
                onError = { msg -> fail(msg) },
                onStatus = { msg -> handler.post { if (gen == loopGen) ScanBus.note.value = msg } }
            )
            usb = s
            Thread {
                val err = s.open()
                handler.post {
                    if (gen != loopGen || usb !== s) {
                        s.close()
                    } else if (err != null) {
                        fail(err)
                    } else {
                        ScanBus.scanInfo.value = s.modeText
                        startLoop()
                    }
                }
            }.start()
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is turned off")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("BLE scanner unavailable")
            return
        }
        startLoop()
    }

    private fun startLoop() {
        if (store.mode == ScanMode.CONTINUOUS) startScan() else burstCycle()
    }

    private fun closeUsb() {
        usb?.close()
        usb = null
    }

    private fun burstCycle() {
        startScan()
        handler.postDelayed({
            // Keep scanning (no pause) while a device is being hot/cold tracked.
            if (ScanBus.trackTarget.value != null) {
                burstCycle()
                return@postDelayed
            }
            stopScanNow()
            ScanBus.phase.value = Phase.WAITING
            updateStatus()
            handler.postDelayed({ burstCycle() }, store.burstPauseSec * 1000L)
        }, store.burstScanSec * 1000L)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val u = usb
        if (store.useUsb) {
            if (u == null) return
            u.setScanning(true)
            scanning = true
            ScanBus.phase.value = Phase.SCANNING
            updateStatus()
            startClassic()
            return
        }
        val settings = buildScanSettings()
        val filters = if (screenOff && store.screenOffScanOn) buildScreenOffFilters() else null
        try {
            if (filters != null && filters.isNotEmpty()) scanner?.startScan(filters, settings, callback)
            else scanner?.startScan(null, settings, callback)
            if (store.screenOffScanOn) acquireWakeLock()
            if (screenOff && store.screenOffScanOn) {
                ScanBus.note.value =
                    if (filters.isNullOrEmpty()) "SCREEN OFF: NO FILTERABLE TARGETS, ANDROID MAY PAUSE RESULTS"
                    else "SCREEN OFF: ${filters.size} FILTERS (TARGETS + LAW-ENFORCEMENT SIGNATURES)"
            } else if (ScanBus.note.value?.startsWith("SCREEN OFF") == true) {
                ScanBus.note.value = null
            }
            scanning = true
            ScanBus.phase.value = Phase.SCANNING
            updateStatus()
            startClassic()
        } catch (e: SecurityException) {
            fail("Bluetooth scan permission missing")
        } catch (e: IllegalStateException) {
            fail("Bluetooth error: ${e.message}")
        }
    }

    /**
     * Android only returns legacy (BLE 4.x) advertisements unless the scan asks for everything with setLegacy(false);
     * without it, devices that advertise with BLE 5 extended adverts are never reported at all.
     */
    private fun buildScanSettings(): ScanSettings {
        val b = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        var level = extLevel
        if (level < 2) {
            try {
                b.setLegacy(false)
                if (level == 0) b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            } catch (_: Exception) {
                level = 2
                extLevel = 2
            }
        }
        ScanBus.scanInfo.value = when (level) {
            0 -> "PHONE: LEGACY + EXTENDED, ALL PHYS"
            1 -> "PHONE: LEGACY + EXTENDED"
            else -> "PHONE: LEGACY ONLY"
        }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanNow() {
        stopClassic()
        if (scanning) {
            try {
                val u = usb
                if (u != null) u.setScanning(false) else scanner?.stopScan(callback)
            } catch (_: Exception) {
            }
            scanning = false
        }
        releaseWakeLock()
    }

    // ---- scanning with the screen off -----------------------------------------------------------

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signalops:scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun registerScreenReceiver() {
        screenOff = !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val off = when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> true
                    Intent.ACTION_SCREEN_ON -> false
                    else -> return
                }
                if (off == screenOff) return
                screenOff = off
                syncLocation()
                restartForScreenChange()
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // protected system broadcasts
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        screenRx = rx
    }

    /** The screen turned on or off: swap between the full scan and the filtered one without touching the rest of the loop. */
    @SuppressLint("MissingPermission")
    private fun restartForScreenChange() {
        if (!store.screenOffScanOn || store.useUsb || !scanning) return
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        scanning = false
        startScan()
    }

    /**
     * Hardware-style filters for the screen-off scan. A filter can only match an exact address, an exact name, a
     * manufacturer ID, or a service UUID, so each target entry is reduced to one of those (the app's own matcher still makes
     * the final decision on every packet that gets through):
     *  - MAC targets                       -> address filter
     *  - CID/MFG/COMPANY/APPLE targets     -> manufacturer-ID filter
     *  - UUID/SVC/SVCDATA targets          -> service filter
     *  - EXACT: and NAME:/plain name parts -> exact-name filters, using every full name already heard that contains the text
     *  - MAC prefixes                      -> address filters for every address already heard with that prefix
     *  - devices already flagged           -> their address and name
     *  - the built-in law-enforcement signatures (Flock battery maker ID, Raven services) when auto-alerts are on
     */
    private fun buildScreenOffFilters(): List<ScanFilter> {
        val out = LinkedHashMap<String, ScanFilter>()
        fun add(key: String, make: () -> ScanFilter) {
            if (out.size >= MAX_FILTERS || key in out) return
            runCatching { out[key] = make() }
        }
        fun addr(a: String) {
            val m = a.trim().uppercase().replace('-', ':')
            if (m.length == 17 && BluetoothAdapter.checkBluetoothAddress(m)) {
                add("A$m") { ScanFilter.Builder().setDeviceAddress(m).build() }
            }
        }
        fun name(n: String?) {
            if (n.isNullOrBlank()) return
            add("N$n") { ScanFilter.Builder().setDeviceName(n).build() }
        }
        fun company(id: Int) {
            if (id < 0 || id > 0xFFFF) return
            add("M$id") { ScanFilter.Builder().setManufacturerData(id, byteArrayOf(0), byteArrayOf(0)).build() }
        }
        fun uuid(u: String) {
            val h = u.trim().removePrefix("0x").removePrefix("0X").replace("-", "").uppercase()
            if (h.isEmpty() || h.any { it !in '0'..'9' && it !in 'A'..'F' }) return
            val full = when (h.length) {
                4 -> "0000$h-0000-1000-8000-00805F9B34FB"
                8 -> "$h-0000-1000-8000-00805F9B34FB"
                32 -> "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
                else -> return
            }
            add("U$full") { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(full)).build() }
        }
        fun svcData(u: String) {
            val h = u.trim().removePrefix("0x").removePrefix("0X").replace("-", "").uppercase()
            val full = when (h.length) {
                4 -> "0000$h-0000-1000-8000-00805F9B34FB"
                8 -> "$h-0000-1000-8000-00805F9B34FB"
                else -> return
            }
            add("S$full") {
                ScanFilter.Builder().setServiceData(ParcelUuid.fromString(full), byteArrayOf(0), byteArrayOf(0)).build()
            }
        }
        val seen = devs.values.toList()
        fun namesContaining(t: String) {
            name(t)
            for (d in seen) if (d.name?.contains(t, ignoreCase = true) == true) name(d.name)
        }

        // 1. the target list
        val keyed = Regex("^(!)?([A-Za-z]+):(.*)$", RegexOption.DOT_MATCHES_ALL)
        val gate = Regex("""\s+@\s*-?\d{1,3}\s*$""")
        for (raw in watch) {
            val body = raw.trim().replace(gate, "")
            if (body.isEmpty()) continue
            val parts = body.split(Regex("""\s+&\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            // any one positive, filterable part is a necessary condition of the whole rule
            var done = false
            for (p in parts) {
                val km = keyed.matchEntire(p)
                val key = km?.groupValues?.get(2)?.uppercase()
                val neg = km?.groupValues?.get(1) == "!"
                val v = km?.groupValues?.get(3)?.trim().orEmpty()
                if (km != null && key != null) {
                    if (neg) continue
                    when (key) {
                        "CID", "MFG" -> v.substringBefore(':').trim().removePrefix("0x").removePrefix("0X")
                            .takeIf { it.isNotEmpty() && it.length <= 4 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' } }
                            ?.let { company(it.toInt(16)); done = true }
                        "COMPANY" -> {
                            val spec = v.substringBefore(':').trim()
                            val hex = spec.removePrefix("0x").removePrefix("0X")
                            if (hex.isNotEmpty() && hex.length <= 4 && hex.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' }) {
                                company(hex.toInt(16)); done = true
                            } else if (spec.isNotEmpty()) {
                                Classifier.companyIdsNamed(spec).take(8).forEach { company(it) }; done = true
                            }
                        }
                        "APPLE" -> { company(0x004C); done = true }
                        "UUID", "SVC" -> if (!v.contains('*')) { uuid(v.substringBefore(':')); done = true }
                        "SVCDATA" -> { svcData(v.substringBefore(':')); done = true }
                        "EXACT" -> { name(v); done = true }
                        "NAME" -> { namesContaining(v); done = true }
                        "MAC" -> { addr(v); done = true }
                        "STARTS", "ENDS" -> {
                            for (d in seen) {
                                val n = d.name ?: continue
                                if ((key == "STARTS" && n.startsWith(v, true)) || (key == "ENDS" && n.endsWith(v, true))) name(n)
                            }
                            done = true
                        }
                    }
                } else if (Matcher.isMac(p)) {
                    addr(Matcher.normalizeMac(p)); done = true
                } else if (Matcher.isMacPrefix(p)) {
                    val pre = Matcher.normalizeMac(p)
                    for (d in seen) if (d.address.uppercase().startsWith(pre)) addr(d.address)
                    done = true
                } else if (!p.contains('?') && !p.contains('*')) {
                    namesContaining(p); done = true
                }
                if (done) break
            }
        }

        // 2. devices that are already flagged
        val autoA = store.autoAlertOn
        val autoP = store.autoPossibleOn
        for (d in seen) {
            val c = d.cls
            if (d.matched || (c != null && ((c.tier == Tier.ALERT && autoA) || (c.tier == Tier.POSSIBLE && autoP)))) {
                addr(d.address); name(d.name)
            }
        }

        // 3. built-in law-enforcement signatures
        if (autoA || autoP) {
            company(Classifier.XUNTONG_ID)
            for (u in listOf("3100", "3200", "3300", "3400", "3500")) uuid(u)
        }
        return out.values.toList()
    }

    // ---- classic Bluetooth (BR/EDR) discovery -----------------------------------------------------
    // Runs next to the BLE scan: each discovery lasts about 12 s, then it is restarted while scanning goes on.

    private var classicRx: BroadcastReceiver? = null
    private var classicRun = false

    private val classicKick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!classicRun) return
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            var ok = false
            try {
                if (adapter != null && adapter.isEnabled) ok = adapter.isDiscovering || adapter.startDiscovery()
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
            ScanBus.classicActive = ok
            // startDiscovery() is refused while another discovery or a connection setup is busy: try again soon.
            if (!ok) handler.postDelayed(this, 5_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassic() {
        if (!store.classicOn || classicRun) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_FOUND -> onClassicFound(i)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (classicRun) {
                        ScanBus.classicActive = false
                        handler.removeCallbacks(classicKick)
                        handler.postDelayed(classicKick, 1_200L)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // These are protected system broadcasts (only the system can send them), so exported is safe here.
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        classicRx = rx
        classicRun = true
        handler.removeCallbacks(classicKick)
        handler.post(classicKick)
    }

    @SuppressLint("MissingPermission")
    private fun stopClassic() {
        if (!classicRun && classicRx == null) return
        classicRun = false
        handler.removeCallbacks(classicKick)
        classicRx?.let { runCatching { unregisterReceiver(it) } }
        classicRx = null
        ScanBus.classicActive = false
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun onClassicFound(i: Intent) {
        val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val addr = dev.address ?: return
        val rssiRaw = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        val rssi = if (rssiRaw == Short.MIN_VALUE) -100 else rssiRaw.toInt()
        val cls: BluetoothClass? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
        else i.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
        val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: try { dev.name } catch (_: SecurityException) { null }
        ScanBus.classicAddrs.add(addr)
        handle(Advert(addr, rssi, name, emptyMap(), emptyList()), classic = true, cod = cls?.deviceClass ?: dev.bluetoothClass?.deviceClass ?: -1)
    }

    private fun fail(msg: String) {
        ScanBus.error.value = msg
        stopSelf()
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            rememberDevice(result)
            result.toAdvert()?.let { handle(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r ->
                rememberDevice(r)
                r.toAdvert()?.let { handle(it) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Without this reset, startScan() would think a scan is still running and never retry.
            scanning = false
            // The radio refused the extended-advert / PHY options: step down to simpler settings and try again.
            if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED && extLevel < 2) {
                extLevel++
                handler.postDelayed({ startScan() }, 300)
                return
            }
            ScanBus.error.value = "Scan failed (code $errorCode)"
            // Burst mode retries on its next cycle; continuous mode has none, so retry here.
            if (store.mode == ScanMode.CONTINUOUS &&
                errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED
            ) {
                handler.postDelayed({ startScan() }, 10_000)
            }
        }
    }

    private fun rememberDevice(r: ScanResult) {
        val d = r.device ?: return
        ScanBus.bleDevices[d.address] = d
        // Remember if it has ever advertised as connectable (a later scan-response packet can look non-connectable).
        if (r.isConnectable) ScanBus.connectable.add(d.address)
    }

    private fun ScanResult.toAdvert(): Advert? {
        val addr = device.address ?: return null
        val rec = scanRecord
        val mfg = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { m ->
            for (i in 0 until m.size()) mfg[m.keyAt(i)] = m.valueAt(i) ?: ByteArray(0)
        }
        val svcs = rec?.serviceUuids?.map { it.uuid.toString().replace("-", "").uppercase() } ?: emptyList()
        val ext = Build.VERSION.SDK_INT >= 26 && !isLegacy
        return Advert(
            addr, rssi, rec?.deviceName, mfg, svcs, raw = rec?.bytes,
            ext = ext,
            phy = if (ext) secondaryPhy else 0,
            sid = if (ext) advertisingSid else -1,
            txPower = txPower,
            truncated = ext && dataStatus == ScanResult.DATA_TRUNCATED
        )
    }

    /** Keeps the latest raw advertising data for the viewer and decodes any sensor payload in it. */
    private fun updateRaw(a: Advert, name: String?, now: Long) {
        val raw = a.raw ?: return
        val data = AdDecoder.trim(raw)
        if (data.isEmpty()) return
        val prev = ScanBus.rawAds[a.address]
        val packets: List<ByteArray> = if (!a.rawPart || prev == null) {
            listOf(data)
        } else {
            // USB: advert and scan response arrive separately, so keep the latest of each kind.
            val sig = AdDecoder.signature(data)
            val l = ArrayList<ByteArray>()
            l.add(data)
            prev.packets.forEach { if (l.size < 4 && AdDecoder.signature(it) != sig) l.add(it) }
            l
        }
        val same = prev != null && prev.packets.size == packets.size &&
            packets.indices.all { prev.packets[it].contentEquals(packets[it]) }
        val sensor = if (same && prev != null) prev.sensor
        else AdDecoder.sensor(packets, name) ?: prev?.takeIf { now - it.time < 60_000 }?.sensor
        val tokens = if (same && prev != null) prev.tokens else AdDecoder.tokens(packets)
        ScanBus.rawAds[a.address] = RawAd(packets, now, prev?.first ?: now, (prev?.count ?: 0) + 1, sensor, tokens)
        if (sensor != null) Game.onSensor(a.address)
    }

    /** Does any target (name, MAC, prefix, company ID, UUID, byte pattern...) match this device right now? */
    private fun isTarget(
        addr: String, name: String?, rssi: Int, cids: Set<Int>, svcs: Set<String>, apple: Set<Int>,
        cls: Classification?, now: Long, a: Advert?, transport: Int = 1, cod: Int = -1
    ): Boolean {
        if (watch.isEmpty()) return false
        val input = MatchInput(
            name, addr, rssi, cids, svcs, apple, cls,
            ScanBus.rawAds[addr]?.packets ?: emptyList(), a, transport, cod
        )
        val r = Matcher.eval(watch, input)
        if (r == 2) {
            if (stickyUntil.size > 2000) stickyUntil.values.removeAll { it < now }
            stickyUntil[addr] = now + STICKY_MS
        }
        return r != 0 || (stickyUntil[addr] ?: 0L) > now
    }

    /** [classic] = this came from classic Bluetooth discovery (no advertising data, but maybe a class of device [cod]). */
    private fun handle(a: Advert, classic: Boolean = false, cod: Int = -1) {
        val addr = a.address
        val now = System.currentTimeMillis()
        ScanBus.notePacket(a.rssi, a.raw)
        ScanBus.noteExtended(a)
        syncClear()
        // Results are arriving, so an earlier "Scan failed" message no longer applies.
        if (ScanBus.error.value?.startsWith("Scan failed") == true) ScanBus.error.value = null
        // The name is often only in the scan response, so remember it from earlier packets.
        val prev = devs[addr]
        var name = a.name ?: prev?.name ?: ScanBus.resolvedNames[addr]
        updateRaw(a, name, now)

        // Rotating addresses: if this address looks like the next address of a device we already know, treat
        // it as that device (its name, GATT-read name and locate target carry over).
        var ident: Identity? = null
        if (store.mergeRotatingOn && !store.rawListOn) {
            val tokens = ScanBus.rawAds[addr]?.tokens
            if (tokens != null) {
                val r = identities.observe(addr, tokens, a.rssi, now)
                if (r != null) {
                    ident = r.identity
                    val old = r.oldAddr
                    if (old != null) {
                        if (name == null) name = devs[old]?.name ?: ScanBus.resolvedNames[old]
                        ScanBus.resolvedNames[old]?.let { ScanBus.resolvedNames.putIfAbsent(addr, it) }
                        ScanBus.moveNickname(store, old, addr)
                        if (ScanBus.trackTarget.value == old) {
                            ScanBus.trackTarget.value = addr
                            trackAddr = addr
                        }
                    }
                    if (old != null || r.other != null || r.changed) {
                        ScanBus.identityAddrs[r.identity.id] = identities.addresses(r.identity.id)
                        r.other?.let { ScanBus.identityAddrs[it.id] = identities.addresses(it.id) }
                    }
                }
            }
        }
        val akey = ident?.let { "id${it.id}" } ?: addr
        // Smooth RSSI a little so the sorted list and bars don't flicker.
        val rssi = if (prev != null && now - prev.lastSeen < 30_000) (prev.rssi * 6 + a.rssi * 4) / 10 else a.rssi

        // Apple model (AirPods, Watch) and activity, shared by all addresses linked to one device.
        var appleModel = prev?.appleModel
        var appleState = prev?.appleState
        ident?.let { id -> identApple[id.id]?.let { (m, st) -> appleModel = m ?: appleModel; appleState = st ?: appleState } }
        ScanBus.rawAds[addr]?.packets?.let { AdDecoder.appleInfo(it) }?.let { ai ->
            ai.model?.let { appleModel = it }
            ai.state?.let { appleState = it }
            ident?.let { id ->
                if (identApple.size > 3000) identApple.clear()
                identApple[id.id] = appleModel to appleState
            }
        }

        // Collect what this device advertises (company IDs, service UUIDs, Apple message types);
        // different packets from one device carry different pieces, so keep the union.
        var cids = prev?.companyIds ?: emptySet()
        var svcs = prev?.services ?: emptySet()
        var apple = prev?.appleTypes ?: emptySet()
        for ((id, b) in a.mfg) {
            if (id !in cids) cids = cids + id
            if (id == 0x004C && b.isNotEmpty()) {
                // every message in the payload, not just the first (Nearby Info + Handoff + ... can share one)
                for ((ty, ln) in AdDecoder.appleMessages(b)) {
                    val t = if (ty == 0x02 && ln == 0x15) 0x0215 else ty
                    if (t !in apple) apple = apple + t
                }
            }
        }
        for (u in a.services) if (u !in svcs) svcs = svcs + u

        // Only re-run the classifier when its inputs changed (name, new evidence, config or IEEE db loaded).
        val key = java.util.Objects.hash(name, cids, svcs, apple, Classifier.version, Vendors.count.value)
        var cls = prev?.cls
        if (prev == null || prev.clsKey != key) {
            val vendor = if (Vendors.isLocal(addr)) null else Vendors.lookup(addr)
            cls = Classifier.classify(Evidence(addr, name, cids, svcs, apple, vendor))
        }
        val transport = (prev?.transport ?: 0) or (if (classic) 2 else 1)
        val devCod = if (cod >= 0) cod else prev?.cod ?: -1
        val hit = isTarget(addr, name, rssi, cids, svcs, apple, cls, now, a, transport, devCod)
        val dev = SeenDevice(
            addr, name, rssi, now, hit, cls, cids, svcs, apple, key,
            identity = ident?.id ?: 0, linked = ident?.addrs?.size ?: 1,
            firstSeen = prev?.firstSeen?.takeIf { it > 0L } ?: now,
            transport = transport, cod = devCod,
            appleModel = appleModel, appleState = appleState
        )
        val auto = cls != null &&
            ((cls.tier == Tier.ALERT && store.autoAlertOn) || (cls.tier == Tier.POSSIBLE && store.autoPossibleOn))

        devs[addr] = dev
        devsDirty = true
        Dex.onSeen(dev)   // before the game, so a type it already counted earlier is not announced again
        Game.onDevice(addr, name, cls, hit, Vendors.isLocal(addr))

        if (addr == ScanBus.trackTarget.value) {
            if (a.rssi >= -50) Game.onLocated(addr)
            trackEma = if (trackSeen == 0L || now - trackSeen > 5_000) a.rssi.toFloat()
            else trackEma * 0.65f + a.rssi * 0.35f
            trackSeen = now
            trackName = name
            trackHist.addLast(now to trackEma)
            while (trackHist.isNotEmpty() && trackHist.first().first < now - 4_000) trackHist.removeFirst()
        }

        var alerted = false
        if ((hit || auto) && now - (lastAlert[akey] ?: 0L) >= COOLDOWN_MS) {
            lastAlert[akey] = now
            fireAlert(dev)
            alerted = true
        }
        onFirstHeard(dev, akey, alerted, now)
        if (store.trackerOn && TrackerWatch.isTracker(dev)) {
            val tl = ScanBus.nicknames[addr.uppercase()] ?: name ?: cls?.label ?: "Tracker ${addr.takeLast(5)}"
            TrackerWatch.onSighting(dev, akey, tl, now)?.let { lvl ->
                fireAlert(
                    dev,
                    tag = if (lvl == TrackLevel.FOLLOWING) "TRACKER FOLLOWING YOU" else "TRACKER NEAR YOU 20+ MIN",
                    title = if (lvl == TrackLevel.FOLLOWING) "Tracker may be following you"
                    else "Tracker has been near you a while (location off, can't tell if it moves with you)"
                )
            }
        }
    }

    /**
     * Runs for every advertisement but only acts the first time a device (or the linked device it belongs to) is heard.
     * That plays the chosen new-device sound and, when ALERT ON NEW DEVICE is on, fires the normal full alert.
     * Devices heard before the alert switch was turned on are already in [heardKeys], so they never alert.
     */
    private fun onFirstHeard(d: SeenDevice, akey: String, alerted: Boolean, now: Long) {
        val addr = d.address
        if (akey in heardKeys || addr in heardKeys) {
            heardKeys.add(akey); heardKeys.add(addr)
            return
        }
        heardKeys.add(akey); heardKeys.add(addr)
        if (heardKeys.size > 20_000) heardKeys.clear()
        if (store.newSkipAnonOn && d.name == null && d.cls?.anon == true && d.identity == 0 && Vendors.isLocal(addr)) return
        if (alerted) return // a target / tier alert already made noise for this device
        if (store.alertNewOn && now >= learnUntil) {
            fireAlert(d, isNew = true)
        } else if (store.soundOn && store.newSoundOn && now - lastNewSound >= NEW_SOUND_GAP_MS) {
            lastNewSound = now
            AlertEffects.playNewDevice(store.newSoundType)
        }
    }

    // ---- publishing the device list ---------------------------------------------------------

    private fun syncClear() {
        if (clearSeen != ScanBus.clearGen) {
            devs.clear()
            identities.clear()
            identApple.clear()
            heardKeys.clear()
            if (store.alertNewOn) learnUntil = System.currentTimeMillis() + LEARN_MS
            clearSeen = ScanBus.clearGen
            devsDirty = false
        }
    }

    private fun publishDevices() {
        syncClear()
        if (!devsDirty) return
        devsDirty = false
        val now = System.currentTimeMillis()
        if (store.rawListOn) {
            // Raw list: keep everything. Only a memory cap, dropping the longest-silent addresses first.
            if (devs.size > RAW_CAP) {
                val drop = devs.values.sortedBy { it.lastSeen }.take(devs.size - RAW_CAP * 9 / 10).map { it.address }
                drop.forEach { devs.remove(it) }
                ScanBus.pruned.addAndGet(drop.size)
                ScanBus.rawAds.keys.retainAll(devs.keys)
            }
        } else {
            val before = devs.size
            if (devs.size > 60) {
                // Rotating-address devices (Find My etc.) leave a new entry every few minutes; drop old ones.
                devs.values.removeAll { it.name == null && it.cls?.anon == true && now - it.lastSeen >= 60_000 }
                // Old addresses of a device that has moved on to a new one are of no further use.
                devs.values.removeAll { now - it.lastSeen >= 60_000 && identities.isSuperseded(it.address) }
                ScanBus.rawAds.keys.retainAll(devs.keys)
            }
            if (devs.size > 300) devs.values.removeAll { now - it.lastSeen >= 120_000 }
            if (devs.size < before) ScanBus.pruned.addAndGet(before - devs.size)
        }
        ScanBus.devices.value = HashMap(devs)
    }

    private val publishLoop = object : Runnable {
        override fun run() {
            publishDevices()
            expireSpeed(System.currentTimeMillis())
            if (store.trackerOn) TrackerWatch.publish(System.currentTimeMillis())
            Game.tickScan(ScanBus.phase.value == Phase.SCANNING, System.currentTimeMillis())
            Game.flush()
            handler.postDelayed(this, 300)
        }
    }

    // ---- Bluetooth state --------------------------------------------------------------------

    /** Stops the service (and tells the UI) if Bluetooth is switched off while scanning. */
    private fun registerBtReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i == null || i.action != BluetoothAdapter.ACTION_STATE_CHANGED || store.useUsb) return
                val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                    fail("Bluetooth was turned off")
                }
            }
        }
        ContextCompat.registerReceiver(
            this, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        btReceiver = r
    }

    // ---- hot / cold tracking ----------------------------------------------------------------

    /** Every 100 ms: publish tracker state and play a tick whose rate and pitch rise with signal. */
    private val trackLoop = object : Runnable {
        override fun run() {
            val target = ScanBus.trackTarget.value
            val now = System.currentTimeMillis()
            if (target != trackAddr) {
                trackAddr = target
                trackSeen = 0L
                trackName = null
                lastTick = 0L
                trackHist.clear()
                if (target == null) ScanBus.track.value = null
            }
            if (target != null) {
                val fresh = trackSeen != 0L && now - trackSeen < 3_000
                val ema = trackEma
                var trend = 0
                if (fresh && trackHist.size > 1) {
                    val diff = ema - trackHist.first().second
                    trend = if (diff >= 3f) 1 else if (diff <= -3f) -1 else 0
                }
                ScanBus.track.value = TrackState(
                    target, trackName ?: ScanBus.devices.value[target]?.name,
                    Math.round(ema), trend, trackSeen, fresh
                )
                if (fresh && !ScanBus.trackMuted.value) {
                    val f = ((ema + 100f) / 65f).coerceIn(0f, 1f)
                    val interval = (1200 - 1080 * f).toLong()
                    if (now - lastTick >= interval) {
                        lastTick = now
                        AlertEffects.playTick(450f + 1150f * f)
                    }
                }
            }
            handler.postDelayed(this, 100)
        }
    }

    // ---- auto-identify: read the GATT name of unnamed devices ------------------------------

    private val autoIdLoop = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 2500)
            autoIdentifyStep()
        }
    }

    @SuppressLint("MissingPermission")
    private fun autoIdentifyStep() {
        // Names learned elsewhere (e.g. the manual CONNECT dialog) show up in the list right away,
        // whether or not auto-identify is on.
        if (ScanBus.resolvedNames.isNotEmpty()) {
            devs.values.filter { it.name == null }.mapNotNull { d ->
                ScanBus.resolvedNames[d.address]?.let { d.address to it }
            }.forEach { (a, n) -> applyIdentified(a, n, true) }
        }
        if (!store.autoIdentifyOn || NameResolver.busy) return
        if (ScanBus.phase.value == Phase.IDLE) return
        // Stay out of the way of the manual connect dialog and of hot/cold tracking.
        if (GattProbe.info.value != null || ScanBus.trackTarget.value != null) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val now = System.currentTimeMillis()
        val target = devs.values
            .filter { d ->
                val t = idTries[d.address]
                d.name == null && d.cls?.anon != true &&
                    !(store.mergeRotatingOn && identities.isSuperseded(d.address)) &&
                    now - d.lastSeen < 60_000 &&
                    ScanBus.resolvedNames[d.address] == null &&
                    ScanBus.bleDevices.containsKey(d.address) &&
                    // Devices never seen as connectable get a single try; the rest a few, 2 minutes apart.
                    (t == null || (t.first < (if (ScanBus.connectable.contains(d.address)) 5 else 1) &&
                        now - t.second > 120_000))
            }
            // Untried devices first (so a stubborn one can't starve the rest), then connectable, then strongest.
            .sortedWith(
                compareBy<SeenDevice> { idTries[it.address]?.first ?: 0 }
                    .thenBy { if (ScanBus.connectable.contains(it.address)) 0 else 1 }
                    .thenByDescending { it.rssi }
            )
            .firstOrNull() ?: return
        val addr = target.address
        val dev = ScanBus.bleDevices[addr] ?: return
        idTries[addr] = ((idTries[addr]?.first ?: 0) + 1) to now
        NameResolver.fetch(applicationContext, dev) { name, reached ->
            handler.post { applyIdentified(addr, name, reached) }
        }
    }

    private fun applyIdentified(addr: String, name: String?, reached: Boolean) {
        val now = System.currentTimeMillis()
        if (name == null) {
            // The device answered but has no readable name: don't keep trying.
            if (reached) idTries[addr] = 99 to now
            return
        }
        ScanBus.resolvedNames[addr] = name
        Game.onIdentified(addr)
        val d = devs[addr] ?: return
        val vendor = if (Vendors.isLocal(addr)) null else Vendors.lookup(addr)
        val key = java.util.Objects.hash(name, d.companyIds, d.services, d.appleTypes, Classifier.version, Vendors.count.value)
        val cls = Classifier.classify(Evidence(addr, name, d.companyIds, d.services, d.appleTypes, vendor))
        val hit = isTarget(addr, name, d.rssi, d.companyIds, d.services, d.appleTypes, cls, now, null, d.transport, d.cod)
        val dev = d.copy(name = name, matched = hit, cls = cls, clsKey = key)
        devs[addr] = dev
        devsDirty = true
        val auto = cls != null &&
            ((cls.tier == Tier.ALERT && store.autoAlertOn) || (cls.tier == Tier.POSSIBLE && store.autoPossibleOn))
        val akey = if (d.identity != 0) "id${d.identity}" else addr
        if ((hit || auto) && now - (lastAlert[akey] ?: 0L) >= COOLDOWN_MS) {
            lastAlert[akey] = now
            fireAlert(dev)
        }
    }

    // ---- alerts ---------------------------------------------------------------------------

    private fun fireAlert(d: SeenDevice, isNew: Boolean = false, tag: String? = null, title: String? = null) {
        val base = ScanBus.nicknames[d.address.uppercase()] ?: d.name ?: d.cls?.label ?: d.address
        val label = if (tag != null) "$tag: $base" else if (isNew) "NEW: $base" else base
        ScanBus.alerts.tryEmit(AlertEvent(label, d.address, d.rssi, d.lastSeen))
        if (store.soundOn) AlertEffects.playSound(store.alertSoundType)
        if (store.vibrateOn) AlertEffects.vibrate(this)
        if (store.overlayOn && !ScanBus.appVisible && Settings.canDrawOverlays(this)) {
            OverlayManager.show(applicationContext)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (title != null) title else if (isNew) "New device" else if (d.matched) "Device found" else d.cls?.let { "${it.tier.name}: ${it.title.ifEmpty { "device" }}" } ?: "Device found")
            .setContentText("$label (${d.address})  ${d.rssi} dBm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(d.address.hashCode(), n)
    }

    // ---- notifications --------------------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Scan status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Device alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)          // the app plays its own sound effect
                enableVibration(false)
            }
        )
    }

    private fun statusText(): String = when {
        store.mode == ScanMode.CONTINUOUS -> "Continuous scan"
        ScanBus.phase.value == Phase.SCANNING -> "Burst scan: scanning now"
        else -> "Burst scan: waiting for next burst"
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Signal OPS IRL")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_STATUS, statusNotification(statusText()))
    }
}
