package com.ghadirb.aimusic.recommendation.signals

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.exp

object TimeContext {
    fun isWeekend(timeMs: Long, zone: TimeZone, weekendDays: Set<Int>): Boolean =
        Calendar.getInstance(zone).apply { timeInMillis = timeMs }.get(Calendar.DAY_OF_WEEK) in weekendDays
}

/**
 * Spec §5 — independent behavioural signals of one track. Completion, skip, replay and favourite are
 * separate numbers: "10 plays + 9 completions" and "10 plays + 9 skips" end up far apart.
 */
data class TrackSignal(
    val trackId: Long,
    val plays: Int,
    val completions: Int,
    val skips: Int,
    val replays: Int,
    /** completions / plays (raw, independent of how many plays there were). */
    val completionRate: Double,
    val skipRate: Double,
    val replayRate: Double,
    /** Laplace-smoothed completion rate: 1/1 is less convincing than 9/10. 0..1. */
    val completionScore: Double,
    /** Σ recency-decayed completions. */
    val decayedCompletions: Double,
    /** Smoothed, recency-decayed skip severity (see [SkipModel]). 0..1. */
    val negativeScore: Double,
    /** Saturating replay signal. 0..1. */
    val replayScore: Double,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
    /** Completions in the last 14 days. */
    val recentCompletions: Int,
    /** Decayed completions per TimeBuckets bucket. */
    val daypartCompletions: Map<String, Double>,
    val weekdayCompletions: Double,
    val weekendCompletions: Double
)

class ListeningSignals(val byTrack: Map<Long, TrackSignal>) {
    val maxPlays: Int = byTrack.values.maxOfOrNull { it.plays } ?: 0
    val hasHistory: Boolean get() = byTrack.isNotEmpty()
    operator fun get(trackId: Long): TrackSignal? = byTrack[trackId]

    companion object {
        val EMPTY = ListeningSignals(emptyMap())
    }
}

/** Turns raw listening sessions into per-track [TrackSignal]s. Pure and deterministic. */
class SignalAggregator(private val config: RecommendationConfig = RecommendationConfig()) {
    private val decay = RecencyDecay(config.recency)
    private val skipModel = SkipModel(config.skip)

    private class Acc {
        var plays = 0; var completions = 0; var skips = 0; var replays = 0
        var decayedCompletions = 0.0; var negativeSum = 0.0; var weightSum = 0.0
        var first: Long? = null; var last: Long? = null
        var recent = 0
        var weekday = 0.0; var weekend = 0.0
        val dayparts = HashMap<String, Double>()
    }

    fun aggregate(
        validTrackIds: Set<Long>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): ListeningSignals {
        if (history.isEmpty()) return ListeningSignals.EMPTY
        val accs = HashMap<Long, Acc>()
        for (e in history) {
            if (e.trackId !in validTrackIds) continue
            val acc = accs.getOrPut(e.trackId) { Acc() }
            val age = (nowMs - e.startTime).coerceAtLeast(0L)
            val w = decay.weightForAge(age)
            acc.plays++
            acc.weightSum += w
            if (skipModel.isCompleted(e)) {
                acc.completions++
                acc.decayedCompletions += w
                if (age <= RECENT_WINDOW_MS) acc.recent++
                val bucket = TimeBuckets.bucketOf(e.startTime, zone)
                acc.dayparts[bucket] = (acc.dayparts[bucket] ?: 0.0) + w
                if (TimeContext.isWeekend(e.startTime, zone, config.context.weekendDays)) acc.weekend += w else acc.weekday += w
            }
            if (e.skipped) {
                acc.skips++
                acc.negativeSum += skipModel.penalty(e) * w
            }
            acc.replays += e.replayCount
            if (acc.first == null || e.startTime < acc.first!!) acc.first = e.startTime
            if (acc.last == null || e.startTime > acc.last!!) acc.last = e.startTime
        }
        val out = HashMap<Long, TrackSignal>(accs.size)
        for ((id, a) in accs) {
            val plays = a.plays.toDouble()
            out[id] = TrackSignal(
                trackId = id,
                plays = a.plays, completions = a.completions, skips = a.skips, replays = a.replays,
                completionRate = a.completions / plays,
                skipRate = a.skips / plays,
                replayRate = (a.replays / plays).coerceAtMost(1.0),
                completionScore = (a.completions + 1.0) / (plays + 2.0),
                decayedCompletions = a.decayedCompletions,
                negativeScore = (a.negativeSum / (a.weightSum + 1.0)).coerceIn(0.0, 1.0),
                replayScore = 1.0 - exp(-a.replays / 2.0),
                firstPlayedAt = a.first, lastPlayedAt = a.last,
                recentCompletions = a.recent,
                daypartCompletions = a.dayparts,
                weekdayCompletions = a.weekday, weekendCompletions = a.weekend
            )
        }
        return ListeningSignals(out)
    }

    private companion object {
        const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    }
}
