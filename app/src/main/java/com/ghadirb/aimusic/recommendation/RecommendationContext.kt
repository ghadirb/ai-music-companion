package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.recommendation.candidate.CandidateSourceId
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationWeights
import com.ghadirb.aimusic.recommendation.features.FeatureSet
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.profile.TasteProfile
import com.ghadirb.aimusic.recommendation.session.SessionContext
import com.ghadirb.aimusic.recommendation.similarity.ArtistProfile
import com.ghadirb.aimusic.recommendation.similarity.SimilarityEngine
import kotlin.random.Random

/**
 * Everything one recommendation run needs, shared by candidate generation, ranking, diversity and
 * exploration. Built once per run by [RecommendationPipeline]; sections override [weights], [seeds],
 * [daypart], [energyTarget], [eligible] and [allowedSources] to get their own strategy.
 */
class RecommendationContext(
    val features: FeatureSet,
    val profile: TasteProfile,
    val session: SessionContext,
    val config: RecommendationConfig,
    /** Already normalised weights for this run. */
    val weights: RecommendationWeights,
    val similarity: SimilarityEngine,
    val artistProfiles: Map<String, ArtistProfile>,
    /** Tracks the user likes / just listened to; similarity sources and the similarity signal use them. */
    val seeds: List<TrackFeatureVector>,
    /** TimeBuckets bucket this run optimises for (may differ from "now", e.g. the "tonight" section). */
    val daypart: String,
    val isWeekend: Boolean,
    /** Preferred energy (0..1) for [daypart]: learned if there is enough evidence, otherwise a generic prior. */
    val energyTarget: Double,
    val random: Random,
    val nowMs: Long,
    /** Section-level filter, applied on top of "not interested". */
    val eligible: (TrackFeatureVector) -> Boolean = { true },
    /** null = every candidate source may contribute. */
    val allowedSources: Set<CandidateSourceId>? = null
) {
    val isColdStart: Boolean get() = profile.isEmpty && !features.hasHistory

    fun isEligible(v: TrackFeatureVector): Boolean = !v.notInterested && eligible(v)
}
