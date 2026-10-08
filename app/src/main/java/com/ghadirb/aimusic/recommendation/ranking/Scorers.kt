package com.ghadirb.aimusic.recommendation.ranking

import com.ghadirb.aimusic.recommendation.RecommendationContext
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import kotlin.math.max
import kotlin.math.min

/** Value used when a comparison is impossible (missing metadata / no taste yet): neither a reward nor a penalty. */
const val NEUTRAL = 0.5

/** Spec §12 — Taste/Feature scoring: how well a track fits the learned multi-dimensional profile + its own behaviour. */
class FeatureScorer {

    fun artist(ctx: RecommendationContext, v: TrackFeatureVector): Double = ctx.profile.artistAffinity(v)

    fun genre(ctx: RecommendationContext, v: TrackFeatureVector): Double = ctx.profile.genreAffinity(v)

    fun mood(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        if (v.mood == null || ctx.profile.moods.isEmpty()) return NEUTRAL
        return moodFit(ctx.profile.moods, v.mood)
    }

    fun energy(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        val g = ctx.profile.energy ?: return NEUTRAL
        val e = v.energy ?: return NEUTRAL
        return g.match(e, MIN_ENERGY_SPREAD)
    }

    fun bpm(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        val g = ctx.profile.bpm ?: return NEUTRAL
        val b = v.bpm ?: return NEUTRAL
        return g.match(b.toDouble(), MIN_BPM_SPREAD)
    }

    /** Aggregate of every taste dimension that has data on both sides. */
    fun taste(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        val p = ctx.profile
        if (p.isEmpty) return NEUTRAL
        var sum = 0.0
        var used = 0.0
        fun add(w: Double, value: Double) { sum += w * value; used += w }
        if (v.artistId != null && p.artists.isNotEmpty()) add(0.25, p.artistAffinity(v))
        if (v.genreId != null && p.genres.isNotEmpty()) add(0.20, p.genreAffinity(v))
        if (v.mood != null && p.moods.isNotEmpty()) add(0.15, moodFit(p.moods, v.mood))
        if (v.energy != null && p.energy != null) add(0.12, p.energy.match(v.energy, MIN_ENERGY_SPREAD))
        if (v.bpm != null && p.bpm != null) add(0.08, p.bpm.match(v.bpm.toDouble(), MIN_BPM_SPREAD))
        if (p.durationMin != null) add(0.05, p.durationMin.match(v.durationMs / 60_000.0, 1.0))
        if (v.language != null && p.languages.isNotEmpty()) add(0.10, p.languages[v.language] ?: 0.0)
        val decade = FeatureNormalizer.decade(v.year)
        if (decade != null && p.eras.isNotEmpty()) add(0.05, p.eras[decade] ?: 0.0)
        return if (used <= 0.0) NEUTRAL else sum / used
    }

    /** Smoothed completion rate; unplayed tracks are neutral so they are not punished for being new. */
    fun completion(v: TrackFeatureVector): Double = v.signal?.completionScore ?: NEUTRAL

    fun favorite(v: TrackFeatureVector): Double = if (v.favorite) 1.0 else 0.0

    fun replay(v: TrackFeatureVector): Double = v.signal?.replayScore ?: 0.0

    /** Severity of past skips of this track (subtracted by the strategy). */
    fun skip(v: TrackFeatureVector): Double = v.signal?.negativeScore ?: 0.0

    /** Novel-but-plausible: unplayed tracks that still fit the taste. */
    fun discovery(ctx: RecommendationContext, v: TrackFeatureVector): Double =
        if (v.isUnplayed) 0.5 + 0.5 * taste(ctx, v) else 0.0

    companion object {
        const val MIN_ENERGY_SPREAD = 0.15
        const val MIN_BPM_SPREAD = 15.0

        /** Best (affinity × mood similarity) over a mood distribution, scaled so its strongest mood = 1. */
        fun moodFit(dist: Map<String, Double>, mood: String): Double {
            val top = dist.values.maxOrNull() ?: return NEUTRAL
            if (top <= 0.0) return NEUTRAL
            val key = FeatureNormalizer.key(mood)
            var best = 0.0
            for ((m, w) in dist) {
                val sim = if (m == key) 1.0 else (FeatureNormalizer.moodSimilarity(m, key) ?: 0.0)
                best = max(best, (w / top) * sim)
            }
            return best
        }
    }
}

/** Time-of-day, weekday/weekend and live-session fit. */
class ContextScorer {

    fun timeOfDay(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        var sum = 0.0
        var used = 0.0
        fun add(w: Double, value: Double) { sum += w * value; used += w }
        v.energy?.let { add(0.50, FeatureNormalizer.closeness(it, ctx.energyTarget, 0.5)) }
        v.daypartAffinity(ctx.daypart)?.let { add(0.30, it) }
        val slice = ctx.profile.dayparts[ctx.daypart]
        if (slice != null && slice.moods.isNotEmpty() && v.mood != null) add(0.20, FeatureScorer.moodFit(slice.moods, v.mood))
        val dayType = if (ctx.isWeekend) v.weekendAffinity else v.weekdayAffinity
        dayType?.let { add(0.15, it) }
        return if (used <= 0.0) NEUTRAL else sum / used
    }

    /**
     * -1..1. Negative when the track resembles what was just skipped (or was just played/skipped itself),
     * positive when it continues the pattern of what the user is listening to right now.
     */
    fun session(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        val s = ctx.session
        if (!s.isActive) return 0.0
        if (v.trackId in s.recentlySkipped) return -1.0
        if (v.trackId in s.recentlyPlayed) return -0.5

        var pos = 0.0
        var posUsed = 0
        fun addPos(x: Double) { pos += x; posUsed++ }
        s.energyPattern?.let { e -> v.energy?.let { addPos(FeatureNormalizer.closeness(it, e, 0.4)) } }
        s.bpmPattern?.let { b -> v.bpm?.let { addPos(FeatureNormalizer.closeness(it.toDouble(), b, 40.0)) } }
        if (s.moodPattern.isNotEmpty() && v.mood != null) addPos(FeatureScorer.moodFit(s.moodPattern, v.mood))
        val fit = if (posUsed > 0) pos / posUsed else 0.0

        var rej = 0.0
        var rejUsed = 0
        fun addRej(x: Double) { rej += x; rejUsed++ }
        s.skippedEnergyPattern?.let { e -> v.energy?.let { addRej(FeatureNormalizer.closeness(it, e, 0.3)) } }
        s.skippedBpmPattern?.let { b -> v.bpm?.let { addRej(FeatureNormalizer.closeness(it.toDouble(), b, 30.0)) } }
        if (s.skippedMoodPattern.isNotEmpty() && v.mood != null) addRej(FeatureScorer.moodFit(s.skippedMoodPattern, v.mood))
        val rejection = if (rejUsed > 0) rej / rejUsed else 0.0

        var out = fit * (1.0 - 0.5 * s.skipPressure) - rejection * (0.4 + 0.8 * s.skipPressure)
        if (s.recentFavorited.isNotEmpty() && v.trackId !in s.recentFavorited) {
            val favSeeds = s.recentFavorited.mapNotNull { ctx.features.byId[it] }
            if (ctx.similarity.maxSimilarity(v, favSeeds) >= 0.5) out += 0.3
        }
        return out.coerceIn(-1.0, 1.0)
    }
}

/** Spec §6 — how current the user's interest in a track is. */
class RecencyScorer {

    fun recency(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        val s = v.signal
        var score = 0.0
        if (s != null) score = min(1.0, s.decayedCompletions / 3.0)

        val last = v.lastPlayedAt
        if (last != null) {
            val age = max(0L, ctx.nowMs - last)
            // Played within the last hours: do not serve it again right away.
            if (age < REPEAT_GUARD_MS) score *= 0.3
            // A liked track that has been resting for a long time deserves a comeback.
            else if (age >= REDISCOVER_AFTER_MS && (v.favorite || (s?.completionScore ?: 0.0) >= 0.7)) {
                score = max(score, 0.3 + 0.4 * min(1.0, age / (120.0 * DAY_MS)))
            }
        } else if (ctx.nowMs - v.dateAdded in 0..NEW_TRACK_MS) {
            score = max(score, 0.4) // fresh addition the user has not heard yet
        }
        return score.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val REPEAT_GUARD_MS = 3L * 60 * 60 * 1000
        const val REDISCOVER_AFTER_MS = 30L * DAY_MS
        const val NEW_TRACK_MS = 14L * DAY_MS
    }
}

/** Similarity to the run's seed tracks (0 when there are no seeds). */
class SimilarityScorer {
    fun similarity(ctx: RecommendationContext, v: TrackFeatureVector): Double {
        var best = 0.0
        for (seed in ctx.seeds) {
            if (seed.trackId == v.trackId) continue
            val s = ctx.similarity.trackSimilarity(seed, v)
            if (s > best) best = s
        }
        return best
    }
}

internal fun daypartLabel(bucket: String): String = when (bucket) {
    TimeBuckets.MORNING -> "صبح"
    TimeBuckets.AFTERNOON -> "ظهر"
    TimeBuckets.EVENING -> "عصر"
    else -> "شب"
}
