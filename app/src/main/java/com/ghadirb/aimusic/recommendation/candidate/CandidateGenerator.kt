package com.ghadirb.aimusic.recommendation.candidate

import com.ghadirb.aimusic.recommendation.RecommendationContext
import kotlin.math.max

/** Where a candidate came from (spec §11). Used for trimming, exploration pools and explanations. */
enum class CandidateSourceId {
    FAVORITE, MOST_PLAYED, HIGH_COMPLETION,
    SIMILAR_TRACK, SAME_GENRE, SIMILAR_ARTIST,
    RECENT_PREFERENCE, REDISCOVERY,
    CONTEXT,
    DISCOVERY_SIMILAR, DISCOVERY_RANDOM,
    FALLBACK;

    val isDiscovery: Boolean get() = this == DISCOVERY_SIMILAR || this == DISCOVERY_RANDOM
}

/**
 * A track proposed for ranking. After merging, [sources] holds every source that proposed it,
 * [sourceScore] the best source-local score (0..1) and [seedTrackId] the liked track it is similar to (if any).
 */
data class Candidate(
    val trackId: Long,
    val sources: Set<CandidateSourceId>,
    val sourceScore: Double,
    val seedTrackId: Long? = null
)

interface CandidateSource {
    /** At most [limit] candidates per internal list; must only use data from [ctx]. */
    fun generate(ctx: RecommendationContext, limit: Int): List<Candidate>
}

/**
 * Spec §11 — the library is never ranked as a whole: several cheap sources each propose a few
 * tracks, the proposals are merged (duplicates removed, sources unioned) and capped.
 */
class CandidateGenerator(private val sources: List<CandidateSource> = defaultSources()) {

    fun generate(ctx: RecommendationContext): List<Candidate> {
        val cfg = ctx.config.candidates
        val merged = LinkedHashMap<Long, Candidate>()
        for (source in sources) {
            for (c in source.generate(ctx, cfg.perSource)) {
                val allowed = ctx.allowedSources
                val srcs = if (allowed == null) c.sources else c.sources.filter { it in allowed }.toSet()
                if (srcs.isEmpty()) continue
                val v = ctx.features.byId[c.trackId] ?: continue
                if (!ctx.isEligible(v)) continue
                val prev = merged[c.trackId]
                merged[c.trackId] =
                    if (prev == null) c.copy(sources = srcs)
                    else Candidate(c.trackId, prev.sources + srcs, max(prev.sourceScore, c.sourceScore), prev.seedTrackId ?: c.seedTrackId)
            }
        }

        var result = trim(merged.values.toList(), cfg.maxTotal)

        // Top up (controlled randomness) so a thin history never yields an empty/tiny list.
        val eligible = ctx.features.vectors.filter { ctx.isEligible(it) }
        val wanted = minOf(cfg.minTotal, eligible.size)
        if (result.size < wanted) {
            val have = result.mapTo(HashSet()) { it.trackId }
            val filler = eligible.filter { it.trackId !in have }.shuffled(ctx.random).take(wanted - result.size)
            result = result + filler.map { Candidate(it.trackId, setOf(CandidateSourceId.FALLBACK), 0.0) }
        }
        return result
    }

    /** Keeps the strongest candidates but reserves a quarter of the pool for discovery. */
    private fun trim(all: List<Candidate>, max: Int): List<Candidate> {
        if (all.size <= max) return all
        val (disc, known) = all.partition { c -> c.sources.all { it.isDiscovery } }
        val order = compareByDescending<Candidate> { it.sources.size }.thenByDescending { it.sourceScore }
        val reserve = minOf(disc.size, max / 4)
        val keptKnown = known.sortedWith(order).take(max - reserve)
        val keptDisc = disc.sortedWith(order).take(max - keptKnown.size)
        return keptKnown + keptDisc
    }

    companion object {
        fun defaultSources(): List<CandidateSource> = listOf(
            FavoriteCandidateSource(),
            SimilarTrackCandidateSource(),
            SimilarArtistCandidateSource(),
            RecentCandidateSource(),
            ContextCandidateSource(),
            DiscoveryCandidateSource()
        )
    }
}
