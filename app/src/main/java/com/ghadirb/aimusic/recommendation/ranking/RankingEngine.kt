package com.ghadirb.aimusic.recommendation.ranking

import com.ghadirb.aimusic.recommendation.RecommendationContext
import com.ghadirb.aimusic.recommendation.candidate.Candidate
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import java.util.EnumMap

/** The individual signals a score is built from (spec §12). */
enum class ScoreSignal {
    TASTE, ARTIST, GENRE, MOOD, ENERGY, BPM, COMPLETION, FAVORITE, RECENCY, REPLAY,
    DISCOVERY, SESSION, SIMILARITY, TIME_OF_DAY, SKIP_PENALTY
}

/** A candidate with its final [score], the raw [signals] (0..1, session -1..1) and the weighted [contributions]. */
class ScoredTrack(
    val vector: TrackFeatureVector,
    val candidate: Candidate,
    val score: Double,
    val signals: Map<ScoreSignal, Double>,
    val contributions: Map<ScoreSignal, Double>
) {
    val trackId: Long get() = vector.trackId
}

/**
 * Spec §24 — ML-ready seam. Ranking only talks to this interface; [RuleBasedScoringStrategy] is the
 * current implementation. A future `MLScoringStrategy` (on-device model, output P(Like) from the same
 * signals) or a `HybridScoringStrategy` can be dropped in without touching candidates, diversity or UI.
 * Deliberately no online/ML dependency is added at this stage.
 */
interface ScoringStrategy {
    val name: String
    fun score(ctx: RecommendationContext, candidate: Candidate, vector: TrackFeatureVector): ScoredTrack
}

/** Transparent weighted sum of signals; weights come from [RecommendationContext.weights] (never hard-coded). */
class RuleBasedScoringStrategy(
    private val features: FeatureScorer = FeatureScorer(),
    private val context: ContextScorer = ContextScorer(),
    private val recency: RecencyScorer = RecencyScorer(),
    private val similarity: SimilarityScorer = SimilarityScorer()
) : ScoringStrategy {

    override val name: String = "rule-based"

    override fun score(ctx: RecommendationContext, candidate: Candidate, vector: TrackFeatureVector): ScoredTrack {
        val w = ctx.weights
        val signals = EnumMap<ScoreSignal, Double>(ScoreSignal::class.java)
        signals[ScoreSignal.TASTE] = features.taste(ctx, vector)
        signals[ScoreSignal.ARTIST] = features.artist(ctx, vector)
        signals[ScoreSignal.GENRE] = features.genre(ctx, vector)
        signals[ScoreSignal.MOOD] = features.mood(ctx, vector)
        signals[ScoreSignal.ENERGY] = features.energy(ctx, vector)
        signals[ScoreSignal.BPM] = features.bpm(ctx, vector)
        signals[ScoreSignal.COMPLETION] = features.completion(vector)
        signals[ScoreSignal.FAVORITE] = features.favorite(vector)
        signals[ScoreSignal.REPLAY] = features.replay(vector)
        signals[ScoreSignal.DISCOVERY] = features.discovery(ctx, vector)
        signals[ScoreSignal.RECENCY] = recency.recency(ctx, vector)
        signals[ScoreSignal.SIMILARITY] = similarity.similarity(ctx, vector)
        signals[ScoreSignal.TIME_OF_DAY] = context.timeOfDay(ctx, vector)
        signals[ScoreSignal.SESSION] = context.session(ctx, vector)
        signals[ScoreSignal.SKIP_PENALTY] = features.skip(vector)

        val contributions = EnumMap<ScoreSignal, Double>(ScoreSignal::class.java)
        contributions[ScoreSignal.TASTE] = w.taste * signals.getValue(ScoreSignal.TASTE)
        contributions[ScoreSignal.ARTIST] = w.artist * signals.getValue(ScoreSignal.ARTIST)
        contributions[ScoreSignal.GENRE] = w.genre * signals.getValue(ScoreSignal.GENRE)
        contributions[ScoreSignal.MOOD] = w.mood * signals.getValue(ScoreSignal.MOOD)
        contributions[ScoreSignal.ENERGY] = w.energy * signals.getValue(ScoreSignal.ENERGY)
        contributions[ScoreSignal.BPM] = w.bpm * signals.getValue(ScoreSignal.BPM)
        contributions[ScoreSignal.COMPLETION] = w.completion * signals.getValue(ScoreSignal.COMPLETION)
        contributions[ScoreSignal.FAVORITE] = w.favorite * signals.getValue(ScoreSignal.FAVORITE)
        contributions[ScoreSignal.REPLAY] = w.replay * signals.getValue(ScoreSignal.REPLAY)
        contributions[ScoreSignal.DISCOVERY] = w.discovery * signals.getValue(ScoreSignal.DISCOVERY)
        contributions[ScoreSignal.RECENCY] = w.recency * signals.getValue(ScoreSignal.RECENCY)
        contributions[ScoreSignal.SIMILARITY] = w.similarity * signals.getValue(ScoreSignal.SIMILARITY)
        contributions[ScoreSignal.TIME_OF_DAY] = w.timeOfDay * signals.getValue(ScoreSignal.TIME_OF_DAY)
        // Session weight grows while the user keeps skipping, so the list adapts quickly.
        contributions[ScoreSignal.SESSION] = w.session * ctx.session.adaptationFactor * signals.getValue(ScoreSignal.SESSION)
        contributions[ScoreSignal.SKIP_PENALTY] = -w.skipPenalty * signals.getValue(ScoreSignal.SKIP_PENALTY)

        return ScoredTrack(vector, candidate, contributions.values.sum(), signals, contributions)
    }
}

/** Scores every candidate with the active [ScoringStrategy] and sorts best-first (deterministic tie-breaks). */
class RankingEngine(private val strategy: ScoringStrategy = RuleBasedScoringStrategy()) {

    fun rank(ctx: RecommendationContext, candidates: List<Candidate>): List<ScoredTrack> {
        val scored = ArrayList<ScoredTrack>(candidates.size)
        for (c in candidates) {
            val v = ctx.features.byId[c.trackId] ?: continue
            scored += strategy.score(ctx, c, v)
        }
        return scored.sortedWith(
            compareByDescending<ScoredTrack> { it.score }
                .thenByDescending { it.vector.dateAdded }
                .thenBy { it.vector.title }
                .thenBy { it.trackId }
        )
    }
}
