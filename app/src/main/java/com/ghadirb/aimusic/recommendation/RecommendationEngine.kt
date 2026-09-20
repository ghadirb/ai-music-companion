package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Thin, suspend-friendly façade over the pure scoring code. All heavy work runs on Dispatchers.Default,
 * uses only on-device data and every suggestion carries a short human-readable reason.
 */
class RecommendationEngine(
    private val repository: MusicRepository,
    private val config: ScoringConfig = ScoringConfig()
) {

    suspend fun recommend(limit: Int = 10, nowMs: Long = System.currentTimeMillis()): List<Recommendation> {
        val tracks = repository.observeTracks().first()
        if (tracks.isEmpty()) return emptyList()
        val history = repository.recentHistory(HISTORY_WINDOW)
        return withContext(Dispatchers.Default) { RecommendationScorer.score(tracks, history, nowMs, config).take(limit) }
    }

    suspend fun topRecommendations(limit: Int = 10): List<TrackEntity> = recommend(limit).map { it.track }

    /** Offline similarity ranking; ready to be replaced by an embedding model later. */
    suspend fun similarTracks(source: TrackEntity, limit: Int = 12): List<TrackEntity> {
        val tracks = repository.observeTracks().first()
        return withContext(Dispatchers.Default) {
            tracks.asSequence()
                .filter { it.id != source.id }
                .map { candidate -> candidate to TrackSimilarity.score(source, candidate) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    fun buildTasteProfile(
        tracks: List<TrackEntity>,
        historyByTrack: Map<Long, List<ListeningHistoryEntity>>
    ): UserPreferenceEntity =
        TasteProfileBuilder.build(tracks, historyByTrack.values.flatten(), System.currentTimeMillis(), config)

    private companion object {
        const val HISTORY_WINDOW = 3000
    }
}
