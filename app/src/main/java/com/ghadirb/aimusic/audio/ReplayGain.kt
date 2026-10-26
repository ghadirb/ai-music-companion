package com.ghadirb.aimusic.audio

import kotlin.math.pow

/**
 * ReplayGain loudness levelling, the safe way: only the tag already stored in the file is used (no analysis), and
 * the volume is only ever LOWERED, never raised, so it can not cause clipping. Pure logic, JVM unit tested.
 */
object ReplayGain {

    /** "-6.50 dB", "+3.2dB", "-6,5 dB" -> -6.5f ; null for anything else. */
    fun parseGainDb(text: String?): Float? {
        val t = text?.trim()?.replace(',', '.')?.replace('−', '-') ?: return null
        val m = Regex("""^([+-]?\d+(?:\.\d+)?)\s*(?:dB)?$""", RegexOption.IGNORE_CASE).find(t) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        return if (v.isNaN() || v.isInfinite() || v < -60f || v > 60f) null else v
    }

    /** Picks the track gain from the file's tags (key/value pairs), else the album gain. Keys are case-insensitive. */
    fun pickGainDb(tags: List<Pair<String, String>>): Float? {
        fun find(key: String) = tags.firstOrNull { it.first.trim().equals(key, ignoreCase = true) }?.second?.let(::parseGainDb)
        return find("replaygain_track_gain") ?: find("replaygain_album_gain")
    }

    /** Linear player volume (0.05..1.0) for a tag value; 1.0 (no change) when there is no usable tag. */
    fun volumeFor(gainDb: Float?, preampDb: Float = 0f): Float {
        if (gainDb == null) return 1f
        val effective = (gainDb + preampDb).coerceAtMost(0f)      // never louder than the original
        return 10.0.pow(effective / 20.0).toFloat().coerceIn(MIN_VOLUME, 1f)
    }

    const val MIN_VOLUME = 0.05f
}
