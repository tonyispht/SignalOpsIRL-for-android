package com.example.signalopsirl

import java.util.concurrent.ConcurrentHashMap

enum class PairLevel {
    /** Nothing suggests the device pairs without a PIN or confirmation (or it can't be told). */
    NONE,
    /** The device looks like it has no display or keyboard, so Bluetooth pairing falls back to "Just Works". */
    LIKELY,
    /** Just Works is likely AND something worse showed up (a key in the advert, or readable/writable GATT data without pairing). */
    OPEN
}

class PairVerdict(val level: PairLevel, val reasons: List<String>)

/** What a GATT probe (CONNECT) learned about authentication, saved per address after the probe finishes. */
data class GattFacts(val readsOk: Int, val needPairing: Int, val writable: Int)

/**
 * Passive guess at whether a device would pair as "Just Works" (no PIN, no number to confirm).
 *
 * The pairing method is chosen at pairing time from both sides' input/output abilities, and it is not advertised, so this
 * is an estimate from what the device is: a device with no display and no keyboard (sensor, tracker, earbuds, bulb, lock...)
 * can only do Just Works, while phones, computers and keyboards use a confirmation or passkey. Evidence used:
 * connectable or not, the device kind from the identification rules, advertised appearance, the discoverable flag, advertised
 * out-of-band pairing data, and, once you have pressed CONNECT, whether data could be read without pairing.
 * Just Works gives no protection against a man-in-the-middle during pairing. Nothing is sent to the device to find this out.
 */
object PairingCheck {
    /** Results of GATT probes, keyed by address. */
    val gattFacts = ConcurrentHashMap<String, GattFacts>()

    /** Bumped when [gattFacts] changes so the UI refreshes. */
    val gattFactsGen = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Device kinds that have no display and no keyboard (so pairing can only be Just Works). */
    private val NO_IO = setOf(
        "trackers_valuables", "pet_trackers", "proximity_beacons", "smart_iot", "smart_appliances", "medical_health",
        "fitness_gym", "ev_micromobility", "hobby_rc_drones", "toys_robots_stem", "music_instruments_pro_audio",
        "cameras_photo_video_production", "makers_3d_printing_craft", "rural_farm_agriculture",
        "utilities_scada_smart_city", "industrial_logistics", "commercial_fleet_rv_trucking",
        "accessibility_hearing_assistive", "security_access_control", "outdoor_offgrid_power", "hunting_trail_woods",
        "motorcycle_powersports_gear", "aviation_marine_gliding", "wearables_audio", "computer_peripherals",
        "commercial_pos_retail"
    )

    /** Device kinds with a screen or keyboard: pairing shows a number to confirm or asks for a passkey. */
    private val HAS_IO = setOf(
        "smartphones", "laptops_tablets", "handhelds_gaming", "handheld_computers_umpc", "ereaders_notepads_reading",
        "home_entertainment_av", "crypto_security_keys", "smart_glasses_ai_wearables"
    )

    private val KEYBOARD = Regex("keyboard|kbd|keypad", RegexOption.IGNORE_CASE)
    private val SCREEN_WEARABLE = Regex("watch|wear os|galaxy fit|fenix|forerunner|venu|vivoactive", RegexOption.IGNORE_CASE)

    // Apple continuity types that only phones, tablets, watches and computers send (they have a display).
    private val APPLE_SCREEN_TYPES = setOf(0x0C, 0x0F, 0x10, 0x09, 0x0B)

    fun recordProbe(address: String, facts: GattFacts) {
        gattFacts[address] = facts
        gattFactsGen.value++
    }

    fun assess(d: SeenDevice, ad: RawAd?): PairVerdict {
        val fields = fields(ad?.packets)
        val reasons = ArrayList<String>()

        // A Temporary Key advertised in the clear is the worst case: anyone can pair and decrypt.
        if (fields.any { it.first == 0x10 }) {
            return PairVerdict(PairLevel.OPEN, listOf("ADVERTISES A SECURITY MANAGER TK VALUE (PAIRING KEY IN THE CLEAR)"))
        }
        if (!ScanBus.connectable.contains(d.address)) {
            return PairVerdict(PairLevel.NONE, listOf("NOT CONNECTABLE, SO IT CANNOT BE PAIRED"))
        }
        val oob = fields.firstOrNull { it.first == 0x11 }?.second
        if (oob != null && oob.isNotEmpty() && (oob[0].toInt() and 1) != 0) {
            return PairVerdict(PairLevel.NONE, listOf("OUT-OF-BAND PAIRING DATA ADVERTISED (NOT JUST WORKS)"))
        }

        val cat = d.cls?.category
        val name = d.name ?: ScanBus.resolvedNames[d.address]
        val appearance = fields.firstOrNull { it.first == 0x19 && it.second.size >= 2 }?.second
            ?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
        val flags = fields.firstOrNull { it.first == 0x01 }?.second?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val sensor = ad?.sensor != null

        // What kind of input/output does it have?
        var noIo: String? = null
        var hasIo: String? = null
        when {
            appearance != null && appearance == 0x03C1 -> hasIo = "ADVERTISES AS A KEYBOARD (PASSKEY ENTRY)"
            name != null && KEYBOARD.containsMatchIn(name) -> hasIo = "KEYBOARD (PASSKEY ENTRY)"
            appearance != null && (appearance shr 6) in setOf(1, 2, 3, 5) ->
                hasIo = "ADVERTISES AS A PHONE, COMPUTER OR WATCH (HAS A DISPLAY)"
            0x004C in d.companyIds && d.appleTypes.any { it in APPLE_SCREEN_TYPES } ->
                hasIo = "APPLE PHONE, TABLET OR COMPUTER (HAS A DISPLAY)"
            cat != null && cat in HAS_IO -> hasIo = "KIND OF DEVICE HAS A SCREEN OR KEYBOARD (CONFIRM/PASSKEY)"
            cat == "wearables_audio" && name != null && SCREEN_WEARABLE.containsMatchIn(name) ->
                hasIo = "SMARTWATCH (HAS A DISPLAY)"
            sensor -> noIo = "BROADCASTS SENSOR DATA, NO DISPLAY OR KEYBOARD"
            cat != null && cat in NO_IO -> noIo = "${d.cls?.title?.take(30) ?: "DEVICE"}: NO DISPLAY OR KEYBOARD"
        }

        if (hasIo != null) return PairVerdict(PairLevel.NONE, listOf(hasIo, "PAIRING NORMALLY SHOWS A NUMBER TO CONFIRM OR ASKS FOR A PASSKEY"))
        if (noIo == null) return PairVerdict(PairLevel.NONE, listOf("DEVICE TYPE UNKNOWN: CAN'T TELL HOW IT PAIRS"))

        reasons.add(noIo)
        reasons.add("WITH NO INPUT OR OUTPUT, BLUETOOTH PAIRING CAN ONLY USE JUST WORKS (NO PIN, NO CONFIRMATION)")
        reasons.add(
            if ((flags and 0x03) != 0) "DISCOVERABLE FLAG SET: IT IS ACCEPTING NEW PAIRINGS RIGHT NOW"
            else "NOT FLAGGED DISCOVERABLE: MAY ONLY ACCEPT PHONES IT IS ALREADY PAIRED WITH"
        )
        var level = PairLevel.LIKELY

        val f = gattFacts[d.address]
        if (f != null) {
            when {
                f.readsOk > 0 && f.needPairing == 0 && f.writable > 0 -> {
                    level = PairLevel.OPEN
                    reasons.add("GATT PROBE: IDENTITY DATA READ WITHOUT PAIRING, AND ${f.writable} WRITABLE CHARACTERISTICS (WRITES NOT TESTED)")
                }
                f.readsOk > 0 && f.needPairing == 0 ->
                    reasons.add("GATT PROBE: IDENTITY DATA READ WITHOUT PAIRING")
                f.needPairing > 0 ->
                    reasons.add("GATT PROBE: SOME DATA NEEDS PAIRING (LINK ENCRYPTION IS ENFORCED, BUT JUST WORKS IS UNAUTHENTICATED)")
            }
        } else {
            reasons.add("TAP CONNECT TO ALSO CHECK WHETHER DATA IS READABLE WITHOUT PAIRING")
        }
        return PairVerdict(level, reasons)
    }

    /** Splits the raw advertising packets into (AD type, data) pairs. */
    private fun fields(packets: List<ByteArray>?): List<Pair<Int, ByteArray>> {
        if (packets == null) return emptyList()
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (b in packets) {
            var i = 0
            while (i < b.size) {
                val len = b[i].toInt() and 0xFF
                if (len == 0 || i + 1 + len > b.size) break
                val type = b[i + 1].toInt() and 0xFF
                out.add(type to b.copyOfRange(i + 2, i + 1 + len))
                i += len + 1
            }
        }
        return out
    }
}
