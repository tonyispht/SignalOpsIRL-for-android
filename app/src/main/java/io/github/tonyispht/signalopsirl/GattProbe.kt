package io.github.tonyispht.signalopsirl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

enum class GattPhase { CONNECTING, DISCOVERING, READING, DONE, FAILED }

data class GattChar(val uuid: String, val props: String)

data class GattSvc(val uuid: String, val chars: List<GattChar>)

data class GattInfo(
    val address: String,
    val phase: GattPhase,
    val message: String? = null,
    val services: List<GattSvc> = emptyList(),
    /** Decoded values of the well-known readable characteristics, keyed by the characteristic's full UUID. */
    val values: Map<String, String> = emptyMap()
)

/**
 * One-shot "connect and look" probe: connects to a device over GATT, lists its services and reads a small
 * whitelist of harmless identity characteristics (name, manufacturer, model, firmware, battery...), then
 * disconnects. Only one probe runs at a time; the UI watches [info].
 */
object GattProbe {
    val info = MutableStateFlow<GattInfo?>(null)

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private const val CONNECT_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var gen = 0
    private val queue = ArrayDeque<BluetoothGattCharacteristic>()

    // ---- name tables ---------------------------------------------------------------------

    private val SERVICE_NAMES = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "1802" to "Immediate Alert",
        "1803" to "Link Loss", "1804" to "Tx Power", "1805" to "Current Time", "1808" to "Glucose",
        "1809" to "Health Thermometer", "180A" to "Device Information", "180D" to "Heart Rate",
        "180E" to "Phone Alert Status", "180F" to "Battery", "1810" to "Blood Pressure",
        "1812" to "Human Interface Device", "1816" to "Cycling Speed and Cadence",
        "1818" to "Cycling Power", "1819" to "Location and Navigation", "181A" to "Environmental Sensing",
        "181D" to "Weight Scale", "FD6F" to "Exposure Notification", "FEAA" to "Eddystone",
        "FEED" to "Tile", "FE95" to "Xiaomi", "FE2C" to "Google Fast Pair", "FE59" to "Nordic DFU"
    )

    private val CHAR_NAMES = mapOf(
        "2A00" to "Device Name", "2A01" to "Appearance", "2A04" to "Preferred Conn Params",
        "2A05" to "Service Changed", "2A06" to "Alert Level", "2A07" to "Tx Power Level",
        "2A19" to "Battery Level", "2A23" to "System ID", "2A24" to "Model Number",
        "2A25" to "Serial Number", "2A26" to "Firmware Revision", "2A27" to "Hardware Revision",
        "2A28" to "Software Revision", "2A29" to "Manufacturer", "2A2B" to "Current Time",
        "2A37" to "Heart Rate Measurement", "2A38" to "Body Sensor Location",
        "2A4A" to "HID Information", "2A4B" to "Report Map", "2A4C" to "HID Control Point",
        "2A4D" to "Report", "2A50" to "PnP ID", "2A6E" to "Temperature", "2A6F" to "Humidity"
    )

    /** The only characteristics that get read. They are identity/status values, not control points. */
    private val READ_WHITELIST = setOf(
        "2A00", "2A01", "2A07", "2A19", "2A23", "2A24", "2A25", "2A26", "2A27", "2A28", "2A29", "2A50"
    )

    /** "0x180F" for Bluetooth-SIG base UUIDs, otherwise the full UUID in upper case. */
    fun shortUuid(uuid: String): String {
        val u = uuid.lowercase()
        return if (u.startsWith("0000") && u.endsWith(BASE_SUFFIX)) "0x" + u.substring(4, 8).uppercase()
        else uuid.uppercase()
    }

    private fun key16(uuid: String): String? {
        val u = uuid.lowercase()
        return if (u.startsWith("0000") && u.endsWith(BASE_SUFFIX)) u.substring(4, 8).uppercase() else null
    }

    fun serviceName(uuid: String): String? = key16(uuid)?.let { SERVICE_NAMES[it] ?: BtDb.serviceName(it) }

    fun charName(uuid: String): String? = key16(uuid)?.let { CHAR_NAMES[it] }

    // ---- control -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, address: String) {
        closeGatt()
        val app = ctx.applicationContext
        val myGen = ++gen
        info.value = GattInfo(address, GattPhase.CONNECTING)

        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            fail(myGen, "Phone Bluetooth is turned off. Turn it on to connect.")
            return
        }
        // Prefer the device object from the scan (it carries the address type, needed for random addresses).
        val dev: BluetoothDevice? = ScanBus.bleDevices[address]
            ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (dev == null) {
            fail(myGen, "Invalid address")
            return
        }
        queue.clear()
        try {
            gatt = dev.connectGatt(app, false, callbackFor(myGen), BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            fail(myGen, "Bluetooth connect permission missing")
            return
        }
        main.postDelayed({
            if (gen == myGen && info.value?.phase == GattPhase.CONNECTING) {
                fail(myGen, "No response. The device may be out of range, busy, or not connectable.")
            }
        }, CONNECT_TIMEOUT_MS)
    }

    /** Ends any connection and clears the result (used when the dialog closes). */
    fun close() {
        gen++
        closeGatt()
        info.value = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun fail(myGen: Int, msg: String) {
        if (gen != myGen) return
        closeGatt()
        val cur = info.value ?: return
        info.value = cur.copy(phase = GattPhase.FAILED, message = msg)
    }

    private fun update(myGen: Int, f: (GattInfo) -> GattInfo) {
        if (gen != myGen) return
        info.value = info.value?.let(f)
    }

    // ---- callbacks -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun callbackFor(myGen: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gen != myGen) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                update(myGen) { it.copy(phase = GattPhase.DISCOVERING) }
                if (!g.discoverServices()) fail(myGen, "Could not start service discovery")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val phase = info.value?.phase
                // After DONE/FAILED the disconnect is ours and expected.
                if (phase != GattPhase.DONE && phase != GattPhase.FAILED) {
                    fail(myGen, if (status == 133) "Connection failed (GATT 133). Out of range, or the device refuses connections."
                    else "Disconnected by the device (status $status)")
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(myGen, "Connection error (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gen != myGen) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(myGen, "Service discovery failed (status $status)")
                return
            }
            val svcs = g.services.map { s ->
                GattSvc(s.uuid.toString(), s.characteristics.map { c ->
                    GattChar(c.uuid.toString(), propString(c.properties))
                })
            }
            queue.clear()
            g.services.forEach { s ->
                s.characteristics.forEach { c ->
                    val k = key16(c.uuid.toString())
                    if (k != null && k in READ_WHITELIST && (c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        queue.addLast(c)
                    }
                }
            }
            update(myGen) { it.copy(phase = GattPhase.READING, services = svcs) }
            readNext(myGen, g)
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) = got(myGen, g, c, value, status)

        // Older Android versions call this one instead.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            got(myGen, g, c, c.value ?: ByteArray(0), status)
    }

    @SuppressLint("MissingPermission")
    private fun readNext(myGen: Int, g: BluetoothGatt) {
        while (gen == myGen) {
            val c = queue.removeFirstOrNull()
            if (c == null) {
                finish(myGen)
                return
            }
            if (g.readCharacteristic(c)) return
            // This read could not even be started; skip it.
            update(myGen) { it.copy(values = it.values + (c.uuid.toString() to "(read failed)")) }
        }
    }

    private fun got(myGen: Int, g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (gen != myGen) return
        val text = when (status) {
            BluetoothGatt.GATT_SUCCESS -> format(key16(c.uuid.toString()), value)
            5, 15 -> "(needs pairing)"
            else -> "(error $status)"
        }
        update(myGen) { it.copy(values = it.values + (c.uuid.toString() to text)) }
        // A Device Name read here also names the device in the Signals list.
        if (status == BluetoothGatt.GATT_SUCCESS && key16(c.uuid.toString()) == "2A00") {
            val n = String(value, Charsets.UTF_8).trimEnd('\u0000').trim().take(40)
            val addr = info.value?.address
            if (addr != null && n.isNotEmpty() && n.none { it.isISOControl() }) {
                ScanBus.resolvedNames[addr] = n
                Game.onIdentified(addr)
            }
        }
        readNext(myGen, g)
    }

    private fun finish(myGen: Int) {
        if (gen != myGen) return
        update(myGen) { it.copy(phase = GattPhase.DONE) }
        info.value?.let { gi ->
            val vals = gi.values.values
            val writable = gi.services
                .filter { key16(it.uuid) !in setOf("1800", "1801") }
                .sumOf { s -> s.chars.count { c -> c.props.contains('W') || c.props.contains('w') } }
            PairingCheck.recordProbe(
                gi.address,
                GattFacts(
                    readsOk = vals.count { !it.startsWith("(") },
                    needPairing = vals.count { it == "(needs pairing)" },
                    writable = writable
                )
            )
        }
        // Everything wanted has been read, so release the connection.
        closeGatt()
    }

    // ---- formatting ----------------------------------------------------------------------

    private fun propString(p: Int): String = buildString {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('R')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append('W')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append('w')
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('N')
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append('I')
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) append('S')
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun format(k: String?, v: ByteArray): String {
        if (v.isEmpty()) return "(empty)"
        return when (k) {
            "2A19" -> "${v[0].toInt() and 0xFF}%"
            "2A07" -> "${v[0].toInt()} dBm"
            "2A01" -> if (v.size >= 2) "0x%04X".format((v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)) else hex(v)
            "2A23", "2A50" -> hex(v)
            else -> {
                val s = String(v, Charsets.UTF_8).trimEnd('\u0000').trim()
                if (s.isNotEmpty() && s.all { !it.isISOControl() }) s else hex(v)
            }
        }
    }
}
