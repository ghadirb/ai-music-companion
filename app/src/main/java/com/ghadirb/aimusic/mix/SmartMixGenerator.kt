package com.ghadirb.aimusic.mix

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.ListeningStats
import com.ghadirb.aimusic.recommendation.Reason
import com.ghadirb.aimusic.recommendation.ReasonType
import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.RecommendationScorer
import com.ghadirb.aimusic.recommendation.ScoringConfig
import java.util.Random
import java.util.TimeZone

/** [occasion] mixes answer "what do I want to listen to now?" (workout, driving, …); the others are personal. */
enum class MixType(val titleFa: String, val emoji: String, val occasion: Boolean) {
    MY_FAVORITES("علاقه‌مندی‌های من", "❤️", false),
    RECENTLY_LOVED("اخیراً دوست داشتی", "🔥", false),
    REDISCOVER("دوباره کشف کن", "🔄", false),
    RANDOM_FROM_TASTE("تصادفی از سلیقهٔ من", "🎲", false),

    WORKOUT("ورزشی", "🏋️", true),
    DRIVING("رانندگی", "🚗", true),
    HAPPY("شاد", "😄", true),
    CHILL("آرام", "🌿", true),
    FOCUS("تمرکز و مطالعه", "🎯", true),
    NIGHT("مناسب شب", "🌙", true),
    SAD("غمگین", "😢", true),
    MORNING("شروع روز", "☀️", true),
    ENERGETIC("پرانرژی", "⚡", true);

    companion object {
        /** The occasion that fits the time of day best (used as the default selection on Home). */
        fun suggestedFor(bucket: String): MixType = when (bucket) {
            "morning" -> MORNING
            "afternoon" -> FOCUS
            "evening" -> DRIVING
            else -> NIGHT
        }
    }
}

data class SmartMix(val type: MixType, val tracks: List<Recommendation>) {
    val trackList: List<TrackEntity> get() = tracks.map { it.track }
}

/**
 * Builds the smart mixes purely from the user's own library + local listening history
 * (no network). Deterministic for the same input; the "random" mix is seeded by the calendar day
 * so it is stable during the day and changes tomorrow.
 */
object SmartMixGenerator {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CALM = "calm"
    private const val NEUTRAL = "neutral"
    private const val SAD = "sad"
    private const val HAPPY = "happy"
    private const val ENERGETIC = "energetic"

    fun generateAll(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        limit: Int = 30,
        config: ScoringConfig = ScoringConfig(),
        zone: TimeZone = TimeZone.getDefault()
    ): List<SmartMix> {
        if (tracks.isEmpty()) return emptyList()
        val stats = ListeningStats.build(tracks, history, nowMs, config, zone)
        val scored = RecommendationScorer.score(tracks, history, nowMs, config, zone, stats)
        return MixType.values().map { type -> SmartMix(type, build(type, scored, stats, nowMs, limit, config)) }
            .filter { it.tracks.isNotEmpty() }
    }

    fun generate(
        type: MixType,
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        limit: Int = 30,
        config: ScoringConfig = ScoringConfig(),
        zone: TimeZone = TimeZone.getDefault()
    ): List<Recommendation> {
        val stats = ListeningStats.build(tracks, history, nowMs, config, zone)
        val scored = RecommendationScorer.score(tracks, history, nowMs, config, zone, stats)
        return build(type, scored, stats, nowMs, limit, config)
    }

    private fun build(
        type: MixType,
        scored: List<Recommendation>,
        stats: ListeningStats,
        nowMs: Long,
        limit: Int,
        config: ScoringConfig
    ): List<Recommendation> = when (type) {
        MixType.MY_FAVORITES -> scored.filter { it.track.isFavorite }
            .map { it.copy(reasons = listOf(Reason(ReasonType.FAVORITE))) }.take(limit)

        MixType.RECENTLY_LOVED -> scored
            .filter { (stats.signals[it.track.id]?.recentCompletions ?: 0) >= 1 }
            .sortedWith(compareByDescending<Recommendation> { stats.signals[it.track.id]?.recentCompletions ?: 0 }.thenByDescending { it.score })
            .map { r -> r.copy(reasons = listOf(Reason(ReasonType.RECENTLY_LOVED, number = stats.signals[r.track.id]?.recentCompletions))) }
            .take(limit)

        MixType.REDISCOVER -> scored.mapNotNull { r ->
            val s = stats.signals[r.track.id]
            val liked = r.track.isFavorite || (s?.completedRaw ?: 0) >= 2
            val last = s?.lastPlayedAt
            val days = when {
                last != null -> ((nowMs - last) / DAY_MS).toInt()
                r.track.isFavorite -> 60
                else -> null
            }
            if (liked && days != null && days >= config.rediscoverAfterDays) {
                r.copy(reasons = listOf(Reason(ReasonType.NOT_PLAYED_LONG, number = days))) to days
            } else null
        }.sortedByDescending { it.first.score + it.second / 30.0 }.map { it.first }.take(limit)

        MixType.CHILL -> scored.filter { isCalm(it.track) }.take(limit)

        MixType.WORKOUT -> scored.filter { r ->
            val t = r.track
            t.moodTag != SAD && t.durationMs >= 90_000 && (t.moodTag == ENERGETIC || (t.energyLevel != null && t.energyLevel >= 0.65f))
        }.sortedByDescending { it.score + tempoBonus(it.track, 110..175) }.take(limit)

        MixType.DRIVING -> scored.filter { r ->
            val t = r.track
            val e = t.energyLevel
            t.moodTag != SAD && t.durationMs >= 150_000 &&
                (t.moodTag == ENERGETIC || t.moodTag == HAPPY || (e != null && e in 0.4f..0.8f)) &&
                (t.bpm == null || t.bpm in 80..150)
        }.take(limit)

        MixType.HAPPY -> scored.filter { r ->
            val t = r.track
            t.moodTag == HAPPY || (t.moodTag != SAD && t.moodTag != CALM && t.energyLevel != null && t.energyLevel >= 0.5f && (t.bpm ?: 100) >= 100)
        }.take(limit)

        MixType.SAD -> scored.filter { it.track.moodTag == SAD }.take(limit)

        MixType.MORNING -> scored.filter { r ->
            val t = r.track
            val e = t.energyLevel
            t.moodTag != SAD && t.durationMs >= 120_000 && e != null && e in 0.35f..0.7f
        }.take(limit)

        MixType.ENERGETIC -> scored.filter { isEnergetic(it.track) }.take(limit)

        MixType.FOCUS -> scored.filter {
            val t = it.track
            val e = t.energyLevel
            e != null && e in 0.15f..0.5f && (t.moodTag == CALM || t.moodTag == NEUTRAL || t.moodTag == null) && t.durationMs >= 120_000
        }.take(limit)

        MixType.NIGHT -> scored.filter { r ->
            val t = r.track
            t.moodTag == CALM || t.moodTag == SAD || (t.energyLevel != null && t.energyLevel < 0.45f)
        }.take(limit)

        MixType.RANDOM_FROM_TASTE -> weightedSample(scored.take(200), limit, seed = nowMs / DAY_MS)
    }

    private fun tempoBonus(t: TrackEntity, range: IntRange): Double = if (t.bpm != null && t.bpm in range) 0.6 else 0.0

    private fun isCalm(t: TrackEntity) = t.moodTag == CALM || (t.energyLevel != null && t.energyLevel < 0.4f)
    private fun isEnergetic(t: TrackEntity) =
        t.moodTag == ENERGETIC || t.moodTag == HAPPY || (t.energyLevel != null && t.energyLevel >= 0.65f)

    /** Weighted sampling without replacement (Efraimidis–Spirakis), seeded so it is reproducible. */
    internal fun weightedSample(candidates: List<Recommendation>, limit: Int, seed: Long): List<Recommendation> {
        if (candidates.isEmpty()) return emptyList()
        val random = Random(seed)
        val floor = candidates.minOf { it.score }
        return candidates
            .map { r ->
                val weight = (r.score - floor) + 0.5
                r to Math.pow(random.nextDouble().coerceAtLeast(1e-9), 1.0 / weight)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
