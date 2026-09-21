package com.ghadirb.aimusic.radio

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.ListeningStats
import com.ghadirb.aimusic.recommendation.Reason
import com.ghadirb.aimusic.recommendation.ReasonType
import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.RecommendationTuning
import com.ghadirb.aimusic.recommendation.ScoringConfig
import com.ghadirb.aimusic.recommendation.TrackSimilarity
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/** Every coefficient of the radio scoring in one configurable place (nothing is hard-coded in the engine). */
data class RadioConfig(
    val similarityWeight: Double = 3.0,
    val artistAffinityWeight: Double = 1.0,
    val genreAffinityWeight: Double = 0.8,
    val moodWeight: Double = 1.0,
    val bpmWeight: Double = 0.8,
    val energyContinuityWeight: Double = 1.5,
    val favoriteBoost: Double = 0.8,
    val discoveryBoost: Double = 0.8,
    val recentPlayPenalty: Double = 2.5,
    val recentWindowHours: Int = 6,
    val skipPenalty: Double = 0.8,
    val skipCap: Double = 4.0,
    val repetitionPenalty: Double = 2.0,
    /** No track of the same artist within this many previous picks (relaxed when candidates run out). */
    val artistCooldown: Int = 3,
    /** Chance (0..1) that a pick is a "discovery" (never/rarely played) instead of the top-scored track. */
    val explorationRate: Double = 0.15,
    val minSimilarity: Double = 0.2,
    /** Tracks with at least this much (decayed) skip weight are excluded until relaxation. */
    val maxSkipWeight: Double = 3.0,
    val batchSize: Int = 10,
    /** Generate the next batch when this few upcoming tracks remain in the queue. */
    val prefetchThreshold: Int = 3,
    val scoring: ScoringConfig = ScoringConfig()
) {
    companion object {
        fun forTuning(tuning: RecommendationTuning): RadioConfig = when (tuning) {
            RecommendationTuning.FAMILIAR -> RadioConfig(explorationRate = 0.05, discoveryBoost = 0.4, scoring = tuning.config())
            RecommendationTuning.BALANCED -> RadioConfig(scoring = tuning.config())
            RecommendationTuning.EXPLORE -> RadioConfig(explorationRate = 0.35, discoveryBoost = 1.3, scoring = tuning.config())
        }
    }
}

/**
 * Smart Radio: picks the next tracks for an endless queue from the user's own library.
 *
 *   score = similarity·w + artistAffinity·w + genreAffinity·w + moodCompatibility·w + bpmCompatibility·w
 *         + energyContinuity·w + favoriteBoost + discoveryBoost
 *         − recentPlayPenalty − skipPenalty − repetitionPenalty
 *
 * Deterministic for a given seed. If too few candidates pass the filters, the constraints are relaxed in steps
 * (lower similarity → drop artist/skip limits → allow anything not already queued).
 * Pure Kotlin: the caller (PlayerViewModel) only appends the result to the Media3 queue.
 */
object RadioEngine {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SIMILARITY_MAX = 12.0
    private const val UNKNOWN_ARTIST = "Unknown artist"

    private data class Level(val minSimilarity: Double, val artistCooldown: Int, val maxSkipWeight: Double)

    fun nextBatch(
        seeds: List<TrackEntity>,
        alreadyQueued: List<Long>,
        library: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        count: Int = RadioConfig().batchSize,
        config: RadioConfig = RadioConfig(),
        random: Random = Random(nowMs / 60_000L),
        zone: TimeZone = TimeZone.getDefault()
    ): List<Recommendation> {
        if (seeds.isEmpty() || library.isEmpty() || count <= 0) return emptyList()
        val byId = library.associateBy { it.id }
        val stats = ListeningStats.build(library, history, nowMs, config.scoring, zone)
        val seedIds = seeds.map { it.id }.toSet()
        val used = LinkedHashSet<Long>().apply { addAll(alreadyQueued); addAll(seedIds) }
        val sequence = ArrayList<TrackEntity>() // recent order, oldest first: queued tail + picks
        alreadyQueued.takeLast(8).mapNotNullTo(sequence) { byId[it] }
        if (sequence.isEmpty()) sequence.addAll(seeds.takeLast(1))

        val seedMood = seeds.mapNotNull { it.moodTag }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val seedBpm = seeds.mapNotNull { it.bpm }.takeIf { it.isNotEmpty() }?.average()
        val levels = listOf(
            Level(config.minSimilarity, config.artistCooldown, config.maxSkipWeight),
            Level(config.minSimilarity / 2, config.artistCooldown, config.maxSkipWeight),
            Level(config.minSimilarity / 2, 0, config.maxSkipWeight + 2),
            Level(0.0, 0, Double.MAX_VALUE)
        )

        val picked = ArrayList<Recommendation>()
        while (picked.size < count) {
            val last = sequence.lastOrNull()
            var chosen: Recommendation? = null
            for (level in levels) {
                val recentArtists = sequence.takeLast(level.artistCooldown).map { it.artist }.filter { it != UNKNOWN_ARTIST }.toSet()
                val scored = ArrayList<Recommendation>()
                for (track in library) {
                    if (track.id in used) continue
                    if (level.artistCooldown > 0 && track.artist in recentArtists) continue
                    val signals = stats.signals[track.id]
                    if ((signals?.skipsDecayed ?: 0.0) >= level.maxSkipWeight) continue
                    val similarity = similarityToSeeds(seeds, track)
                    if (similarity < level.minSimilarity) continue
                    scored += score(track, similarity, seedMood, seedBpm, last, sequence, stats, config, nowMs)
                }
                if (scored.isEmpty()) continue
                scored.sortByDescending { it.score }
                chosen = pickWithExploration(scored, stats, config, random)
                break
            }
            val next = chosen ?: break
            picked += next
            used += next.track.id
            sequence += next.track
        }
        return picked
    }

    private fun similarityToSeeds(seeds: List<TrackEntity>, candidate: TrackEntity): Double {
        val top = seeds.map { TrackSimilarity.score(it, candidate) }.sortedDescending().take(3)
        return (top.average() / SIMILARITY_MAX).coerceIn(0.0, 1.0)
    }

    private fun score(
        track: TrackEntity,
        similarity: Double,
        seedMood: String?,
        seedBpm: Double?,
        last: TrackEntity?,
        sequence: List<TrackEntity>,
        stats: ListeningStats,
        c: RadioConfig,
        nowMs: Long
    ): Recommendation {
        val parts = ArrayList<Pair<Double, Reason>>()
        var total = similarity * c.similarityWeight
        if (similarity >= 0.45) parts += (similarity * c.similarityWeight) to Reason(ReasonType.SIMILAR_TO_RECENT)

        val artistAff = stats.artistAffinity[track.artist] ?: 0.0
        total += artistAff * c.artistAffinityWeight
        if (artistAff >= 0.4) parts += (artistAff * c.artistAffinityWeight + 0.5) to Reason(ReasonType.FAVORITE_ARTIST, detail = track.artist)
        val genreAff = track.genre?.let { stats.genreAffinity[it] } ?: 0.0
        total += genreAff * c.genreAffinityWeight
        total += moodCompatibility(seedMood, track.moodTag) * c.moodWeight
        total += bpmCompatibility(seedBpm, track.bpm) * c.bpmWeight
        total += energyContinuity(last?.energyLevel, track.energyLevel) * c.energyContinuityWeight

        if (track.isFavorite) { total += c.favoriteBoost; parts += (c.favoriteBoost + 0.3) to Reason(ReasonType.FAVORITE) }

        val signals = stats.signals[track.id]
        if (signals == null || signals.completedRaw == 0) {
            total += c.discoveryBoost
            parts += c.discoveryBoost to Reason(ReasonType.EXPLORE)
        }
        signals?.lastPlayedAt?.let { playedAt ->
            val ageHours = (nowMs - playedAt) / (60.0 * 60 * 1000)
            if (ageHours in 0.0..c.recentWindowHours.toDouble()) total -= c.recentPlayPenalty * (1.0 - ageHours / c.recentWindowHours)
        }
        signals?.let { total -= min(c.skipCap, it.skipsDecayed) * c.skipPenalty }

        val window = sequence.takeLast(3)
        val sameGenre = window.count { it.genre != null && it.genre == track.genre }
        val sameAlbum = window.count { it.album == track.album && track.album != "Unknown album" }
        total -= c.repetitionPenalty * (0.25 * sameGenre + 0.5 * sameAlbum)

        return Recommendation(track, total, parts.sortedByDescending { it.first }.map { it.second }.distinctBy { it.type }.take(2))
    }

    /** With probability [RadioConfig.explorationRate] choose a decent never/rarely-played track instead of the top one. */
    private fun pickWithExploration(sorted: List<Recommendation>, stats: ListeningStats, c: RadioConfig, random: Random): Recommendation {
        if (random.nextDouble() >= c.explorationRate) return sorted.first()
        val pool = sorted.take(25).filter { (stats.signals[it.track.id]?.completedRaw ?: 0) <= 1 }.take(8)
        if (pool.isEmpty()) return sorted.first()
        val floor = pool.minOf { it.score }
        val weights = pool.map { (it.score - floor) + 0.5 }
        var roll = random.nextDouble() * weights.sum()
        for (i in pool.indices) { roll -= weights[i]; if (roll <= 0) return pool[i] }
        return pool.last()
    }

    internal fun moodCompatibility(a: String?, b: String?): Double = when {
        a == null || b == null -> 0.5
        a == b -> 1.0
        setOf(a, b) in ADJACENT_MOODS -> 0.6
        else -> 0.0
    }

    private val ADJACENT_MOODS = setOf(
        setOf("calm", "neutral"), setOf("energetic", "happy"), setOf("neutral", "happy"), setOf("calm", "sad")
    )

    internal fun bpmCompatibility(seed: Double?, candidate: Int?): Double =
        if (seed == null || candidate == null) 0.5 else (1.0 - abs(candidate - seed) / 60.0).coerceIn(0.0, 1.0)

    /** Rewards small energy steps so the mood flows instead of jumping. */
    internal fun energyContinuity(last: Float?, candidate: Float?): Double =
        if (last == null || candidate == null) 0.5 else (1.0 - abs(candidate - last) * 2.5).coerceIn(0.0, 1.0)
}
