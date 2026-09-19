package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * MVP recommendation logic: purely rule-based scoring over local listening
 * history + metadata. No audio analysis, no network, no ML model yet — this
 * is intentional (see README "AI Roadmap"). The scoring weights below are a
 * starting point and are meant to be tuned once real usage data exists.
 */
class RecommendationEngine(private val repository: MusicRepository) {

    /**
     * Scores every track using: favorite bonus, completion history, replay
     * count, and a skip penalty. Returns the top [limit] tracks, highest
     * score first. This backs the "پیشنهاد امروز" / "دوباره کشف کن" cards.
     */
    suspend fun topRecommendations(limit: Int = 10): List<TrackEntity> {
        val allTracks = repository.observeTracks().first()
        if (allTracks.isEmpty()) return emptyList()

        val history = repository.recentHistory(500)
        val historyByTrack = history.groupBy { it.trackId }

        val scored = allTracks.map { track ->
            val entries = historyByTrack[track.id].orEmpty()
            val completed = entries.count { it.completedPercentage >= 0.8 }
            val skipped = entries.count { it.skipped }
            val replays = entries.sumOf { it.replayCount }
            val favoriteBonus = if (track.isFavorite) 3.0 else 0.0

            val score = favoriteBonus + completed * 2.0 + replays * 1.5 - skipped * 1.0
            track to score
        }

        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * Builds/refreshes the single-row taste profile from recent history.
     * Called periodically (e.g. from a WorkManager job) rather than on every
     * screen open, per the doc's "بعد از چند روز استفاده" expectation.
     */
    fun buildTasteProfile(
        tracks: List<TrackEntity>,
        historyByTrack: Map<Long, List<ListeningHistoryEntity>>
    ): UserPreferenceEntity {
        val artistScore = mutableMapOf<String, Double>()
        val genreScore = mutableMapOf<String, Double>()
        var totalCompletedDuration = 0L
        var completedCount = 0

        for (track in tracks) {
            val entries = historyByTrack[track.id] ?: continue
            val completed = entries.count { it.completedPercentage >= 0.8 }
            val skipped = entries.count { it.skipped }
            val replays = entries.sumOf { it.replayCount }

            val weight = (completed * 2 + replays * 1.5 - skipped * 1.0).coerceAtLeast(0.0)
            if (weight <= 0) continue

            artistScore[track.artist] = (artistScore[track.artist] ?: 0.0) + weight
            track.genre?.let { genreScore[it] = (genreScore[it] ?: 0.0) + weight }

            if (completed > 0) {
                totalCompletedDuration += track.durationMs
                completedCount += completed
            }
        }

        val topArtists = artistScore.entries.sortedByDescending { it.value }.take(5).joinToString(",") { it.key }
        val topGenres = genreScore.entries.sortedByDescending { it.value }.take(5).joinToString(",") { it.key }
        val avgPreferredDuration = if (completedCount > 0) totalCompletedDuration / completedCount else 0L

        return UserPreferenceEntity(
            favoriteArtists = topArtists,
            favoriteGenres = topGenres,
            favoriteEnergyLevel = "unknown", // requires BPM/energy detection — future phase, see README
            preferredDurationMs = avgPreferredDuration,
            preferredTimeOfDay = currentTimeBucket()
        )
    }

    /** Rough day-part bucket used until real per-session timestamps are analyzed. */
    private fun currentTimeBucket(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "morning"
            in 11..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
    }
}
