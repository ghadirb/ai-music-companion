package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar
import kotlin.math.abs

/** Explainable, on-device personalized ranking built from local behavior. */
class RecommendationEngine(private val repository: MusicRepository) {

    suspend fun topRecommendations(limit: Int = 10): List<TrackEntity> {
        val allTracks = repository.observeTracks().first()
        if (allTracks.isEmpty()) return emptyList()
        val history = repository.recentHistory(500)
        val historyByTrack = history.groupBy { it.trackId }
        val likedTracks = allTracks.filter { track ->
            val entries = historyByTrack[track.id].orEmpty()
            track.isFavorite || entries.count { it.completedPercentage >= 0.8 } > entries.count { it.skipped }
        }
        val favoriteArtists = likedTracks.map { it.artist }.filter { it != "Unknown artist" }.toSet()
        val favoriteGenres = likedTracks.mapNotNull { it.genre }.toSet()
        val tracksById = allTracks.associateBy { it.id }
        val timeMatchedTracks = history.filter {
            it.completedPercentage >= 0.8 && timeBucket(it.startTime) == currentTimeBucket()
        }.mapNotNull { tracksById[it.trackId] }
        val preferredEnergy = timeMatchedTracks.mapNotNull { it.energyLevel }.average().takeIf { !it.isNaN() }

        return allTracks.map { track ->
            val entries = historyByTrack[track.id].orEmpty()
            val completed = entries.count { it.completedPercentage >= 0.8 }
            val skipped = entries.count { it.skipped }
            val replays = entries.sumOf { it.replayCount }
            val favoriteBonus = if (track.isFavorite) 3.0 else 0.0
            val artistAffinity = if (track.artist in favoriteArtists) 2.0 else 0.0
            val genreAffinity = if (track.genre != null && track.genre in favoriteGenres) 1.25 else 0.0
            val energyAffinity = if (preferredEnergy != null && track.energyLevel != null) {
                (1.5 - abs(track.energyLevel - preferredEnergy) * 3.0).coerceAtLeast(0.0)
            } else 0.0
            track to (favoriteBonus + completed * 2.0 + replays * 1.5 - skipped + artistAffinity + genreAffinity + energyAffinity)
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Offline similarity ranking; ready to be replaced by an embedding model later. */
    suspend fun similarTracks(source: TrackEntity, limit: Int = 12): List<TrackEntity> =
        repository.observeTracks().first().asSequence()
            .filter { it.id != source.id }
            .map { candidate -> candidate to similarityScore(source, candidate) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()

    private fun similarityScore(source: TrackEntity, candidate: TrackEntity): Double {
        var score = 0.0
        if (candidate.artist == source.artist && source.artist != "Unknown artist") score += 4.0
        if (candidate.genre != null && candidate.genre == source.genre) score += 2.5
        if (candidate.album == source.album && source.album != "Unknown album") score += 1.0
        if (candidate.energyLevel != null && source.energyLevel != null) score +=
            (2.0 - abs(candidate.energyLevel - source.energyLevel) * 4.0).coerceAtLeast(0.0)
        if (candidate.bpm != null && source.bpm != null) score +=
            (1.5 - abs(candidate.bpm - source.bpm) / 40.0).coerceAtLeast(0.0)
        return score + (0.5 - abs(candidate.durationMs - source.durationMs).toDouble() / 600_000.0).coerceAtLeast(0.0)
    }

    fun buildTasteProfile(tracks: List<TrackEntity>, historyByTrack: Map<Long, List<ListeningHistoryEntity>>): UserPreferenceEntity {
        val artistScore = mutableMapOf<String, Double>()
        val genreScore = mutableMapOf<String, Double>()
        val timeBuckets = mutableMapOf<String, Double>()
        var totalCompletedDuration = 0L
        var completedCount = 0
        var weightedEnergy = 0.0
        var energyWeight = 0.0

        for (track in tracks) {
            val entries = historyByTrack[track.id] ?: continue
            val completed = entries.count { it.completedPercentage >= 0.8 }
            val skipped = entries.count { it.skipped }
            val replays = entries.sumOf { it.replayCount }
            val weight = (completed * 2 + replays * 1.5 - skipped).coerceAtLeast(0.0)
            if (weight <= 0) continue
            artistScore[track.artist] = (artistScore[track.artist] ?: 0.0) + weight
            track.genre?.let { genreScore[it] = (genreScore[it] ?: 0.0) + weight }
            track.energyLevel?.let { weightedEnergy += it * weight; energyWeight += weight }
            entries.filter { it.completedPercentage >= 0.8 }.forEach { entry ->
                val bucket = timeBucket(entry.startTime)
                timeBuckets[bucket] = (timeBuckets[bucket] ?: 0.0) + 1.0
            }
            if (completed > 0) { totalCompletedDuration += track.durationMs; completedCount += completed }
        }

        return UserPreferenceEntity(
            favoriteArtists = artistScore.entries.sortedByDescending { it.value }.take(5).joinToString(",") { it.key },
            favoriteGenres = genreScore.entries.sortedByDescending { it.value }.take(5).joinToString(",") { it.key },
            favoriteEnergyLevel = if (energyWeight > 0) "%.2f".format(weightedEnergy / energyWeight) else "unknown",
            preferredDurationMs = if (completedCount > 0) totalCompletedDuration / completedCount else 0L,
            preferredTimeOfDay = timeBuckets.maxByOrNull { it.value }?.key ?: "unknown"
        )
    }

    private fun currentTimeBucket(): String = timeBucket(System.currentTimeMillis())
    private fun timeBucket(timeMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)
        return when (hour) { in 5..10 -> "morning"; in 11..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
    }
}
