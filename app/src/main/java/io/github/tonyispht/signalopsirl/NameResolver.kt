package io.github.tonyispht.signalopsirl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Quietly connects to one device at a time and reads its GATT Device Name (0x2A00), so devices that never
 * put a name in their advertisements can still be listed by name. Used by the scan service's auto-identify.
 */
object NameResolver {
    private val SVC_GENERIC_ACCESS: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val CHR_DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    private const val TIMEOUT_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var gen = 0

    /** True from the moment a lookup starts until it reports back (or is cancelled). */
    @Volatile var busy = false
        private set

    /**
     * Starts a lookup. [onDone] gets the name (or null) and whether the device was reachable at all
     * (connected and its services were read), and is called exactly once unless [cancel] is called first.
     */
    @SuppressLint("MissingPermission")
    fun fetch(ctx: Context, device: BluetoothDevice, onDone: (name: String?, reached: Boolean) -> Unit) {
        cancel()
        val myGen = ++gen
        busy = true
        var reached = false
        var finished = false

        fun finish(name: String?) {
            if (finished || gen != myGen) return
            finished = true
            busy = false
            val g = gatt
            gatt = null
            if (g != null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            onDone(name, reached)
        }

        fun nameOf(v: ByteArray?): String? {
            if (v == null || v.isEmpty()) return null
            val s = String(v, Charsets.UTF_8).filter { !it.isISOControl() }.trim().take(40)
            return s.ifEmpty { null }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (gen != myGen) return
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.discoverServices()) finish(null)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(null)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (gen != myGen) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(null)
                    return
                }
                reached = true
                val c = g.getService(SVC_GENERIC_ACCESS)?.getCharacteristic(CHR_DEVICE_NAME)
                if (c == null || (c.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0 ||
                    !g.readCharacteristic(c)
                ) finish(null)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
            ) {
                if (gen != myGen) return
                finish(if (status == BluetoothGatt.GATT_SUCCESS) nameOf(value) else null)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (gen != myGen) return
                finish(if (status == BluetoothGatt.GATT_SUCCESS) nameOf(c.value) else null)
            }
        }

        try {
            gatt = device.connectGatt(ctx.applicationContext, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            finish(null)
            return
        }
        main.postDelayed({ finish(null) }, TIMEOUT_MS)
    }

    /** Abandons a lookup in progress without calling its callback. */
    @SuppressLint("MissingPermission")
    fun cancel() {
        gen++
        busy = false
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }
}
