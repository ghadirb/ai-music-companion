package com.ghadirb.aimusic.recommendation.explanation

import com.ghadirb.aimusic.recommendation.Reason
import com.ghadirb.aimusic.recommendation.ReasonType
import com.ghadirb.aimusic.recommendation.RecommendationContext
import com.ghadirb.aimusic.recommendation.candidate.CandidateSourceId
import com.ghadirb.aimusic.recommendation.ranking.ScoreSignal
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack

/**
 * Spec §16 — turns the score breakdown + candidate sources into at most two human-readable reasons.
 * Mapping to the names in the spec:
 * BecauseFavoriteArtist=FAVORITE_ARTIST, BecauseSimilarTrack=SIMILAR_TRACK, BecauseSimilarMood=SIMILAR_MOOD,
 * BecauseNightPreference=TIME_OF_DAY_MATCH(detail="night"), BecauseHighCompletion=HIGH_COMPLETION,
 * BecauseDiscovery=DISCOVERY/EXPLORE, BecauseRecentlyLiked=RECENTLY_LOVED / RECENTLY_FAVORITED.
 * The Persian text is produced by [com.ghadirb.aimusic.recommendation.ReasonText].
 */
class RecommendationExplainer {

    fun explain(ctx: RecommendationContext, s: ScoredTrack, max: Int = 2): List<Reason> {
        val v = s.vector
        val sig = s.signals
        val src = s.candidate.sources
        val found = ArrayList<Pair<Double, Reason>>()

        if (ctx.isColdStart && !v.favorite) {
            found += 0.5 to Reason(ReasonType.COLD_START)
        }
        val artistAff = sig[ScoreSignal.ARTIST] ?: 0.0
        if (v.artist != null && artistAff >= 0.6) found += (0.8 + 0.2 * artistAff) to Reason(ReasonType.FAVORITE_ARTIST, detail = v.artist)

        val seed = s.candidate.seedTrackId?.let { ctx.features.byId[it] }
        if (seed != null && (sig[ScoreSignal.SIMILARITY] ?: 0.0) >= 0.45) {
            found += (0.7 + 0.3 * (sig[ScoreSignal.SIMILARITY] ?: 0.0)) to Reason(ReasonType.SIMILAR_TRACK, detail = seed.title)
        }
        val session = sig[ScoreSignal.SESSION] ?: 0.0
        if (ctx.session.recentFavorited.isNotEmpty() && session >= 0.3) found += 0.85 to Reason(ReasonType.RECENTLY_FAVORITED)
        else if (session >= 0.4) found += (0.6 + 0.2 * session) to Reason(ReasonType.SESSION_MATCH)

        val slice = ctx.profile.dayparts[ctx.daypart]
        if ((sig[ScoreSignal.TIME_OF_DAY] ?: 0.0) >= 0.75 && slice != null && slice.weight >= ctx.config.context.minDaypartSamples / 2) {
            found += 0.75 to Reason(ReasonType.TIME_OF_DAY_MATCH, detail = ctx.daypart)
        }
        if (v.mood != null && ctx.profile.moods.isNotEmpty() && (sig[ScoreSignal.MOOD] ?: 0.0) >= 0.7) {
            found += 0.65 to Reason(ReasonType.SIMILAR_MOOD, detail = v.mood)
        }
        val plays = v.signal?.plays ?: 0
        if (plays >= 2 && (v.completionRate ?: 0.0) >= 0.8) found += 0.7 to Reason(ReasonType.HIGH_COMPLETION)
        if (v.favorite) found += 0.9 to Reason(ReasonType.FAVORITE)
        val recent = v.signal?.recentCompletions ?: 0
        if (recent >= 2) found += (0.6 + 0.05 * recent) to Reason(ReasonType.RECENTLY_LOVED, number = recent)
        if (CandidateSourceId.REDISCOVERY in src && v.lastPlayedAt != null) {
            val days = ((ctx.nowMs - v.lastPlayedAt) / DAY_MS).toInt()
            found += 0.55 to Reason(ReasonType.NOT_PLAYED_LONG, number = days)
        }
        if (CandidateSourceId.SIMILAR_ARTIST in src) found += 0.5 to Reason(ReasonType.SIMILAR_ARTIST)
        if (v.genre != null && (sig[ScoreSignal.GENRE] ?: 0.0) >= 0.6) found += 0.45 to Reason(ReasonType.GENRE_MATCH, detail = v.genre)
        if (v.isUnplayed) {
            val randomOnly = src.isNotEmpty() && src.all { it == CandidateSourceId.DISCOVERY_RANDOM || it == CandidateSourceId.FALLBACK }
            found += 0.4 to Reason(if (randomOnly) ReasonType.DISCOVERY else ReasonType.EXPLORE)
            if (ctx.nowMs - v.dateAdded in 0..NEW_MS) found += 0.35 to Reason(ReasonType.NEW_ADDITION)
        }

        return found.sortedByDescending { it.first }.map { it.second }.distinctBy { it.type }.take(max)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val NEW_MS = 14L * DAY_MS
    }
}
