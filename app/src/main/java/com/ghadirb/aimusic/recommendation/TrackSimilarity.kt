package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlin.math.abs

/** Offline, deterministic track-to-track similarity (artist, genre, album, energy, BPM, length). */
object TrackSimilarity {
    private const val UNKNOWN_ARTIST = "Unknown artist"
    private const val UNKNOWN_ALBUM = "Unknown album"

    fun score(source: TrackEntity, candidate: TrackEntity): Double {
        var score = 0.0
        if (candidate.artist == source.artist && source.artist != UNKNOWN_ARTIST) score += 4.0
        if (candidate.genre != null && candidate.genre == source.genre) score += 2.5
        if (candidate.album == source.album && source.album != UNKNOWN_ALBUM) score += 1.0
        val ce = candidate.energyLevel
        val se = source.energyLevel
        if (ce != null && se != null) score += (2.0 - abs(ce - se) * 4.0).coerceAtLeast(0.0)
        val cb = candidate.bpm
        val sb = source.bpm
        if (cb != null && sb != null) score += (1.5 - abs(cb - sb) / 40.0).coerceAtLeast(0.0)
        if (candidate.moodTag != null && candidate.moodTag == source.moodTag) score += 0.8
        return score + (0.5 - abs(candidate.durationMs - source.durationMs).toDouble() / 600_000.0).coerceAtLeast(0.0)
    }
}
