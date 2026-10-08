package com.ghadirb.aimusic.recommendation.events

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.RecommendationEventEntity
import com.ghadirb.aimusic.recommendation.SectionResult
import com.ghadirb.aimusic.recommendation.config.SkipModelConfig
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack

/**
 * Spec §18 — pure helpers around [RecommendationEventEntity]. Persistence lives in MusicRepository;
 * everything here is local-only and has no Android dependency, so it is unit-testable.
 */
object RecommendationEvents {

    /** A suggestion counts as "chosen" if the track is played within this window after it was shown. */
    const val ATTRIBUTION_WINDOW_MS = 24L * 60 * 60 * 1000

    /** Events older than this are pruned by the background worker. */
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    /** The main source that proposed a pick (lowest enum order wins), or "UNKNOWN". */
    fun mainSource(pick: ScoredTrack): String = pick.candidate.sources.minByOrNull { it.ordinal }?.name ?: "UNKNOWN"

    /** One row per suggested track (position = index in the section, 0-based). */
    fun toEntities(result: SectionResult, nowMs: Long, algorithmVersion: String): List<RecommendationEventEntity> =
        result.picks.mapIndexed { index, pick ->
            RecommendationEventEntity(
                trackId = pick.trackId,
                section = result.section.type.id,
                source = mainSource(pick),
                position = index,
                score = pick.score,
                timestamp = nowMs,
                algorithmVersion = algorithmVersion,
                discovery = pick.vector.isUnplayed
            )
        }

    /** What a finished listening session means for the suggestion that led to it: (completed, skipped). */
    fun outcomeOf(entry: ListeningHistoryEntity, cfg: SkipModelConfig = SkipModelConfig()): Pair<Boolean, Boolean> {
        val completed = !entry.skipped && entry.completedPercentage >= cfg.completedThreshold
        return completed to entry.skipped
    }

    /** Time window in which an unresolved suggestion of the played track is attributed to this session. */
    fun attributionWindow(entry: ListeningHistoryEntity): LongRange =
        (entry.startTime - ATTRIBUTION_WINDOW_MS)..(entry.startTime + SLACK_MS)

    private const val SLACK_MS = 60_000L
}
