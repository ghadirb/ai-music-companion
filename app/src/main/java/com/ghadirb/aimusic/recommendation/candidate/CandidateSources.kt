package com.ghadirb.aimusic.recommendation.candidate

import com.ghadirb.aimusic.recommendation.RecommendationContext
import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.profile.TrackAffinity
import kotlin.math.min

private fun RecommendationContext.pool(): List<TrackFeatureVector> = features.vectors.filter { isEligible(it) }

/** Favourites, most played and high-completion tracks. */
class FavoriteCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        val pool = ctx.pool()
        val out = ArrayList<Candidate>()

        val favorites = pool.filter { it.favorite }
            .sortedWith(compareByDescending<TrackFeatureVector> { TrackAffinity.love(it) }.thenByDescending { it.dateAdded })
            .take(limit)
        val maxLove = favorites.maxOfOrNull { TrackAffinity.love(it) }?.takeIf { it > 0.0 } ?: 1.0
        favorites.mapTo(out) { Candidate(it.trackId, setOf(CandidateSourceId.FAVORITE), (TrackAffinity.love(it) / maxLove).coerceIn(0.1, 1.0)) }

        pool.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(limit)
            .mapTo(out) { Candidate(it.trackId, setOf(CandidateSourceId.MOST_PLAYED), it.popularity) }

        pool.filter { (it.signal?.plays ?: 0) >= 2 && (it.completionRate ?: 0.0) >= 0.7 && (it.skipRate ?: 1.0) <= 0.3 }
            .sortedByDescending { it.signal?.completionScore ?: 0.0 }.take(limit)
            .mapTo(out) { Candidate(it.trackId, setOf(CandidateSourceId.HIGH_COMPLETION), it.signal?.completionScore ?: 0.0) }
        return out
    }
}

/** Tracks similar to the seeds (liked / recently played) and tracks in the user's favourite genres. */
class SimilarTrackCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        if (ctx.seeds.isEmpty()) return emptyList()
        val seedIds = ctx.seeds.mapTo(HashSet()) { it.trackId }
        val sims = HashMap<Long, Pair<Double, Long>>()
        for (v in ctx.pool()) {
            if (v.trackId in seedIds) continue
            var best = 0.0
            var bestSeed = -1L
            for (s in ctx.seeds) {
                val sim = ctx.similarity.trackSimilarity(s, v)
                if (sim > best) { best = sim; bestSeed = s.trackId }
            }
            if (bestSeed >= 0) sims[v.trackId] = best to bestSeed
        }
        val out = ArrayList<Candidate>()
        sims.entries.filter { it.value.first >= MIN_SIMILARITY }.sortedByDescending { it.value.first }.take(limit)
            .mapTo(out) { Candidate(it.key, setOf(CandidateSourceId.SIMILAR_TRACK), it.value.first, it.value.second) }

        val topGenres: Set<String> = ctx.profile.genres.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet()
            .ifEmpty { ctx.seeds.mapNotNull { it.genreId }.toSet() }
        if (topGenres.isNotEmpty()) {
            ctx.pool().filter { it.genreId in topGenres && it.trackId !in seedIds }
                .sortedByDescending { sims[it.trackId]?.first ?: 0.0 }.take(limit)
                .mapTo(out) { Candidate(it.trackId, setOf(CandidateSourceId.SAME_GENRE), sims[it.trackId]?.first ?: 0.2, sims[it.trackId]?.second) }
        }
        return out
    }

    private companion object { const val MIN_SIMILARITY = 0.30 }
}

/** Tracks of artists that resemble the user's favourite artists. */
class SimilarArtistCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        val own: List<String> = ctx.profile.artists.entries.sortedByDescending { it.value }.take(3).map { it.key }
            .ifEmpty { ctx.seeds.mapNotNull { it.artistId }.distinct().take(3) }
        if (own.isEmpty()) return emptyList()
        val ownSet = own.toSet()
        val best = HashMap<String, Double>()
        for (a in own) {
            val pa = ctx.artistProfiles[a] ?: continue
            for ((id, pb) in ctx.artistProfiles) {
                if (id in ownSet) continue
                val s = ctx.similarity.artistSimilarity(pa, pb)
                if (s >= MIN_SIMILARITY && s > (best[id] ?: 0.0)) best[id] = s
            }
        }
        if (best.isEmpty()) return emptyList()
        val chosen = best.entries.sortedByDescending { it.value }.take(5).associate { it.key to it.value }
        return ctx.pool().filter { it.artistId in chosen }
            .sortedWith(compareBy<TrackFeatureVector> { it.playCount }.thenByDescending { chosen[it.artistId] ?: 0.0 })
            .take(limit)
            .map { Candidate(it.trackId, setOf(CandidateSourceId.SIMILAR_ARTIST), chosen[it.artistId] ?: 0.0) }
    }

    private companion object { const val MIN_SIMILARITY = 0.35 }
}

/** Recent personal preference (what was completed lately, same artists) and rediscovery of old favourites. */
class RecentCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        val pool = ctx.pool()
        val out = ArrayList<Candidate>()
        val recent = pool.filter { (it.signal?.recentCompletions ?: 0) > 0 }
            .sortedByDescending { it.signal?.recentCompletions ?: 0 }
        recent.take(limit).mapTo(out) {
            Candidate(it.trackId, setOf(CandidateSourceId.RECENT_PREFERENCE), min(1.0, (it.signal?.recentCompletions ?: 0) / 4.0))
        }
        val recentIds = recent.mapTo(HashSet()) { it.trackId }
        val recentArtists = recent.take(5).mapNotNull { it.artistId }.toSet()
        if (recentArtists.isNotEmpty()) {
            pool.filter { it.artistId in recentArtists && it.trackId !in recentIds }.take(limit)
                .mapTo(out) { Candidate(it.trackId, setOf(CandidateSourceId.RECENT_PREFERENCE), 0.4) }
        }
        pool.filter { v ->
            val last = v.lastPlayedAt
            last != null && TrackAffinity.love(v) > 0.0 && ctx.nowMs - last >= REDISCOVER_AFTER_MS
        }.sortedBy { it.lastPlayedAt }.take(limit).mapTo(out) {
            val days = (ctx.nowMs - (it.lastPlayedAt ?: ctx.nowMs)) / DAY_MS.toDouble()
            Candidate(it.trackId, setOf(CandidateSourceId.REDISCOVERY), min(1.0, days / 120.0))
        }
        return out
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val REDISCOVER_AFTER_MS = 30L * DAY_MS
    }
}

/** Tracks whose energy / own listening times fit the daypart this run optimises for. */
class ContextCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        val scored = ctx.pool().mapNotNull { v ->
            val own = v.daypartAffinity(ctx.daypart)
            val fit = v.energy?.let { FeatureNormalizer.closeness(it, ctx.energyTarget, 0.35) }
            val score = when {
                fit != null && own != null -> 0.7 * fit + 0.3 * own
                fit != null -> fit
                own != null && (v.signal?.completions ?: 0) >= 2 -> own
                else -> null
            }
            if (score == null || score < MIN_FIT) null else v to score
        }
        return scored.sortedByDescending { it.second }.take(limit)
            .map { Candidate(it.first.trackId, setOf(CandidateSourceId.CONTEXT), it.second) }
    }

    private companion object { const val MIN_FIT = 0.5 }
}

/** Never-played tracks: close to the taste (similar discovery) plus controlled random exploration. */
class DiscoveryCandidateSource : CandidateSource {
    override fun generate(ctx: RecommendationContext, limit: Int): List<Candidate> {
        val unplayed = ctx.pool().filter { it.isUnplayed && !it.favorite }
        if (unplayed.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()

        val similar: List<Pair<TrackFeatureVector, Double>> =
            if (ctx.seeds.isNotEmpty()) {
                unplayed.map { it to ctx.similarity.maxSimilarity(it, ctx.seeds) }.filter { it.second >= MIN_SIMILARITY }
            } else {
                // Cold start: no taste yet — use audio features fitting the time of day, then metadata completeness.
                unplayed.map { v ->
                    val fit = v.energy?.let { FeatureNormalizer.closeness(it, ctx.energyTarget, 0.4) } ?: 0.3
                    val metadata = (if (v.genre != null) 0.1 else 0.0) + (if (v.mood != null) 0.1 else 0.0)
                    v to (0.8 * fit + metadata)
                }
            }
        val similarSorted = similar.sortedByDescending { it.second }.take(limit)
        similarSorted.mapTo(out) { Candidate(it.first.trackId, setOf(CandidateSourceId.DISCOVERY_SIMILAR), it.second) }

        // Round-robin over artists so one prolific artist cannot fill the whole exploration pool.
        val taken = similarSorted.mapTo(HashSet()) { it.first.trackId }
        val groups = unplayed.filter { it.trackId !in taken }.groupBy { it.artistId ?: "?${it.trackId}" }
            .values.map { it.shuffled(ctx.random).toMutableList() }.shuffled(ctx.random)
        var added = 0
        var i = 0
        while (added < limit && groups.any { it.isNotEmpty() }) {
            val g = groups[i % groups.size]
            if (g.isNotEmpty()) {
                val v = g.removeAt(0)
                out += Candidate(v.trackId, setOf(CandidateSourceId.DISCOVERY_RANDOM), 0.1)
                added++
            }
            i++
        }
        return out
    }

    private companion object { const val MIN_SIMILARITY = 0.25 }
}
