package com.ghadirb.aimusic.recommendation.signals

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.config.SkipModelConfig

/**
 * Spec §3 — meaningful listening behaviours.
 *
 * The app stores ONE light row per listening session (never one per second). Milestone events
 * (PLAYED_10_PERCENT … PLAY_COMPLETED, SKIPPED_*, REPLAYED) are *derived* from that row by
 * [BehaviorClassifier]; discrete user actions (FAVORITED, UNFAVORITED, ADDED_TO_PLAYLIST,
 * REMOVED_FROM_PLAYLIST) are stored in the small `behavior_event` table.
 */
enum class ListeningBehavior {
    PLAY_STARTED,
    PLAYED_10_PERCENT,
    PLAYED_25_PERCENT,
    PLAYED_50_PERCENT,
    PLAYED_75_PERCENT,
    PLAYED_90_PERCENT,
    PLAY_COMPLETED,
    SKIPPED_EARLY,
    SKIPPED_MIDDLE,
    FAVORITED,
    UNFAVORITED,
    REPLAYED,
    ADDED_TO_PLAYLIST,
    REMOVED_FROM_PLAYLIST;

    /** Behaviours that are stored explicitly (user actions), as opposed to derived ones. */
    val isExplicitAction: Boolean
        get() = this == FAVORITED || this == UNFAVORITED || this == ADDED_TO_PLAYLIST || this == REMOVED_FROM_PLAYLIST
}

object BehaviorClassifier {
    /** Milestones a single listening session reached. Always starts with PLAY_STARTED. */
    fun classify(entry: ListeningHistoryEntity, cfg: SkipModelConfig = SkipModelConfig()): List<ListeningBehavior> {
        val out = ArrayList<ListeningBehavior>(6)
        out += ListeningBehavior.PLAY_STARTED
        val f = entry.completedPercentage
        if (f >= 0.10f) out += ListeningBehavior.PLAYED_10_PERCENT
        if (f >= 0.25f) out += ListeningBehavior.PLAYED_25_PERCENT
        if (f >= 0.50f) out += ListeningBehavior.PLAYED_50_PERCENT
        if (f >= 0.75f) out += ListeningBehavior.PLAYED_75_PERCENT
        if (f >= 0.90f) out += ListeningBehavior.PLAYED_90_PERCENT
        when {
            !entry.skipped && f >= cfg.completedThreshold -> out += ListeningBehavior.PLAY_COMPLETED
            entry.skipped && f < cfg.earlySkipMaxFraction -> out += ListeningBehavior.SKIPPED_EARLY
            entry.skipped -> out += ListeningBehavior.SKIPPED_MIDDLE
        }
        if (entry.replayCount > 0) out += ListeningBehavior.REPLAYED
        return out
    }
}
