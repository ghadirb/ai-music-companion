package com.ghadirb.aimusic.audio

import kotlin.math.ln

/** One equaliser preset, described independently of the device: dB gain at some anchor frequencies (Hz). */
data class EqPreset(val id: Int, val name: String, val anchors: List<Pair<Int, Float>>)

/** Presets + the maths that fits them onto whatever bands the phone's equaliser has (usually 5). JVM unit tested. */
object EqPresets {
    const val CUSTOM = -1

    val ALL: List<EqPreset> = listOf(
        EqPreset(0, "تخت (بدون تغییر)", listOf(60 to 0f, 16000 to 0f)),
        EqPreset(1, "تقویت بیس", listOf(60 to 6f, 250 to 3f, 1000 to 0f, 4000 to 0f, 16000 to 0f)),
        EqPreset(2, "تقویت تیزی", listOf(60 to -1f, 1000 to 0f, 4000 to 3f, 12000 to 6f)),
        EqPreset(3, "صدای خواننده", listOf(60 to -3f, 250 to -1f, 1000 to 3f, 3000 to 4f, 8000 to 0f, 16000 to -2f)),
        EqPreset(4, "راک", listOf(60 to 5f, 250 to 2f, 1000 to -2f, 4000 to 3f, 12000 to 5f)),
        EqPreset(5, "پاپ", listOf(60 to -1f, 250 to 2f, 1000 to 4f, 4000 to 2f, 12000 to -1f)),
        EqPreset(6, "جز", listOf(60 to 3f, 250 to 2f, 1000 to -2f, 4000 to 2f, 12000 to 4f)),
        EqPreset(7, "کلاسیک", listOf(60 to 4f, 250 to 2f, 1000 to -1f, 4000 to 2f, 12000 to 3f)),
        EqPreset(8, "رقص و الکترونیک", listOf(60 to 6f, 250 to 3f, 1000 to 0f, 4000 to 2f, 12000 to 4f)),
        EqPreset(9, "پادکست و گفتار", listOf(60 to -6f, 250 to -2f, 1000 to 3f, 3000 to 5f, 8000 to 1f, 16000 to -4f))
    )

    fun byId(id: Int): EqPreset? = ALL.firstOrNull { it.id == id }

    /** Gain in dB at [freqHz]: linear in log-frequency between anchors, flat outside them. */
    fun dbAt(anchors: List<Pair<Int, Float>>, freqHz: Int): Float {
        if (anchors.isEmpty()) return 0f
        val sorted = anchors.sortedBy { it.first }
        if (freqHz <= sorted.first().first) return sorted.first().second
        if (freqHz >= sorted.last().first) return sorted.last().second
        val hi = sorted.indexOfFirst { it.first >= freqHz }
        val (f0, g0) = sorted[hi - 1]
        val (f1, g1) = sorted[hi]
        val t = (ln(freqHz.toFloat()) - ln(f0.toFloat())) / (ln(f1.toFloat()) - ln(f0.toFloat()))
        return g0 + (g1 - g0) * t
    }

    /** Millibel gain per device band, clamped to the band range the hardware reports. */
    fun gainsMb(preset: EqPreset, centerFreqsHz: List<Int>, minMb: Int, maxMb: Int): List<Int> =
        centerFreqsHz.map { (dbAt(preset.anchors, it) * 100f).toInt().coerceIn(minMb, maxMb) }
}

/** Everything the user can set in the sound panel. Saved as one small string. */
data class AudioEffectsSettings(
    val enabled: Boolean = false,
    val preset: Int = 0,
    val bandGainsMb: List<Int> = emptyList(),
    /** 0..1000 */
    val bassStrength: Int = 0,
    /** 0..1000 ("surround") */
    val virtualizerStrength: Int = 0,
    /** extra volume in millibel, 0..MAX_LOUDNESS_MB */
    val loudnessMb: Int = 0,
    val skipSilence: Boolean = false,
    /** Level loudness with the file's own ReplayGain tag (only lowers volume). */
    val replayGain: Boolean = false
) {
    fun sanitized() = copy(
        bassStrength = bassStrength.coerceIn(0, 1000),
        virtualizerStrength = virtualizerStrength.coerceIn(0, 1000),
        loudnessMb = loudnessMb.coerceIn(0, MAX_LOUDNESS_MB)
    )

    /** Band gains to apply on a device with [bands] bands: the saved ones, or the preset's, or flat. */
    fun gainsFor(centerFreqsHz: List<Int>, minMb: Int, maxMb: Int): List<Int> = when {
        bandGainsMb.size == centerFreqsHz.size -> bandGainsMb.map { it.coerceIn(minMb, maxMb) }
        else -> EqPresets.byId(preset)?.let { EqPresets.gainsMb(it, centerFreqsHz, minMb, maxMb) } ?: centerFreqsHz.map { 0 }
    }

    fun encode(): String = listOf(
        if (enabled) 1 else 0, preset, bandGainsMb.joinToString(","), bassStrength, virtualizerStrength, loudnessMb, if (skipSilence) 1 else 0,
        if (replayGain) 1 else 0
    ).joinToString(";")

    companion object {
        const val MAX_LOUDNESS_MB = 1500

        fun decode(text: String?): AudioEffectsSettings {
            val f = text?.split(';') ?: return AudioEffectsSettings()
            if (f.size != 7 && f.size != 8) return AudioEffectsSettings()   // 7 = saved before ReplayGain existed
            return runCatching {
                AudioEffectsSettings(
                    enabled = f[0] == "1",
                    preset = f[1].toInt(),
                    bandGainsMb = if (f[2].isBlank()) emptyList() else f[2].split(',').map { it.toInt() },
                    bassStrength = f[3].toInt(),
                    virtualizerStrength = f[4].toInt(),
                    loudnessMb = f[5].toInt(),
                    skipSilence = f[6] == "1",
                    replayGain = f.getOrNull(7) == "1"
                ).sanitized()
            }.getOrDefault(AudioEffectsSettings())
        }
    }
}

/** A-B repeat: loop the part between two points of the current song (practice, favourite part). */
data class AbLoop(val aMs: Long? = null, val bMs: Long? = null) {
    val active: Boolean get() = aMs != null && bMs != null && bMs > aMs
}

object AbLoopLogic {
    const val MIN_LENGTH_MS = 500L

    fun setA(loop: AbLoop, positionMs: Long): AbLoop {
        val b = loop.bMs
        return AbLoop(positionMs, if (b != null && b >= positionMs + MIN_LENGTH_MS) b else null)
    }

    /** B only counts if A exists and the loop would be at least [MIN_LENGTH_MS] long. */
    fun setB(loop: AbLoop, positionMs: Long): AbLoop {
        val a = loop.aMs ?: return loop
        return if (positionMs >= a + MIN_LENGTH_MS) AbLoop(a, positionMs) else loop
    }

    /** Where to jump when playback reached the end of an active loop, else null. */
    fun seekTarget(loop: AbLoop, positionMs: Long): Long? =
        if (loop.active && positionMs >= loop.bMs!!) loop.aMs else null
}
