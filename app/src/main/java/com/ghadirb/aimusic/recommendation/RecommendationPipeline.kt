package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.candidate.CandidateGenerator
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.diversity.DiversityReRanker
import com.ghadirb.aimusic.recommendation.explanation.RecommendationExplainer
import com.ghadirb.aimusic.recommendation.exploration.DiscoveryOutcomes
import com.ghadirb.aimusic.recommendation.exploration.ExplorationManager
import com.ghadirb.aimusic.recommendation.features.FeatureExtractor
import com.ghadirb.aimusic.recommendation.features.FeatureSet
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.profile.TasteProfile
import com.ghadirb.aimusic.recommendation.profile.TasteProfileCalculator
import com.ghadirb.aimusic.recommendation.profile.TrackAffinity
import com.ghadirb.aimusic.recommendation.ranking.RankingEngine
import com.ghadirb.aimusic.recommendation.ranking.RuleBasedScoringStrategy
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack
import com.ghadirb.aimusic.recommendation.ranking.ScoringStrategy
import com.ghadirb.aimusic.recommendation.section.RecommendationSection
import com.ghadirb.aimusic.recommendation.section.SectionStrategies
import com.ghadirb.aimusic.recommendation.section.SectionType
import com.ghadirb.aimusic.recommendation.section.SeedMode
import com.ghadirb.aimusic.recommendation.session.BehaviorEvent
import com.ghadirb.aimusic.recommendation.session.SessionAnalyzer
import com.ghadirb.aimusic.recommendation.session.SessionContext
import com.ghadirb.aimusic.recommendation.signals.SkipModel
import com.ghadirb.aimusic.recommendation.similarity.ArtistProfile
import com.ghadirb.aimusic.recommendation.similarity.SimilarityEngine
import java.util.TimeZone
import kotlin.random.Random

/** Raw, already-loaded data of one run. The pipeline itself never touches Room or Android. */
class PipelineInput(
    val tracks: List<TrackEntity>,
    val history: List<ListeningHistoryEntity>,
    val behaviorEvents: List<BehaviorEvent> = emptyList(),
    /** Profile saved by the background worker; used when fresh, otherwise rebuilt on the fly. */
    val storedProfile: TasteProfile? = null,
    val nowMs: Long = System.currentTimeMillis(),
    val zone: TimeZone = TimeZone.getDefault(),
    /** Same seed ⇒ same list. By default it changes hourly so exploration slowly rotates. */
    val randomSeed: Long = nowMs / HOUR_MS
) {
    private companion object { const val HOUR_MS = 60L * 60 * 1000 }
}

/** Everything that is shared by all sections of one run — computed once. */
class PreparedRun(
    val input: PipelineInput,
    val features: FeatureSet,
    val profile: TasteProfile,
    val session: SessionContext,
    val similarity: SimilarityEngine,
    val artistProfiles: Map<String, ArtistProfile>,
    val outcomes: DiscoveryOutcomes,
    val tracksById: Map<Long, TrackEntity>,
    /** Tracks the user loves (best first) — seeds of "because you like …". */
    val lovedSeeds: List<TrackFeatureVector>,
    /** What was listened to most recently — seeds of "similar to recent". */
    val recentSeeds: List<TrackFeatureVector>
)

/** A generated section plus the ranked picks behind it (needed for cache and event logging). */
class SectionResult(val section: RecommendationSection, val picks: List<ScoredTrack>)

/**
 * Spec §1/§30 — wires the whole chain for one run:
 * features → profile + session → candidate generation → ranking → diversity → exploration → explanation.
 * Each section gets its own [com.ghadirb.aimusic.recommendation.section.SectionPlan]. Pure and
 * deterministic for a given [PipelineInput]; callers run it on Dispatchers.Default.
 */
class RecommendationPipeline(
    val config: RecommendationConfig = RecommendationConfig(),
    strategy: ScoringStrategy = RuleBasedScoringStrategy()
) {
    private val extractor = FeatureExtractor(config)
    private val profileCalculator = TasteProfileCalculator(config)
    private val sessionAnalyzer = SessionAnalyzer(config)
    private val similarity = SimilarityEngine()
    private val candidateGenerator = CandidateGenerator()
    private val ranking = RankingEngine(strategy)
    private val exploration = ExplorationManager(config.exploration)
    private val diversity = DiversityReRanker(config.diversity)
    private val explainer = RecommendationExplainer()
    private val skipModel = SkipModel(config.skip)

    /** Builds the long-term profile only (used by the background worker). */
    fun buildProfile(input: PipelineInput): TasteProfile {
        val features = extractor.extract(input.tracks, input.history, input.nowMs, input.zone)
        return profileCalculator.build(features, input.history, input.nowMs, input.zone)
    }

    fun prepare(input: PipelineInput): PreparedRun {
        val features = extractor.extract(input.tracks, input.history, input.nowMs, input.zone)
        val stored = input.storedProfile?.takeIf {
            !it.isEmpty && it.algorithmVersion == config.algorithmVersion && input.nowMs - it.updatedAt in 0..PROFILE_MAX_AGE_MS
        }
        val profile = stored ?: profileCalculator.build(features, input.history, input.nowMs, input.zone)
        val session = sessionAnalyzer.analyze(features, input.history, input.behaviorEvents, input.nowMs, input.zone)

        val loved = features.vectors
            .map { it to TrackAffinity.love(it) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<TrackFeatureVector, Double>> { it.second }.thenBy { it.first.trackId })
            .map { it.first }
        val justFavorited = session.recentFavorited.mapNotNull { features.byId[it] }
        val lovedSeeds = (justFavorited + loved).distinctBy { it.trackId }.take(SEED_COUNT)

        return PreparedRun(
            input = input,
            features = features,
            profile = profile,
            session = session,
            similarity = similarity,
            artistProfiles = similarity.buildArtistProfiles(features.vectors),
            outcomes = ExplorationManager.outcomesFromHistory(input.history, input.nowMs, skipModel),
            tracksById = input.tracks.associateBy { it.id },
            lovedSeeds = lovedSeeds,
            recentSeeds = recentSeeds(features, session, input.history)
        )
    }

    /**
     * One Home section, or null when it would be meaningless (needs seeds that do not exist yet,
     * or nothing eligible is left). [exclude] lets the caller avoid repeating tracks of earlier sections.
     */
    fun generate(run: PreparedRun, type: SectionType, limit: Int = DEFAULT_LIMIT, exclude: Set<Long> = emptySet()): SectionResult? {
        if (limit <= 0 || run.features.vectors.isEmpty()) return null
        val plan = SectionStrategies.plan(type, config, run.profile)
        val seeds = if (plan.seedMode == SeedMode.RECENT) run.recentSeeds else run.lovedSeeds
        if (plan.requiresSeeds && seeds.isEmpty()) return null

        val ctx = buildContext(run, plan.type, plan, seeds, exclude)
        val candidates = candidateGenerator.generate(ctx)
        if (candidates.isEmpty()) return null

        val ranked = ranking.rank(ctx, candidates)
        // Diversity first (so exploitation slots are already varied), exploration composes the list,
        // then a final diversity pass guarantees the caps on what is actually shown.
        val diversified = diversity.reRank(ranked, limit = ranked.size, topN = maxOf(config.diversity.topN, limit))
        val slots = exploration.plan(limit, run.outcomes, plan.exploration)
        val composed = exploration.compose(diversified, ctx, limit, slots)
        // Final guard: the composed list comes first (so exploitation/discovery order is kept), the rest of the
        // diversified pool is only a back-fill. If the composed list repeats one artist/album too often
        // (e.g. a heavy-listened artist fills every exploitation slot) the surplus is replaced by clean
        // tracks instead of being shown; small libraries stay adaptive inside DiversityReRanker.
        val composedIds = composed.mapTo(HashSet()) { it.trackId }
        val pool = composed + diversified.filter { it.trackId !in composedIds }
        val picks = diversity.reRank(pool, limit = limit, topN = limit)

        val items = ArrayList<Recommendation>(picks.size)
        val kept = ArrayList<ScoredTrack>(picks.size)
        for (p in picks) {
            val track = run.tracksById[p.trackId] ?: continue
            items += Recommendation(track, p.score, explainer.explain(ctx, p))
            kept += p
        }
        if (items.isEmpty()) return null
        return SectionResult(RecommendationSection(type, type.titleFa, type.defaultReasonFa, items), kept)
    }

    /**
     * All sections relevant right now, in display order. A section first tries to avoid tracks that
     * earlier sections already show; if that leaves too little (small library) it falls back to
     * allowing repeats instead of disappearing.
     */
    fun generateAll(
        run: PreparedRun,
        types: List<SectionType> = SectionType.values().toList(),
        limit: Int = DEFAULT_LIMIT
    ): List<SectionResult> {
        val used = HashSet<Long>()
        val out = ArrayList<SectionResult>()
        for (type in types) {
            if (!isRelevantNow(type, run.session.daypart)) continue
            val unique = generate(run, type, limit, exclude = used)
            val result = if (unique != null && unique.picks.size >= minOf(MIN_UNIQUE_ITEMS, limit)) unique
            else generate(run, type, limit) ?: unique
            if (result != null) {
                out += result
                result.picks.mapTo(used) { it.trackId }
            }
        }
        return out
    }

    private fun buildContext(
        run: PreparedRun,
        type: SectionType,
        plan: com.ghadirb.aimusic.recommendation.section.SectionPlan,
        seeds: List<TrackFeatureVector>,
        exclude: Set<Long>
    ): RecommendationContext {
        val daypart = plan.daypart ?: run.session.daypart
        val seedIds: Set<Long> = if (plan.excludeSeeds) seeds.mapTo(HashSet()) { it.trackId } else emptySet()
        return RecommendationContext(
            features = run.features,
            profile = run.profile,
            session = run.session,
            config = config.copy(weights = plan.weights, exploration = plan.exploration),
            weights = plan.weights,
            similarity = run.similarity,
            artistProfiles = run.artistProfiles,
            seeds = seeds,
            daypart = daypart,
            isWeekend = run.session.isWeekend,
            energyTarget = plan.energyTarget ?: energyFor(run.profile, daypart),
            random = Random(run.input.randomSeed xor type.id.hashCode().toLong()),
            nowMs = run.input.nowMs,
            eligible = { v -> v.trackId !in seedIds && v.trackId !in exclude && plan.eligible(v) },
            allowedSources = plan.allowedSources
        )
    }

    /** Spec §8: learned energy of that daypart when there is enough evidence, otherwise the generic prior. */
    private fun energyFor(profile: TasteProfile, daypart: String): Double {
        val slice = profile.dayparts[daypart]
        val learned = slice?.energy
        if (slice != null && learned != null && slice.weight >= config.context.minDaypartSamples) {
            return learned.mean.coerceIn(0.0, 1.0)
        }
        return config.context.priorEnergy[daypart] ?: 0.5
    }

    /** Newest distinct tracks the user actually listened to (session first, then plain history). */
    private fun recentSeeds(features: FeatureSet, session: SessionContext, history: List<ListeningHistoryEntity>): List<TrackFeatureVector> {
        val fromSession = session.recentCompleted.mapNotNull { features.byId[it] }
        val fromHistory = history.asSequence()
            .filter { !it.skipped }
            .sortedByDescending { it.startTime }
            .mapNotNull { features.byId[it.trackId] }
            .filter { !it.notInterested }
            .toList()
        return (fromSession + fromHistory).distinctBy { it.trackId }.take(RECENT_SEED_COUNT)
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val SEED_COUNT = 8
        const val RECENT_SEED_COUNT = 5
        const val MIN_UNIQUE_ITEMS = 4
        /** A stored profile older than this is rebuilt from the history instead of trusted. */
        const val PROFILE_MAX_AGE_MS = 36L * 60 * 60 * 1000

        /** "Tonight" only makes sense in the evening/night; everything else is always relevant. */
        fun isRelevantNow(type: SectionType, daypart: String): Boolean = when (type) {
            SectionType.TONIGHT -> daypart == TimeBuckets.EVENING || daypart == TimeBuckets.NIGHT
            else -> true
        }
    }
}
