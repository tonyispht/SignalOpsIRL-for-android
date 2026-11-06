package com.example.signalopsirl

/** Decoding of the Bluetooth "class of device" that classic discovery reports (major + minor class, 0x1FFC mask). */
object ClassicBt {
    private val MAJOR = mapOf(
        0 to "MISC", 1 to "COMPUTER", 2 to "PHONE", 3 to "NETWORK", 4 to "AUDIO/VIDEO", 5 to "PERIPHERAL",
        6 to "IMAGING", 7 to "WEARABLE", 8 to "TOY", 9 to "HEALTH", 31 to "UNCATEGORIZED"
    )

    private val MAJOR_ALIAS = mapOf(
        "MISC" to 0, "COMPUTER" to 1, "PC" to 1, "PHONE" to 2, "NETWORK" to 3, "AUDIO" to 4, "AV" to 4,
        "PERIPHERAL" to 5, "IMAGING" to 6, "WEARABLE" to 7, "TOY" to 8, "HEALTH" to 9, "UNCATEGORIZED" to 31
    )

    private val AUDIO = mapOf(
        1 to "HEADSET", 2 to "HANDS-FREE", 4 to "MICROPHONE", 5 to "LOUDSPEAKER", 6 to "HEADPHONES",
        7 to "PORTABLE AUDIO", 8 to "CAR AUDIO", 9 to "SET-TOP BOX", 10 to "HIFI AUDIO", 11 to "VCR",
        12 to "VIDEO CAMERA", 13 to "CAMCORDER", 14 to "VIDEO MONITOR", 15 to "DISPLAY + SPEAKER",
        16 to "VIDEO CONFERENCING", 18 to "GAMING / TOY"
    )
    private val PHONE = mapOf(1 to "CELLULAR", 2 to "CORDLESS", 3 to "SMARTPHONE", 4 to "WIRED MODEM", 5 to "ISDN")
    private val COMPUTER = mapOf(
        1 to "DESKTOP", 2 to "SERVER", 3 to "LAPTOP", 4 to "HANDHELD PC", 5 to "PALM", 6 to "WEARABLE COMPUTER", 7 to "TABLET"
    )
    private val WEARABLE = mapOf(1 to "WRISTWATCH", 2 to "PAGER", 3 to "JACKET", 4 to "HELMET", 5 to "GLASSES")
    private val HEALTH = mapOf(
        1 to "BLOOD PRESSURE", 2 to "THERMOMETER", 3 to "SCALE", 4 to "GLUCOSE", 5 to "PULSE OXIMETER",
        6 to "HEART RATE", 7 to "HEALTH DISPLAY", 8 to "STEP COUNTER"
    )
    private val TOY = mapOf(1 to "ROBOT", 2 to "VEHICLE", 3 to "DOLL", 4 to "CONTROLLER", 5 to "GAME")
    private val PERIPH_LOW = mapOf(1 to "JOYSTICK", 2 to "GAMEPAD", 3 to "REMOTE", 4 to "SENSOR", 5 to "DIGITIZER", 6 to "CARD READER")

    fun major(cod: Int) = (cod shr 8) and 0x1F
    fun minor(cod: Int) = (cod shr 2) and 0x3F

    fun minorName(cod: Int): String? {
        val mn = minor(cod)
        return when (major(cod)) {
            4 -> AUDIO[mn]
            2 -> PHONE[mn]
            1 -> COMPUTER[mn]
            7 -> WEARABLE[mn]
            9 -> HEALTH[mn]
            8 -> TOY[mn]
            5 -> {
                val kind = when (mn shr 4) { 1 -> "KEYBOARD"; 2 -> "POINTING DEVICE"; 3 -> "KEYBOARD + POINTER"; else -> null }
                val low = PERIPH_LOW[mn and 0x0F]
                listOfNotNull(kind, low).joinToString(" / ").ifEmpty { null }
            }
            else -> null
        }
    }

    /** "AUDIO/VIDEO \u00B7 HEADPHONES", or null when no class was reported. */
    fun text(cod: Int): String? {
        if (cod < 0) return null
        val mj = MAJOR[major(cod)] ?: "CLASS 0x%02X".format(major(cod))
        return minorName(cod)?.let { "$mj \u00B7 $it" } ?: mj
    }

    private val MINOR_ALIAS: Map<String, Pair<Int, (Int) -> Boolean>> = mapOf(
        "HEADSET" to (4 to { m: Int -> m == 1 }), "HANDSFREE" to (4 to { m: Int -> m == 2 }),
        "HEADPHONES" to (4 to { m: Int -> m == 6 }), "SPEAKER" to (4 to { m: Int -> m == 5 || m == 7 || m == 10 }),
        "CARAUDIO" to (4 to { m: Int -> m == 8 }), "MICROPHONE" to (4 to { m: Int -> m == 4 }),
        "TV" to (4 to { m: Int -> m == 14 || m == 15 }),
        "SMARTPHONE" to (2 to { m: Int -> m == 3 }),
        "LAPTOP" to (1 to { m: Int -> m == 3 }), "TABLET" to (1 to { m: Int -> m == 7 }), "DESKTOP" to (1 to { m: Int -> m == 1 }),
        "KEYBOARD" to (5 to { m: Int -> (m shr 4) and 1 == 1 }), "MOUSE" to (5 to { m: Int -> (m shr 4) and 2 == 2 }),
        "GAMEPAD" to (5 to { m: Int -> (m and 0x0F) == 2 || (m and 0x0F) == 1 }),
        "WATCH" to (7 to { m: Int -> m == 1 }), "GLASSES" to (7 to { m: Int -> m == 5 })
    )

    /** For `COD:` targets: a major class (PHONE, AUDIO...), a minor class (HEADPHONES, KEYBOARD...) or hex digits (`0418`, `04??`). */
    fun spec(v: String): ((Int) -> Boolean)? {
        val k = v.trim().uppercase().replace(" ", "").replace("-", "").replace("_", "")
        MAJOR_ALIAS[k]?.let { mj -> return { cod -> cod >= 0 && major(cod) == mj } }
        MINOR_ALIAS[k]?.let { (mj, f) -> return { cod -> cod >= 0 && major(cod) == mj && f(minor(cod)) } }
        val h = k.removePrefix("0X").replace('X', '?')
        if (h.isEmpty() || h.length > 6 || h.any { !(it in '0'..'9' || it in 'A'..'F' || it == '?') }) return null
        val p = h.padStart(4, '0')
        return { cod -> cod >= 0 && Targets.nibbleMatch(p, (cod and 0xFFFF).toString(16).uppercase().padStart(4, '0'), 0) }
    }
}
