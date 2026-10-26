package com.ghadirb.aimusic.recommendation.config

import kotlin.math.abs

/**
 * Version of the recommendation algorithm. It is stored with every cached section and every
 * recommendation event; bumping it (v3, v4, ML …) invalidates old caches automatically.
 */
const val ALGORITHM_VERSION = "v2"

/**
 * Spec §4 — how a skip is interpreted depending on *when* it happened.
 * Penalties are in 0..1 (1 = strongest negative signal, 0 = neutral).
 */
data class SkipModelConfig(
    /** A skip before this many milliseconds is a strong negative. */
    val strongSkipMaxMs: Long = 10_000L,
    /** From [strongSkipMaxMs] up to this fraction of the track the skip is a medium negative. */
    val mediumSkipMaxFraction: Float = 0.35f,
    /** From this fraction the skip is only a weak negative … */
    val weakSkipMinFraction: Float = 0.70f,
    /** … and from this fraction it is almost neutral. */
    val neutralSkipMinFraction: Float = 0.90f,
    val strongPenalty: Double = 1.0,
    val mediumPenalty: Double = 0.6,
    val weakPenalty: Double = 0.2,
    val neutralPenalty: Double = 0.05,
    /** Fraction below which an unfinished play is logged as SKIPPED_EARLY (otherwise SKIPPED_MIDDLE). */
    val earlySkipMaxFraction: Float = 0.35f,
    /** A play that reaches this fraction (or ends naturally) counts as completed. */
    val completedThreshold: Float = 0.8f
)

/**
 * Spec §6 — recency decay as a piecewise-linear curve over "days ago".
 * Each anchor is (daysAgo, weight). Weights between anchors are interpolated; beyond the last
 * anchor the last weight is used (old interest never disappears completely).
 * Default anchors: today, few days, a week, a month, older.
 */
data class RecencyConfig(
    val anchors: List<Pair<Double, Double>> = listOf(
        0.0 to 1.00,   // today
        1.0 to 0.90,
        3.0 to 0.70,   // few days
        7.0 to 0.50,   // week
        30.0 to 0.20,  // month
        180.0 to 0.05  // older
    )
) {
    init {
        require(anchors.isNotEmpty()) { "RecencyConfig needs at least one anchor" }
        require(anchors.zipWithNext().all { (a, b) -> a.first < b.first }) { "Recency anchors must be sorted by days" }
    }
}

/**
 * Spec §12 — all ranking weights live here (never hard-coded in the ranker).
 * The first eleven values are the baseline from the spec and sum to exactly 1.0.
 * [session], [similarity] and [timeOfDay] are extra signals that the spec lists but gives no
 * baseline for; call [normalized] before use so that the total is always 1.0.
 */
data class RecommendationWeights(
    val taste: Double = 0.25,
    val artist: Double = 0.15,
    val genre: Double = 0.10,
    val mood: Double = 0.10,
    val energy: Double = 0.08,
    val bpm: Double = 0.07,
    val completion: Double = 0.08,
    val favorite: Double = 0.06,
    val recency: Double = 0.05,
    val replay: Double = 0.04,
    val discovery: Double = 0.02,
    // --- extra signals (not part of the spec baseline) ---
    val session: Double = 0.10,
    val similarity: Double = 0.08,
    val timeOfDay: Double = 0.06,
    /** Subtractive penalty strength for skipped tracks. Not part of [total]/[normalized]. */
    val skipPenalty: Double = 0.15
) {
    fun baselineSum(): Double =
        taste + artist + genre + mood + energy + bpm + completion + favorite + recency + replay + discovery

    fun total(): Double = baselineSum() + session + similarity + timeOfDay

    /** Same proportions, scaled so that [total] == 1.0. Returns this unchanged if the total is 0. */
    fun normalized(): RecommendationWeights {
        val t = total()
        if (t <= 0.0 || abs(t - 1.0) < 1e-9) return this
        return copy(
            taste = taste / t, artist = artist / t, genre = genre / t, mood = mood / t, energy = energy / t,
            bpm = bpm / t, completion = completion / t, favorite = favorite / t, recency = recency / t,
            replay = replay / t, discovery = discovery / t, session = session / t,
            similarity = similarity / t, timeOfDay = timeOfDay / t
        )
    }
}

/** Spec §13 — diversity limits for the top of every list. */
data class DiversityConfig(
    val topN: Int = 10,
    val maxPerArtist: Int = 2,
    val maxPerAlbum: Int = 2,
    /** Null = no genre limit. */
    val maxPerGenre: Int? = null
)

/** Spec §14 — exploitation / similar-discovery / exploration split. Shares must sum to 1.0. */
data class ExplorationConfig(
    val exploitation: Double = 0.70,
    val similarDiscovery: Double = 0.20,
    val exploration: Double = 0.10,
    val minExploration: Double = 0.03,
    val maxExploration: Double = 0.30
)

/** Spec §11 — candidate generation sizes. */
data class CandidateConfig(
    /** Max candidates produced by one source. */
    val perSource: Int = 30,
    /** Hard cap after merge (spec: 50..200 on big libraries). */
    val maxTotal: Int = 150,
    /** If sources return fewer than this, the pool is topped up with controlled-random tracks. */
    val minTotal: Int = 30
)

/** Spec §9 — what counts as "the current session". */
data class SessionConfig(
    /** A pause longer than this ends the session. */
    val gapMs: Long = 30L * 60 * 1000,
    val maxAgeMs: Long = 4L * 60 * 60 * 1000,
    val maxEntries: Int = 20,
    /** This many skips in a row triggers fast adaptation. */
    val skipStreakTrigger: Int = 3
)

/** Spec §19 — cache lifetime. */
data class CacheConfig(
    val ttlMs: Long = 6L * 60 * 60 * 1000
)

/** Spec §8 — minimum evidence before learned time-of-day behaviour replaces the generic prior. */
data class ContextConfig(
    val minDaypartSamples: Double = 6.0,
    /** Days treated as weekend (java.util.Calendar.DAY_OF_WEEK values). Default: Thursday + Friday. */
    val weekendDays: Set<Int> = setOf(5, 6),
    /** Generic prior for the preferred energy per daypart (0 calm .. 1 energetic). */
    val priorEnergy: Map<String, Double> = mapOf(
        "morning" to 0.60, "afternoon" to 0.60, "evening" to 0.50, "night" to 0.30
    )
)

/** Everything tunable about the engine in one object. */
data class RecommendationConfig(
    val weights: RecommendationWeights = RecommendationWeights(),
    val skip: SkipModelConfig = SkipModelConfig(),
    val recency: RecencyConfig = RecencyConfig(),
    val diversity: DiversityConfig = DiversityConfig(),
    val exploration: ExplorationConfig = ExplorationConfig(),
    val candidates: CandidateConfig = CandidateConfig(),
    val session: SessionConfig = SessionConfig(),
    val cache: CacheConfig = CacheConfig(),
    val context: ContextConfig = ContextConfig(),
    val algorithmVersion: String = ALGORITHM_VERSION
)
