package com.ghadirb.aimusic.recommendation.session

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.features.FeatureSet
import com.ghadirb.aimusic.recommendation.signals.ListeningBehavior
import com.ghadirb.aimusic.recommendation.signals.SkipModel
import com.ghadirb.aimusic.recommendation.signals.TimeContext
import java.util.TimeZone
import kotlin.math.pow

/** One explicit user action (favourite, playlist add …) — a domain view of a `behavior_event` row. */
data class BehaviorEvent(val trackId: Long, val behavior: ListeningBehavior, val timestamp: Long)

/**
 * Spec §9 — what is going on in the *current* listening session. Lists are newest-first.
 * "Positive" patterns come from tracks that were listened to; "skipped" patterns describe what the
 * user just rejected, so the ranker can steer away from it quickly.
 */
data class SessionContext(
    val nowMs: Long,
    val daypart: String,
    val isWeekend: Boolean,
    val recentlyPlayed: List<Long> = emptyList(),
    val recentlySkipped: List<Long> = emptyList(),
    val recentCompleted: List<Long> = emptyList(),
    val recentFavorited: List<Long> = emptyList(),
    val consecutiveSkips: Int = 0,
    val moodPattern: Map<String, Double> = emptyMap(),
    val energyPattern: Double? = null,
    val bpmPattern: Double? = null,
    val skippedMoodPattern: Map<String, Double> = emptyMap(),
    val skippedEnergyPattern: Double? = null,
    val skippedBpmPattern: Double? = null,
    /** 0..1: how strongly the user is currently rejecting suggestions (skip streak / trigger). */
    val skipPressure: Double = 0.0
) {
    val isActive: Boolean get() = recentlyPlayed.isNotEmpty()

    /** Multiplier for the session weight: it grows when the user keeps skipping, so ranking adapts fast. */
    val adaptationFactor: Double get() = 1.0 + 2.0 * skipPressure

    companion object {
        fun empty(nowMs: Long, zone: TimeZone = TimeZone.getDefault(), weekendDays: Set<Int> = setOf(5, 6)) =
            SessionContext(nowMs, TimeBuckets.bucketOf(nowMs, zone), TimeContext.isWeekend(nowMs, zone, weekendDays))
    }
}

class SessionAnalyzer(private val config: RecommendationConfig = RecommendationConfig()) {
    private val skipModel = SkipModel(config.skip)

    fun analyze(
        features: FeatureSet,
        history: List<ListeningHistoryEntity>,
        events: List<BehaviorEvent>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): SessionContext {
        val base = SessionContext.empty(nowMs, zone, config.context.weekendDays)
        val cfg = config.session
        val valid = history.filter { it.trackId in features.byId && it.startTime <= nowMs }.sortedByDescending { it.startTime }
        if (valid.isEmpty()) return base

        // Walk back from the newest entry while the pauses stay short.
        val session = ArrayList<ListeningHistoryEntity>()
        var laterStart = nowMs
        for (e in valid) {
            if (session.size >= cfg.maxEntries) break
            val end = e.startTime + e.listenDurationMs
            if (laterStart - end > cfg.gapMs) break
            if (nowMs - e.startTime > cfg.maxAgeMs) break
            session += e
            laterStart = e.startTime
        }
        if (session.isEmpty()) return base

        var streak = 0
        for (e in session) { if (e.skipped) streak++ else break }

        val moods = HashMap<String, Double>()
        val skippedMoods = HashMap<String, Double>()
        var energySum = 0.0; var energyW = 0.0; var bpmSum = 0.0; var bpmW = 0.0
        var sEnergySum = 0.0; var sEnergyW = 0.0; var sBpmSum = 0.0; var sBpmW = 0.0
        session.forEachIndexed { i, e ->
            val v = features.byId.getValue(e.trackId)
            val posW = DECAY.pow(i)
            if (e.skipped) {
                val w = posW * (0.4 + skipModel.penalty(e))
                v.energy?.let { sEnergySum += it * w; sEnergyW += w }
                v.bpm?.let { sBpmSum += it * w; sBpmW += w }
                v.mood?.let { skippedMoods.merge(FeatureNormalizer.key(it), w, Double::plus) }
            } else {
                v.energy?.let { energySum += it * posW; energyW += posW }
                v.bpm?.let { bpmSum += it * posW; bpmW += posW }
                v.mood?.let { moods.merge(FeatureNormalizer.key(it), posW, Double::plus) }
            }
        }

        val sessionStart = session.last().startTime
        val favorited = events.filter { it.behavior == ListeningBehavior.FAVORITED && it.timestamp >= sessionStart }
            .sortedByDescending { it.timestamp }.map { it.trackId }.distinct()
        val unfavorited = events.filter { it.behavior == ListeningBehavior.UNFAVORITED && it.timestamp >= sessionStart }.map { it.trackId }.toSet()

        return base.copy(
            recentlyPlayed = session.map { it.trackId },
            recentlySkipped = session.filter { it.skipped }.map { it.trackId },
            recentCompleted = session.filter { skipModel.isCompleted(it) }.map { it.trackId },
            recentFavorited = favorited.filter { it !in unfavorited },
            consecutiveSkips = streak,
            moodPattern = normalize(moods),
            energyPattern = if (energyW > 0) energySum / energyW else null,
            bpmPattern = if (bpmW > 0) bpmSum / bpmW else null,
            skippedMoodPattern = normalize(skippedMoods),
            skippedEnergyPattern = if (sEnergyW > 0) sEnergySum / sEnergyW else null,
            skippedBpmPattern = if (sBpmW > 0) sBpmSum / sBpmW else null,
            skipPressure = (streak.toDouble() / cfg.skipStreakTrigger).coerceIn(0.0, 1.0)
        )
    }

    private fun normalize(m: Map<String, Double>): Map<String, Double> {
        val t = m.values.sum()
        return if (t <= 0.0) emptyMap() else m.mapValues { it.value / t }
    }

    private companion object {
        const val DECAY = 0.85
    }
}
