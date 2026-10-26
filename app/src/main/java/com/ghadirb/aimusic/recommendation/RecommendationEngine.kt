package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.recommendation.cache.CachedItem
import com.ghadirb.aimusic.recommendation.cache.CachedSection
import com.ghadirb.aimusic.recommendation.cache.RecommendationCacheCodec
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.evaluation.EvaluationReport
import com.ghadirb.aimusic.recommendation.evaluation.RecommendationEvaluator
import com.ghadirb.aimusic.recommendation.events.RecommendationEvents
import com.ghadirb.aimusic.recommendation.profile.TasteProfile
import com.ghadirb.aimusic.recommendation.section.RecommendationSection
import com.ghadirb.aimusic.recommendation.section.SectionType
import com.ghadirb.aimusic.recommendation.session.BehaviorEvent
import com.ghadirb.aimusic.recommendation.signals.ListeningBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Sections read from the local cache; [isFresh] tells whether a refresh is still needed. */
data class CachedSections(val sections: List<RecommendationSection>, val isFresh: Boolean)

/**
 * Thin, suspend-friendly façade over the on-device recommendation code. All heavy work runs on
 * Dispatchers.Default, uses only on-device data and every suggestion carries a short human-readable reason.
 *
 * Two generations live side by side:
 *  - **v2 pipeline** ([cachedSections] / [refreshSections]): candidate generation → ranking → diversity →
 *    exploration → explanation, one strategy per Home section, cached in Room (spec §17–§20).
 *  - **legacy scorer** ([recommend], [similarTracks], [buildTasteProfile]): still used by Radio, smart
 *    mixes and smart playlists, and as the automatic fallback if the v2 pipeline ever fails.
 */
class RecommendationEngine(
    private val repository: MusicRepository,
    private val config: ScoringConfig = ScoringConfig(),
    private val smartConfig: RecommendationConfig = RecommendationConfig(),
    private val pipeline: RecommendationPipeline = RecommendationPipeline(smartConfig)
) {

    /**
     * Cache rows are stored as "<algorithm version>:<config hash>": a new algorithm version (v3, ML …)
     * or any tuning change makes old rows unusable automatically (spec §20).
     */
    val cacheVersion: String
        get() = "${smartConfig.algorithmVersion}:${Integer.toHexString(smartConfig.hashCode())}"

    // ------------------------------------------------------------------------------------------
    // v2: Home sections
    // ------------------------------------------------------------------------------------------

    /** Cached sections for the current library (fast: no ranking). Empty if there is no valid cache. */
    suspend fun cachedSections(nowMs: Long = System.currentTimeMillis()): CachedSections =
        cachedSections(repository.allTracksSnapshot(), nowMs)

    private suspend fun cachedSections(tracks: List<TrackEntity>, nowMs: Long): CachedSections {
        if (tracks.isEmpty()) return CachedSections(emptyList(), false)
        val rows = repository.loadRecommendationCache("${smartConfig.algorithmVersion}:")
        val cached = rows.filter { it.algorithmVersion == cacheVersion }.mapNotNull(RecommendationCacheCodec::fromEntity)
        if (cached.isEmpty()) return CachedSections(emptyList(), false)

        val byId = tracks.associateBy { it.id }
        val sections = cached.mapNotNull { RecommendationCacheCodec.rebuild(it, byId) }.sortedBy { it.type.ordinal }
        if (sections.isEmpty()) return CachedSections(emptyList(), false)

        val generatedAt = cached.minOf { it.generatedAt }
        var fresh = RecommendationCacheCodec.isFresh(generatedAt, nowMs, smartConfig.cache.ttlMs)
        // The time of day changed (e.g. "tonight" now applies) → recompute.
        if (fresh && TimeBuckets.bucketOf(generatedAt) != TimeBuckets.bucketOf(nowMs)) fresh = false
        // Tracks were removed from the library since the cache was written.
        if (fresh && cached.any { c -> c.items.any { it.trackId !in byId } }) fresh = false
        // The user listened to several tracks (e.g. a skip streak) → adapt quickly (spec §9).
        if (fresh && repository.historyCountSince(generatedAt) >= REFRESH_AFTER_SESSIONS) fresh = false
        return CachedSections(sections, fresh)
    }

    /**
     * The sections for Home. Returns the cache untouched while it is valid; otherwise (or when [force])
     * runs the full pipeline on Dispatchers.Default, stores cache + events, and returns the new sections.
     * Any failure falls back to the legacy scorer, so Home never ends up empty because of a bug here.
     */
    suspend fun refreshSections(
        limit: Int = HOME_SECTION_SIZE,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): List<RecommendationSection> {
        val tracks = repository.allTracksSnapshot()
        if (tracks.isEmpty()) return emptyList()
        if (!force) {
            val cached = cachedSections(tracks, nowMs)
            if (cached.isFresh) return cached.sections
        }
        return try {
            val history = repository.recentHistory(HISTORY_WINDOW)
            val behavior = repository.behaviorEventsSince(nowMs - BEHAVIOR_WINDOW_MS).mapNotNull { e ->
                val kind = runCatching { ListeningBehavior.valueOf(e.behavior) }.getOrNull() ?: return@mapNotNull null
                BehaviorEvent(e.trackId, kind, e.timestamp)
            }
            val stored = repository.getTasteProfile()
            val results = withContext(Dispatchers.Default) {
                val run = pipeline.prepare(PipelineInput(tracks, history, behavior, stored, nowMs))
                pipeline.generateAll(run, limit = limit)
            }
            if (results.isEmpty()) legacySections(limit, nowMs)
            else {
                persist(results, nowMs)
                results.map { it.section }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            legacySections(limit, nowMs)
        }
    }

    private suspend fun persist(results: List<SectionResult>, nowMs: Long) {
        val cached = results.map { r ->
            CachedSection(
                type = r.section.type,
                items = r.picks.zip(r.section.items) { pick, rec ->
                    CachedItem(pick.trackId, pick.score, RecommendationEvents.mainSource(pick), rec.reasons)
                },
                generatedAt = nowMs,
                algorithmVersion = cacheVersion
            )
        }
        repository.replaceRecommendationCache(cached.map(RecommendationCacheCodec::toEntity))
        repository.logRecommendationEvents(results.flatMap { RecommendationEvents.toEntities(it, nowMs, smartConfig.algorithmVersion) })
    }

    /** Automatic fallback (spec: "fallback to the previous engine"): one "for you" section from the legacy scorer. */
    private suspend fun legacySections(limit: Int, nowMs: Long): List<RecommendationSection> {
        val picks = try { recommend(limit, nowMs) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        if (picks.isEmpty()) return emptyList()
        val type = SectionType.FOR_YOU
        return listOf(RecommendationSection(type, type.titleFa, type.defaultReasonFa, picks))
    }

    /** Long-term multi-dimensional profile (spec §7) — computed in the background by TasteProfileWorker. */
    suspend fun buildSmartProfile(nowMs: Long = System.currentTimeMillis()): TasteProfile? {
        val tracks = repository.allTracksSnapshot()
        if (tracks.isEmpty()) return null
        val history = repository.recentHistory(HISTORY_WINDOW)
        return withContext(Dispatchers.Default) { pipeline.buildProfile(PipelineInput(tracks, history, nowMs = nowMs)) }
    }

    /** Spec §26 — local-only quality report over the last [windowDays] days. */
    suspend fun evaluate(nowMs: Long = System.currentTimeMillis(), windowDays: Int = 30): EvaluationReport {
        val since = nowMs - windowDays * DAY_MS
        val events = repository.recommendationEventsSince(since)
        val tracks = repository.allTracksSnapshot().associateBy { it.id }
        val history = repository.recentHistory(HISTORY_WINDOW).filter { it.startTime >= since }
        val behavior = repository.behaviorEventsSince(since)
        return withContext(Dispatchers.Default) { RecommendationEvaluator.evaluate(events, tracks, history, behavior) }
    }

    // ------------------------------------------------------------------------------------------
    // Legacy scorer (unchanged behaviour)
    // ------------------------------------------------------------------------------------------

    suspend fun recommend(limit: Int = 10, nowMs: Long = System.currentTimeMillis()): List<Recommendation> {
        val tracks = repository.observeTracks().first()
        if (tracks.isEmpty()) return emptyList()
        val history = repository.recentHistory(HISTORY_WINDOW)
        return withContext(Dispatchers.Default) { RecommendationScorer.score(tracks, history, nowMs, config).take(limit) }
    }

    suspend fun topRecommendations(limit: Int = 10): List<TrackEntity> = recommend(limit).map { it.track }

    /** Offline similarity ranking; ready to be replaced by an embedding model later. */
    suspend fun similarTracks(source: TrackEntity, limit: Int = 12): List<TrackEntity> {
        val tracks = repository.observeTracks().first()
        return withContext(Dispatchers.Default) {
            tracks.asSequence()
                .filter { it.id != source.id }
                .map { candidate -> candidate to TrackSimilarity.score(source, candidate) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    fun buildTasteProfile(
        tracks: List<TrackEntity>,
        historyByTrack: Map<Long, List<ListeningHistoryEntity>>
    ): UserPreferenceEntity =
        TasteProfileBuilder.build(tracks, historyByTrack.values.flatten(), System.currentTimeMillis(), config)

    companion object {
        const val HOME_SECTION_SIZE = 10
        /** This many new listening sessions since the cache was written trigger a refresh. */
        const val REFRESH_AFTER_SESSIONS = 3
        private const val HISTORY_WINDOW = 3000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val BEHAVIOR_WINDOW_MS = 14L * DAY_MS
    }
}
