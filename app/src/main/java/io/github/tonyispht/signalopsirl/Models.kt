package io.github.tonyispht.signalopsirl

import kotlin.math.min

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class ScanMode { CONTINUOUS, BURST }

enum class Phase { IDLE, SCANNING, WAITING }

data class SeenDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val matched: Boolean,
    /** What the scanner_config.json rules identified this device as (null = unknown). */
    val cls: Classification? = null,
    // Advertising evidence collected across packets, used to (re)classify the device.
    val companyIds: Set<Int> = emptySet(),
    val services: Set<String> = emptySet(),
    val appleTypes: Set<Int> = emptySet(),
    /** Fingerprint of the inputs the current [cls] was computed from. */
    val clsKey: Int = 0,
    /** Id of the physical device this address was linked to (0 = not a rotating-address device). */
    val identity: Int = 0,
    /** How many addresses that device has used so far (1 = no rotation seen). */
    val linked: Int = 1,
    /** When this address was first heard (ms since epoch); 0 = unknown. Drives the NEW flash and filter. */
    val firstSeen: Long = 0L,
    /** Bit 0 = heard as BLE, bit 1 = heard by classic Bluetooth discovery (3 = both). */
    val transport: Int = 1,
    /** Classic class of device (major + minor class), -1 = none reported. */
    val cod: Int = -1,
    /** Apple only: model read from the adverts (AirPods Pro, Apple Watch...), null = not told. */
    val appleModel: String? = null,
    /** Apple only: what the device says it is doing (idle, screen on...), null = not told. */
    val appleState: String? = null,
    /** Payload clues collected across packets (APPEAR:03C1, SWIFTPAIR, MSCDP:09, FMDN...), see [AdDecoder.hints]. */
    val hints: Set<String> = emptySet()
)

/** Live hot/cold tracking state for one device. trend: 1 = getting closer, -1 = moving away. */
data class TrackState(
    val address: String,
    val name: String?,
    val rssi: Int,
    val trend: Int,
    val lastSeen: Long,
    val seen: Boolean
)

/** One received advertisement from either radio (the phone's scanner or a USB adapter). */
class Advert(
    val address: String,
    val rssi: Int,
    val name: String?,
    /** Manufacturer data by company ID (the two ID bytes are not part of the value). */
    val mfg: Map<Int, ByteArray>,
    /** Service UUIDs as 32 uppercase hex digits without dashes. */
    val services: List<String>,
    /** The raw advertising data (AD structures) this report came from, if the radio provides it. */
    val raw: ByteArray? = null,
    /** True when [raw] is only one packet of several (USB reports adverts and scan responses separately). */
    val rawPart: Boolean = false,
    /** True for a BLE 5 extended advertisement (false = legacy 31-byte advertisement). */
    val ext: Boolean = false,
    /** Secondary PHY of an extended advert: 1 = 1M, 2 = 2M, 3 = Coded (long range), 0 = unknown / legacy. */
    val phy: Int = 0,
    /** Advertising set id of an extended advert (-1 = none). */
    val sid: Int = -1,
    /** Tx power the sender reported in the packet header, in dBm (127 = not reported). */
    val txPower: Int = 127,
    /** True when the radio could not deliver all of an extended advert's data. */
    val truncated: Boolean = false
)

data class AlertEvent(val label: String, val address: String, val rssi: Int, val time: Long)

/** Shared state between the foreground service and the UI. */
object ScanBus {
    val phase = MutableStateFlow(Phase.IDLE)
    val devices = MutableStateFlow<Map<String, SeenDevice>>(emptyMap())

    /** Bumped by [clearDevices] so the service drops its own copy of the device map too. */
    @Volatile var clearGen = 0

    /** Devices the service dropped from its list to save memory (see ScanService.publishDevices); shown in the hidden counter. */
    val pruned = java.util.concurrent.atomic.AtomicInteger(0)

    /** Addresses heard through classic Bluetooth discovery since the last clear. */
    val classicAddrs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True while classic discovery is running. */
    @Volatile var classicActive = false

    // ---- histories for the device detail screen and the sensor graphs (memory only) ----------

    /** Recent signal strength per address: (time ms, rssi), at most one sample a second, last 3 minutes. */
    val rssiHist = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Int>>>()

    /** Alerts silenced from the notification's MUTE button (this scan session only; sound and vibration). */
    val alertsMuted = MutableStateFlow(false)

    fun noteRssi(addr: String, now: Long, rssi: Int) {
        val q = rssiHist.getOrPut(addr) { ArrayDeque() }
        synchronized(q) {
            val last = q.lastOrNull()
            if (last != null && now - last.first < 1_000L) return
            q.addLast(now to rssi)
            while (q.isNotEmpty() && now - q.first().first > 180_000L) q.removeFirst()
        }
        if (rssiHist.size > 600) rssiHist.keys.take(100).forEach { rssiHist.remove(it) }
    }

    fun rssiSeries(addr: String): List<Pair<Long, Int>> = rssiHist[addr]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /** Sensor readings per address: (time ms, temperature °F or NaN, humidity % or NaN), every 30 s, last 2 hours. */
    val sensorHist = ConcurrentHashMap<String, ArrayDeque<Triple<Long, Double, Double>>>()

    private val NUM = Regex("-?\\d+(\\.\\d+)?")

    fun noteSensor(addr: String, now: Long, s: SensorReading) {
        val t = s.values.firstOrNull { it.first == "TEMP" }?.second?.let { NUM.find(it)?.value?.toDoubleOrNull() } ?: Double.NaN
        val h = s.values.firstOrNull { it.first == "HUM" }?.second?.let { NUM.find(it)?.value?.toDoubleOrNull() } ?: Double.NaN
        if (t.isNaN() && h.isNaN()) return
        val q = sensorHist.getOrPut(addr) { ArrayDeque() }
        synchronized(q) {
            val last = q.lastOrNull()
            if (last != null && now - last.first < 30_000L) return
            q.addLast(Triple(now, t, h))
            while (q.isNotEmpty() && now - q.first().first > 7_200_000L) q.removeFirst()
        }
    }

    fun sensorSeries(addr: String): List<Triple<Long, Double, Double>> =
        sensorHist[addr]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    fun clearDevices() {
        pruned.set(0)
        rssiHist.clear()
        classicAddrs.clear()
        clearGen++
        bleDevices.clear()
        rawAds.clear()
        identityAddrs.clear()
        linkLog.value = emptyList()
        devices.value = emptyMap()
    }

    /** The latest address-linking decisions (newest first), shown as the LINK LOG in settings. */
    val linkLog = MutableStateFlow<List<String>>(emptyList())

    fun noteLink(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        linkLog.value = (listOf("$t $msg") + linkLog.value).take(40)
    }

    /** Device objects from the phone's own scanner, so a GATT connection knows each address type. */
    val bleDevices = ConcurrentHashMap<String, android.bluetooth.BluetoothDevice>()

    /** Addresses whose last advertisement said the device accepts connections. */
    val connectable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Names read from unnamed devices over GATT by auto-identify (kept across list clears). */
    val resolvedNames = ConcurrentHashMap<String, String>()

    /** Latest raw advertising data (and any decoded sensor reading) per device address. */
    val rawAds = ConcurrentHashMap<String, RawAd>()

    /** For a linked (rotating-address) device: its addresses, newest first. Keyed by [SeenDevice.identity]. */
    val identityAddrs = ConcurrentHashMap<Int, List<String>>()

    /** Names the user gave to devices, keyed by upper-case address. Saved in preferences. */
    val nicknames = ConcurrentHashMap<String, String>()
    /** Bumped whenever [nicknames] changes so the UI can refresh. */
    val nicknameGen = MutableStateFlow(0)
    private var nicknamesLoaded = false

    fun ensureNicknames(store: Store) {
        if (nicknamesLoaded) return
        nicknamesLoaded = true
        nicknames.putAll(store.loadNicknames())
        nicknameGen.value++
    }

    /** Sets (or, with a blank [nick], removes) the user's name for a device and saves it. */
    fun setNickname(store: Store, addr: String, nick: String?) {
        val key = addr.uppercase()
        val clean = nick?.trim()?.take(40)
        if (clean.isNullOrEmpty()) nicknames.remove(key) else nicknames[key] = clean
        if (!clean.isNullOrEmpty()) Game.onNamed(key)
        store.saveNicknames(nicknames)
        nicknameGen.value++
    }

    /** A device changed its random address: its name moves along to the new one. */
    fun moveNickname(store: Store, from: String, to: String) {
        val n = nicknames.remove(from.uppercase()) ?: return
        nicknames[to.uppercase()] = n
        store.saveNicknames(nicknames)
        nicknameGen.value++
    }

    val error = MutableStateFlow<String?>(null)

    /** Progress text shown under the status line (e.g. while a USB adapter loads its firmware). */
    val note = MutableStateFlow<String?>(null)
    val alerts = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)

    /** Address being hot/cold tracked (null = off), its live state, and whether the ticks are muted. */
    val trackTarget = MutableStateFlow<String?>(null)
    val track = MutableStateFlow<TrackState?>(null)
    val trackMuted = MutableStateFlow(false)

    /** True while MainActivity is on screen (the in-app border is used instead of the overlay). */
    @Volatile var appVisible = false

    // ---- live packet feed: drives the data rain and the oscilloscope strip ----
    private val packetCounter = java.util.concurrent.atomic.AtomicLong(0)
    /** Total advertisements received since the app started. */
    val packetCount: Long get() = packetCounter.get()
    /** Signal strength of the most recent advertisement. */
    @Volatile var lastRssi: Int = -100
    private val packetFeed = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** Called for every received advertisement; keeps a few recent packets' bytes for the rain while the screen is showing. */
    fun notePacket(rssi: Int, raw: ByteArray?) {
        packetCounter.incrementAndGet()
        lastRssi = rssi
        if (raw != null && appVisible) {
            val data = AdDecoder.trim(raw)
            if (data.isNotEmpty()) {
                packetFeed.add(data.copyOf(min(data.size, 16)))
                while (packetFeed.size > 24) packetFeed.poll()
            }
        }
    }

    private val extCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val codedCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Extended (BLE 5) advertisements received since the app started; shows whether the radio delivers them at all. */
    val extCount: Long get() = extCounter.get()

    /** Extended advertisements that arrived on the Coded (long range) PHY. */
    val codedCount: Long get() = codedCounter.get()

    /** One-line description of how the current scan is configured (empty when not scanning). */
    val scanInfo = MutableStateFlow("")

    /** Current ground speed in mph (0 when standing still; null when there is no recent GPS speed or the setting is off). */
    val speedMph = MutableStateFlow<Int?>(null)

    fun noteExtended(a: Advert) {
        if (!a.ext) return
        extCounter.incrementAndGet()
        if (a.phy == 3) codedCounter.incrementAndGet()
    }

    fun pollPacket(): ByteArray? = packetFeed.poll()
    val overlayGranted = MutableStateFlow(false)
}

object Matcher {
    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]?){5}[0-9A-Fa-f]{2}$")

    // 3 to 5 octets with separators, e.g. 00:25:DF (a manufacturer prefix)
    private val MAC_PREFIX = Regex("^([0-9A-Fa-f]{2}[:-]){2,4}[0-9A-Fa-f]{2}$")

    fun isMac(s: String) = MAC.matches(s.trim())

    fun isMacPrefix(s: String) = MAC_PREFIX.matches(s.trim())

    /** MAC addresses and prefixes are stored as AA:BB:CC...; everything else (names, CID:..., UUID:... rules) is kept as typed. */
    fun normalize(entry: String): String {
        val e = entry.trim()
        return if (isMac(e) || isMacPrefix(e)) normalizeMac(e) else e
    }

    fun normalizeMac(s: String): String =
        s.filter { it.isLetterOrDigit() }.uppercase().chunked(2).joinToString(":")

    /**
     * Name / address check only (no advertising data). Plain entries behave as before: a MAC must match exactly,
     * a prefix matches the start of the address, anything else is a case-insensitive name substring.
     * Rules that need advertising data (CID:, UUID:, MFG: ...) are false here; use [eval] for those.
     */
    fun matches(entry: String, name: String?, address: String): Boolean {
        val r = Targets.compile(entry).rule ?: return false
        return r.eval(MatchInput(name, address, 0)) != 0
    }

    /** Null when [entry] is a valid target, otherwise a short message saying what is wrong with it. */
    fun check(entry: String): String? = Targets.compile(entry.trim()).error

    /** Short label for the target list: MAC, PFX, NAME, CID, UUID, MFG, ADV, APPLE, RULE ... */
    fun kind(entry: String): String = Targets.compile(entry.trim()).rule?.kind ?: "?"

    /** True for an ordinary name, MAC or MAC prefix entry (what the +N / +MAC buttons on a signal row manage). */
    fun isPlain(entry: String): Boolean = Targets.compile(entry.trim()).rule?.plain == true

    /**
     * Checks every target against one device. 0 = no target matches, 1 = a target matches,
     * 2 = a target matches but only because of the bytes of the packet just received (the caller keeps such a
     * match alive for a few seconds, since the next packet of the same device may not repeat those bytes).
     */
    fun eval(watch: List<String>, m: MatchInput): Int {
        var best = 0
        for (e in watch) {
            val r = Targets.compile(e.trim()).rule ?: continue
            val v = r.eval(m)
            if (v == 1) return 1
            if (v == 2) best = 2
        }
        return best
    }
}

class Store(context: Context) {
    val prefs: SharedPreferences =
        context.getSharedPreferences("signalops", Context.MODE_PRIVATE)

    var mode: ScanMode
        get() = runCatching { ScanMode.valueOf(prefs.getString("mode", "")!!) }
            .getOrDefault(ScanMode.CONTINUOUS)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var burstScanSec: Int
        get() = prefs.getInt("burstScan", 5)
        set(v) = prefs.edit().putInt("burstScan", v).apply()

    var burstPauseSec: Int
        get() = prefs.getInt("burstPause", 30)
        set(v) = prefs.edit().putInt("burstPause", v).apply()

    var soundOn: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    /** Which target alert sound (index into [AlertEffects.ALERT_SOUND_NAMES]). */
    var alertSoundType: Int
        get() = prefs.getInt("alertSoundType", 0).coerceIn(0, AlertEffects.ALERT_SOUND_NAMES.lastIndex)
        set(v) = prefs.edit().putInt("alertSoundType", v).apply()

    var vibrateOn: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()

    var flashOn: Boolean
        get() = prefs.getBoolean("flash", true)
        set(v) = prefs.edit().putBoolean("flash", v).apply()

    /** Hide the status and navigation bars (swipe from the edge to peek at them). */
    var fullscreenOn: Boolean
        get() = prefs.getBoolean("fullscreen", false)
        set(v) = prefs.edit().putBoolean("fullscreen", v).apply()

    /** Keep scanning with the screen off, using filters built from the target list (Android pauses unfiltered scans). */
    var screenOffScanOn: Boolean
        get() = prefs.getBoolean("screenOffScan", true)
        set(v) = prefs.edit().putBoolean("screenOffScan", v).apply()

    /** Keep the display on while a scan is running. */
    var keepAwakeOn: Boolean
        get() = prefs.getBoolean("keepAwake", false)
        set(v) = prefs.edit().putBoolean("keepAwake", v).apply()

    /** Version of the terms of responsible use the user accepted (0 = never). See [TERMS_VERSION]. */
    var termsAcceptedVersion: Int
        get() = prefs.getInt("termsVersion", 0)
        set(v) = prefs.edit().putInt("termsVersion", v).apply()

    /** Devices hidden from the Signals list, one "key<TAB>label" string each. See [Hidden]. */
    var hiddenRaw: Set<String>
        get() = prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden", v).apply()

    /** Pinned devices (addresses), shown at the top of the Signals list. */
    var pinned: Set<String>
        get() = prefs.getStringSet("pinned", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("pinned", v).apply()

    /** Text size: 0 = normal, 1 = large, 2 = extra large. */
    var textSize: Int
        get() = prefs.getInt("textSize", 0)
        set(v) = prefs.edit().putInt("textSize", v).apply()

    /** The first-run tour has been shown. */
    var tourDone: Boolean
        get() = prefs.getBoolean("tourDone", false)
        set(v) = prefs.edit().putBoolean("tourDone", v).apply()

    /** Show the session report after a scan stops. */
    var sessionReportOn: Boolean
        get() = prefs.getBoolean("sessionReport", true)
        set(v) = prefs.edit().putBoolean("sessionReport", v).apply()

    /** Check GitHub for a newer release when the app opens (at most every few hours). On by default. */
    var autoUpdateCheckOn: Boolean
        get() = prefs.getBoolean("autoUpdateCheck", true)
        set(v) = prefs.edit().putBoolean("autoUpdateCheck", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()

    /** Connect to unnamed devices in the background to read their GATT Device Name. Off by default. */
    var autoIdentifyOn: Boolean
        get() = prefs.getBoolean("autoIdentify", false)
        set(v) = prefs.edit().putBoolean("autoIdentify", v).apply()

    /** Movie-style visual effects (scanlines, glitch text, boot sequence, radar glow). */
    var fxOn: Boolean
        get() = prefs.getBoolean("fx", true)
        set(v) = prefs.edit().putBoolean("fx", v).apply()

    /** Falling hex of real packet bytes over the main screen (needs [fxOn]). */
    var rainOn: Boolean
        get() = prefs.getBoolean("fxRain", true)
        set(v) = prefs.edit().putBoolean("fxRain", v).apply()

    /** Oscilloscope strip under the title (needs [fxOn]). */
    var scopeOn: Boolean
        get() = prefs.getBoolean("fxScope", true)
        set(v) = prefs.edit().putBoolean("fxScope", v).apply()

    /** CRT power-on / power-off effect when a scan starts or stops (needs [fxOn]). */
    var crtPowerOn: Boolean
        get() = prefs.getBoolean("fxCrtPower", true)
        set(v) = prefs.edit().putBoolean("fxCrtPower", v).apply()

    /** Curved-glass look (vignette, rounded bezel, glare) over the whole screen. */
    var curvatureOn: Boolean
        get() = prefs.getBoolean("fxCurve", true)
        set(v) = prefs.edit().putBoolean("fxCurve", v).apply()

    /** Level-up / achievement / title effects and sounds. */
    var gameFxOn: Boolean
        get() = prefs.getBoolean("gameFx", true)
        set(v) = prefs.edit().putBoolean("gameFx", v).apply()

    /** Also receive BLE 5 extended advertisements (and Coded PHY long range where the radio can). */
    var extScanOn: Boolean
        get() = prefs.getBoolean("extScan", true)
        set(v) = prefs.edit().putBoolean("extScan", v).apply()

    /** Link the changing random addresses of one device together and show it as one row. */
    var mergeRotatingOn: Boolean
        get() = prefs.getBoolean("mergeRotating", true)
        set(v) = prefs.edit().putBoolean("mergeRotating", v).apply()

    /** Collapse unnamed devices of the same kind (Apple, Find My...) into one "x N" row. Off = one row per device. */
    var groupAnonOn: Boolean
        get() = prefs.getBoolean("groupAnon", false)
        set(v) = prefs.edit().putBoolean("groupAnon", v).apply()

    /** Show only the strongest device when several share exactly the same name. */
    var onePerNameOn: Boolean
        get() = prefs.getBoolean("onePerName", true)
        set(v) = prefs.edit().putBoolean("onePerName", v).apply()

    /** Also run classic (BR/EDR) Bluetooth discovery next to the BLE scan, to list devices that never advertise over BLE. */
    var classicOn: Boolean
        get() = prefs.getBoolean("classic", true)
        set(v) = prefs.edit().putBoolean("classic", v).apply()

    /** Raw list: no address merging, no grouping, no one-row-per-name, no new-only filter, no pruning, a much longer list. */
    var rawListOn: Boolean
        get() = prefs.getBoolean("rawList", false)
        set(v) = prefs.edit().putBoolean("rawList", v).apply()

    /** Start scanning by itself when the app opens. */
    var autoStartOn: Boolean
        get() = prefs.getBoolean("autoStart", true)
        set(v) = prefs.edit().putBoolean("autoStart", v).apply()

    /** Play a short sound the first time each new device is heard. */
    var newSoundOn: Boolean
        get() = prefs.getBoolean("newSound", true)
        set(v) = prefs.edit().putBoolean("newSound", v).apply()

    /** Which new-device sound (index into [AlertEffects.NEW_SOUND_NAMES]). */
    var newSoundType: Int
        get() = prefs.getInt("newSoundType", 0).coerceIn(0, AlertEffects.NEW_SOUND_NAMES.lastIndex)
        set(v) = prefs.edit().putInt("newSoundType", v).apply()

    /** Fire the full alert for every device first heard after this was switched on. */
    var alertNewOn: Boolean
        get() = prefs.getBoolean("alertNew", false)
        set(v) = prefs.edit().putBoolean("alertNew", v).apply()

    /** Ignore unnamed random-address devices (Find My etc.) for the new-device sound and alert, since they never stop appearing. */
    var newSkipAnonOn: Boolean
        get() = prefs.getBoolean("newSkipAnon", true)
        set(v) = prefs.edit().putBoolean("newSkipAnon", v).apply()

    /** Show a "likely Just Works pairing" marker on devices (see PairingCheck). */
    var pairCheckOn: Boolean
        get() = prefs.getBoolean("pairCheck", true)
        set(v) = prefs.edit().putBoolean("pairCheck", v).apply()

    /** Warn when a tracker keeps showing up around you (see TrackerWatch). */
    var trackerOn: Boolean
        get() = prefs.getBoolean("trackerFollow", true)
        set(v) = prefs.edit().putBoolean("trackerFollow", v).apply()

    /** Show the current speed (mph) in the status card while moving. Uses GPS, only while the screen is on. */
    var speedOn: Boolean
        get() = prefs.getBoolean("showSpeed", true)
        set(v) = prefs.edit().putBoolean("showSpeed", v).apply()

    /** Which of the eight colors the full-screen speed display uses (0-7, see SPEED_COLORS in SpeedScreen.kt). */
    var speedColor: Int
        get() = prefs.getInt("speedColor", 0).coerceIn(0, 7)
        set(v) = prefs.edit().putInt("speedColor", v).apply()

    var overlayOn: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(v) = prefs.edit().putBoolean("overlay", v).apply()

    /** Fire the normal alert when a device is identified as ALERT-tier by the built-in config rules. */
    var autoAlertOn: Boolean
        get() = false // retired in 1.4.1 (no setting)
        set(v) = prefs.edit().putBoolean("autoAlert", v).apply()

    /** Same for POSSIBLE-tier identifications (weaker evidence). */
    var autoPossibleOn: Boolean
        get() = false // retired in 1.4.1 (no setting)
        set(v) = prefs.edit().putBoolean("autoPossible", v).apply()

    /** True = scan through an external USB adapter instead of the phone's Bluetooth. */
    var useUsb: Boolean
        get() = prefs.getBoolean("useUsb", false)
        set(v) = prefs.edit().putBoolean("useUsb", v).apply()

    /** Selected USB adapter as "vid:pid" in hex, e.g. 2357:0604. */
    var usbId: String
        get() = prefs.getString("usbId", "") ?: ""
        set(v) = prefs.edit().putString("usbId", v).apply()

    fun loadWatchlist(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_WATCH, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun loadNicknames(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("nicknames", "{}") ?: "{}")
        val m = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k.uppercase()] = o.getString(k)
        }
        m as Map<String, String>
    }.getOrDefault(emptyMap())

    fun saveNicknames(m: Map<String, String>) {
        prefs.edit().putString("nicknames", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun saveWatchlist(list: List<String>) {
        prefs.edit().putString(KEY_WATCH, JSONArray(list).toString()).apply()
    }

    companion object {
        const val KEY_WATCH = "watch"
        const val KEY_TRACKER = "trackerFollow"
        const val KEY_SPEED = "showSpeed"
    }
}
