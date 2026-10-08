package com.ghadirb.aimusic.recommendation.signals

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.config.RecencyConfig
import com.ghadirb.aimusic.recommendation.config.SkipModelConfig

/**
 * Spec §4 — a skip is interpreted by *when* it happened.
 * Returns a penalty in 0..1 (1 = strongest negative, ~0 = almost neutral). Thresholds come from
 * [SkipModelConfig], nothing is hard-coded here.
 */
class SkipModel(private val cfg: SkipModelConfig = SkipModelConfig()) {

    fun isCompleted(e: ListeningHistoryEntity): Boolean = !e.skipped && e.completedPercentage >= cfg.completedThreshold

    fun penalty(listenedMs: Long, fraction: Float): Double {
        if (listenedMs < cfg.strongSkipMaxMs) return cfg.strongPenalty
        return when {
            fraction >= cfg.neutralSkipMinFraction -> cfg.neutralPenalty
            fraction >= cfg.weakSkipMinFraction -> cfg.weakPenalty
            fraction <= cfg.mediumSkipMaxFraction -> cfg.mediumPenalty
            else -> {
                // Gap between "medium" and "weak": interpolate so the curve has no jump.
                val t = (fraction - cfg.mediumSkipMaxFraction) / (cfg.weakSkipMinFraction - cfg.mediumSkipMaxFraction)
                cfg.mediumPenalty + (cfg.weakPenalty - cfg.mediumPenalty) * t
            }
        }
    }

    /** 0 for a session that was not a skip. */
    fun penalty(e: ListeningHistoryEntity): Double =
        if (!e.skipped) 0.0 else penalty(e.listenDurationMs, e.completedPercentage)
}

/**
 * Spec §6 — recency decay. Piecewise-linear over "days ago" using [RecencyConfig.anchors];
 * recent interest weighs far more than old interest.
 */
class RecencyDecay(private val cfg: RecencyConfig = RecencyConfig()) {

    fun weight(daysAgo: Double): Double {
        val a = cfg.anchors
        val d = if (daysAgo < 0.0) 0.0 else daysAgo
        if (d <= a.first().first) return a.first().second
        for (i in 1 until a.size) {
            val (d1, w1) = a[i]
            if (d <= d1) {
                val (d0, w0) = a[i - 1]
                return w0 + (w1 - w0) * ((d - d0) / (d1 - d0))
            }
        }
        return a.last().second
    }

    fun weightForAge(ageMs: Long): Double = weight(ageMs.toDouble() / DAY_MS)

    companion object {
        const val DAY_MS = 24.0 * 60 * 60 * 1000
    }
}
