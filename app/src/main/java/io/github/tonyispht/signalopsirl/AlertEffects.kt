package io.github.tonyispht.signalopsirl

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

object AlertEffects {
    private const val RATE = 44100
    private val handler = Handler(Looper.getMainLooper())

    // Three rising beeps, synthesized so no audio asset is needed.
    private val pcm: ShortArray by lazy {
        val tones = listOf(880.0, 1175.0, 1568.0)
        val toneN = RATE * 140 / 1000
        val gapN = RATE * 50 / 1000
        val buf = ShortArray(tones.size * (toneN + gapN))
        var idx = 0
        for (f in tones) {
            for (i in 0 until toneN) {
                val env = minOf(1.0, i / (RATE * 0.01), (toneN - i) / (RATE * 0.03))
                val s = sin(2 * PI * f * i / RATE) * env * 0.8 * Short.MAX_VALUE
                buf[idx++] = s.toInt().toShort()
            }
            idx += gapN
        }
        buf
    }

    // Short tracking tick; pitch is quantized so each pitch is synthesized only once.
    private val tickCache = HashMap<Int, ShortArray>()

    private fun tick(freq: Int): ShortArray = tickCache.getOrPut(freq) {
        val n = RATE * 55 / 1000
        ShortArray(n) { i ->
            val env = minOf(1.0, i / (RATE * 0.004), (n - i) / (RATE * 0.02))
            (sin(2 * PI * freq * i / RATE) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun playTick(freqHz: Float) {
        try {
            val pcm = tick((Math.round(freqHz / 25f) * 25).coerceIn(300, 2400))
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            handler.postDelayed({ runCatching { track.release() } }, pcm.size * 1000L / RATE + 150)
        } catch (_: Exception) {
        }
    }

    // ---- target alert sounds ----------------------------------------------------------------

    /** The sounds you can pick for a full alert (index 0 is the original three rising beeps). */
    val ALERT_SOUND_NAMES = listOf("BEEPS", "POLICE CHIRP", "SIREN WAIL", "KLAXON", "RAPID PULSE")

    private val alertPcm = HashMap<Int, ShortArray>()

    private fun alertSoundPcm(type: Int): ShortArray = alertPcm.getOrPut(type) {
        when (type) {
            1 -> policeChirp()
            2 -> sirenWail()
            3 -> klaxon()
            4 -> rapidPulse()
            else -> pcm
        }
    }

    /** A siren-like swoop between [lo] and [hi] Hz: up for [upMs], then back down for [downMs]. */
    private fun yelp(out: DoubleArray, startMs: Int, upMs: Int, downMs: Int, lo: Double, hi: Double) {
        val s = RATE * startMs / 1000
        val up = RATE * upMs / 1000
        val n = up + RATE * downMs / 1000
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s + i
            if (idx >= out.size) break
            val f = if (i < up) lo + (hi - lo) * i / up else hi - (hi - lo) * (i - up) / (n - up)
            phase += 2 * PI * f / RATE
            val env = minOf(1.0, i / (RATE * 0.004), (n - i) / (RATE * 0.012))
            out[idx] += (sin(phase) + 0.45 * sin(2 * phase) + 0.25 * sin(3 * phase)) * env
        }
    }

    /** A buzzy, square-ish beep at [f] Hz. */
    private fun buzz(out: DoubleArray, startMs: Int, lenMs: Int, f: Double) {
        val s = RATE * startMs / 1000
        val n = RATE * lenMs / 1000
        for (i in 0 until n) {
            val idx = s + i
            if (idx >= out.size) break
            val env = minOf(1.0, i / (RATE * 0.003), (n - i) / (RATE * 0.006))
            val w = 2 * PI * f * i / RATE
            out[idx] += (sin(w) + 0.33 * sin(3 * w) + 0.2 * sin(5 * w)) * env
        }
    }

    /** Two quick police-siren yelps, about a third of a second in all. */
    private fun policeChirp(): ShortArray {
        val out = DoubleArray(RATE * 380 / 1000)
        yelp(out, 0, 90, 70, 700.0, 1650.0)
        yelp(out, 175, 90, 70, 700.0, 1650.0)
        return normalized(out, 0.9)
    }

    /** One slow rise and fall, like a police wail. */
    private fun sirenWail(): ShortArray {
        val out = DoubleArray(RATE * 1500 / 1000)
        yelp(out, 0, 700, 700, 600.0, 1300.0)
        return normalized(out, 0.9)
    }

    /** Four alternating low and high tones, like an alarm horn. */
    private fun klaxon(): ShortArray {
        val out = DoubleArray(RATE * 800 / 1000)
        for (k in 0 until 4) buzz(out, k * 190, 170, if (k % 2 == 0) 440.0 else 587.0)
        return normalized(out, 0.9)
    }

    /** Five short, sharp beeps at one pitch. */
    private fun rapidPulse(): ShortArray {
        val out = DoubleArray(RATE * 450 / 1000)
        for (k in 0 until 5) buzz(out, k * 90, 45, 1500.0)
        return normalized(out, 0.9)
    }

    /** Plays the target alert sound [type] (index into [ALERT_SOUND_NAMES]). */
    fun playSound(type: Int = 0) {
        val data = alertSoundPcm(type.coerceIn(0, ALERT_SOUND_NAMES.lastIndex))
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            val ms = data.size * 1000L / RATE + 300
            handler.postDelayed({ runCatching { track.release() } }, ms)
        } catch (_: Exception) {
        }
    }

    // ---- game sounds: a rising arpeggio for level-ups, a two-note chime for achievements and titles ----

    private val levelUpPcm: ShortArray by lazy {
        arpeggio(listOf(523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0), 90, 800)
    }
    private val chimePcm: ShortArray by lazy { arpeggio(listOf(987.77, 1318.5), 85, 300) }
    private val fanfarePcm: ShortArray by lazy {
        arpeggio(listOf(659.25, 830.61, 987.77, 1318.5, 1661.2), 85, 650)
    }
    private val questPcm: ShortArray by lazy { arpeggio(listOf(783.99, 987.77, 1318.5), 70, 380) }

    /** Overlapping bell-like notes, each starting [stepMs] after the last; the final one rings for [lastMs]. */
    private fun arpeggio(freqs: List<Double>, stepMs: Int, lastMs: Int): ShortArray {
        val stepN = RATE * stepMs / 1000
        val lastN = RATE * lastMs / 1000
        val total = stepN * (freqs.size - 1) + lastN
        val out = DoubleArray(total)
        freqs.forEachIndexed { k, f ->
            val n = if (k == freqs.lastIndex) lastN else stepN * 3
            val start = k * stepN
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= total) break
                val env = minOf(1.0, i / (RATE * 0.005)) * Math.exp(-3.5 * i / n)
                out[idx] += (sin(2 * PI * f * i / RATE) + 0.3 * sin(4 * PI * f * i / RATE)) * env * 0.35
            }
        }
        return ShortArray(total) { (out[it].coerceIn(-1.0, 1.0) * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun playGame(pcm: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            handler.postDelayed({ runCatching { track.release() } }, pcm.size * 1000L / RATE + 300)
        } catch (_: Exception) {
        }
    }

    // ---- CRT power hum (faint, synthesized) ----------------------------------------------------

    /** Mains hum (60 Hz plus a little 120/180) for [lenMs], scaled by [env] (0..1 over the sound). */
    private fun humInto(out: DoubleArray, amp: Double, env: (Double) -> Double) {
        val n = out.size
        for (i in 0 until n) {
            val u = i.toDouble() / n
            val w = 2 * PI * i / RATE
            out[i] += (sin(w * 60) + 0.5 * sin(w * 120) + 0.2 * sin(w * 180)) * amp * env(u)
        }
    }

    /** A whine whose pitch moves exponentially from [f0] to [f1] across the whole buffer. */
    private fun whineInto(out: DoubleArray, f0: Double, f1: Double, amp: Double, env: (Double) -> Double) {
        val n = out.size
        var phase = 0.0
        for (i in 0 until n) {
            val u = i.toDouble() / n
            val f = f0 * Math.pow(f1 / f0, u)
            phase += 2 * PI * f / RATE
            out[i] += sin(phase) * amp * env(u)
        }
    }

    /** Switching off: the tube's whine falls away, the hum dies and a soft thump lands as the dot goes out. */
    private val crtOffPcm: ShortArray by lazy {
        val out = DoubleArray(RATE * 760 / 1000)
        val cut = 420.0 / 760.0
        whineInto(out, 2200.0, 90.0, 0.35) { u -> if (u < cut) 1.0 - 0.6 * u / cut else 0.0 }
        humInto(out, 0.5) { u -> if (u < cut) 1.0 else maxOf(0.0, 1.0 - (u - cut) / 0.25) }
        val t0 = (cut * out.size).toInt()
        for (i in 0 until RATE * 160 / 1000) {
            val idx = t0 + i
            if (idx >= out.size) break
            out[idx] += sin(2 * PI * 52.0 * i / RATE) * Math.exp(-i / (RATE * 0.035)) * 0.9
        }
        normalized(out, 0.32)
    }

    /** Switching on: a degauss thunk, a rising whine and the hum swelling in, then fading to quiet. */
    private val crtOnPcm: ShortArray by lazy {
        val out = DoubleArray(RATE * 900 / 1000)
        for (i in 0 until RATE * 220 / 1000) {
            val wob = 1.0 + 0.25 * sin(2 * PI * 9.0 * i / RATE)
            out[i] += sin(2 * PI * 48.0 * wob * i / RATE) * Math.exp(-i / (RATE * 0.06)) * 0.9
        }
        whineInto(out, 180.0, 1900.0, 0.3) { u -> if (u < 0.5) u / 0.5 else maxOf(0.0, 1.0 - (u - 0.5) / 0.3) }
        humInto(out, 0.5) { u -> if (u < 0.15) u / 0.15 else maxOf(0.0, 1.0 - (u - 0.15) / 0.85) }
        normalized(out, 0.32)
    }

    fun playCrtOff() = playGame(crtOffPcm)

    fun playCrtOn() = playGame(crtOnPcm)

    fun playLevelUp() = playGame(levelUpPcm)

    fun playChime() = playGame(chimePcm)

    /** Longer, brighter rise for achievements, perfect days and streak milestones. */
    fun playFanfare() = playGame(fanfarePcm)

    /** Three quick notes for a finished quest or a streak day. */
    fun playQuest() = playGame(questPcm)

    // ---- new-device sounds ------------------------------------------------------------------

    val NEW_SOUND_NAMES = listOf("PING", "HEARTBEAT", "SONAR", "DATA")

    private val newPcm = HashMap<Int, ShortArray>()

    private fun newSoundPcm(type: Int): ShortArray = newPcm.getOrPut(type) {
        when (type) {
            1 -> heartbeat()
            2 -> sonar()
            3 -> dataChirp()
            else -> arpeggio(listOf(1318.5, 1975.5), 60, 240)
        }
    }

    /** Plays one of the new-device sounds (0 = ping, 1 = heartbeat, 2 = sonar, 3 = data chirp). */
    fun playNewDevice(type: Int) = playGame(newSoundPcm(type.coerceIn(0, NEW_SOUND_NAMES.lastIndex)))

    private fun normalized(out: DoubleArray, peak: Double = 0.85): ShortArray {
        var m = 1e-9
        for (v in out) m = maxOf(m, Math.abs(v))
        return ShortArray(out.size) { (out[it] / m * peak * Short.MAX_VALUE).toInt().toShort() }
    }

    /** "Lub-dub": two low thumps whose pitch sags, with overtones so small phone speakers can reproduce them. */
    private fun heartbeat(): ShortArray {
        val total = RATE * 560 / 1000
        val out = DoubleArray(total)
        fun thump(startMs: Int, f0: Double, amp: Double, lenMs: Int) {
            val s = RATE * startMs / 1000
            val n = RATE * lenMs / 1000
            var phase = 0.0
            for (i in 0 until n) {
                val idx = s + i
                if (idx >= total) break
                val t = i.toDouble() / n
                phase += 2 * PI * f0 * (1.0 - 0.35 * t) / RATE
                val env = minOf(1.0, i / (RATE * 0.006)) * Math.exp(-5.5 * t)
                out[idx] += (sin(phase) + 0.55 * sin(2 * phase) + 0.3 * sin(3 * phase)) * env * amp
            }
        }
        thump(0, 62.0, 1.0, 190)
        thump(230, 52.0, 0.7, 170)
        return normalized(out, 0.95)
    }

    /** One soft ping with a fainter echo. */
    private fun sonar(): ShortArray {
        val total = RATE * 900 / 1000
        val out = DoubleArray(total)
        fun ping(startMs: Int, amp: Double) {
            val s = RATE * startMs / 1000
            val n = RATE * 450 / 1000
            for (i in 0 until n) {
                val idx = s + i
                if (idx >= total) break
                val env = minOf(1.0, i / (RATE * 0.004)) * Math.exp(-7.0 * i / n)
                out[idx] += sin(2 * PI * 1046.5 * i / RATE) * env * amp
            }
        }
        ping(0, 1.0)
        ping(330, 0.35)
        return normalized(out)
    }

    /** Four quick digital blips climbing in pitch. */
    private fun dataChirp(): ShortArray {
        val freqs = listOf(2093.0, 2637.0, 3136.0, 3520.0)
        val blipN = RATE * 38 / 1000
        val gapN = RATE * 22 / 1000
        val out = DoubleArray(freqs.size * (blipN + gapN))
        freqs.forEachIndexed { k, f ->
            for (i in 0 until blipN) {
                val env = minOf(1.0, i / (RATE * 0.002), (blipN - i) / (RATE * 0.006))
                val w = sin(2 * PI * f * i / RATE)
                out[k * (blipN + gapN) + i] = (0.6 * w + 0.4 * Math.signum(w)) * env
            }
        }
        return normalized(out)
    }

    @Suppress("DEPRECATION")
    fun vibrateShort(ctx: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 50, 70, 50, 160), -1))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    fun vibrate(ctx: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))
        } catch (_: Exception) {
        }
    }
}
