package com.ghadirb.aimusic.recommendation.evaluation

import com.ghadirb.aimusic.data.local.entity.BehaviorEventEntity
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.RecommendationEventEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.signals.ListeningBehavior

/**
 * Spec §26 — how well the engine is doing for THIS user. Every rate is null when it cannot be
 * computed (no data), never a fake 0. Everything is derived from local tables only.
 */
data class EvaluationReport(
    /** Suggestions shown / the subset the user actually played afterwards. */
    val shown: Int,
    val played: Int,
    /** played / shown. */
    val hitRate: Double?,
    /** Of the played suggestions: finished (>= completed threshold) / skipped. */
    val completionRate: Double?,
    val skipRate: Double?,
    /** Of the played suggestions: favourited after being suggested / replayed after being suggested. */
    val favoriteRate: Double?,
    val replayRate: Double?,
    /** Of the played never-heard-before suggestions: how many were finished. */
    val discoveryAcceptance: Double?,
    /** Mean share of distinct artists / genres per displayed list (1.0 = all different). */
    val artistDiversity: Double?,
    val genreDiversity: Double?,
    /** Same hit rate per section id, to see which section earns its place. */
    val hitRateBySection: Map<String, Double>
)

object RecommendationEvaluator {

    fun evaluate(
        events: List<RecommendationEventEntity>,
        tracksById: Map<Long, TrackEntity>,
        history: List<ListeningHistoryEntity> = emptyList(),
        behavior: List<BehaviorEventEntity> = emptyList()
    ): EvaluationReport {
        val shownEvents = events.filter { it.shown }
        val playedEvents = shownEvents.filter { it.played }

        fun ratio(n: Int, d: Int): Double? = if (d <= 0) null else n.toDouble() / d

        val favorited = playedEvents.count { e -> wasFavoritedAfter(e, behavior) }
        val replayed = playedEvents.count { e -> history.any { it.trackId == e.trackId && it.startTime >= e.timestamp && it.replayCount > 0 } }
        val discoveryPlayed = playedEvents.filter { it.discovery }

        val bySection = shownEvents.groupBy { it.section }.mapNotNull { (section, list) ->
            ratio(list.count { it.played }, list.size)?.let { section to it }
        }.toMap()

        // A displayed list = all events of one section generated at the same instant.
        val batches = shownEvents.groupBy { it.section to it.timestamp }.values
        val artistDiv = batches.mapNotNull { batch -> diversityOf(batch.mapNotNull { tracksById[it.trackId]?.artist }) }
        val genreDiv = batches.mapNotNull { batch -> diversityOf(batch.mapNotNull { tracksById[it.trackId]?.genre }) }

        return EvaluationReport(
            shown = shownEvents.size,
            played = playedEvents.size,
            hitRate = ratio(playedEvents.size, shownEvents.size),
            completionRate = ratio(playedEvents.count { it.completed }, playedEvents.size),
            skipRate = ratio(playedEvents.count { it.skipped }, playedEvents.size),
            favoriteRate = ratio(favorited, playedEvents.size),
            replayRate = ratio(replayed, playedEvents.size),
            discoveryAcceptance = ratio(discoveryPlayed.count { it.completed }, discoveryPlayed.size),
            artistDiversity = artistDiv.takeIf { it.isNotEmpty() }?.average(),
            genreDiversity = genreDiv.takeIf { it.isNotEmpty() }?.average(),
            hitRateBySection = bySection
        )
    }

    /** Distinct / total over the known (non-blank, non-"unknown") values of one list. Null if none are known. */
    fun diversityOf(values: List<String>): Double? {
        val known = values.filter {
            it.isNotBlank() && it != FeatureNormalizer.UNKNOWN_ARTIST && it != FeatureNormalizer.UNKNOWN_ALBUM
        }.map(FeatureNormalizer::key)
        if (known.isEmpty()) return null
        return known.toSet().size.toDouble() / known.size
    }

    /** Favourited at/after the suggestion and not un-favourited again afterwards. */
    private fun wasFavoritedAfter(e: RecommendationEventEntity, behavior: List<BehaviorEventEntity>): Boolean {
        val last = behavior
            .filter { it.trackId == e.trackId && it.timestamp >= e.timestamp }
            .filter { it.behavior == ListeningBehavior.FAVORITED.name || it.behavior == ListeningBehavior.UNFAVORITED.name }
            .maxByOrNull { it.timestamp } ?: return false
        return last.behavior == ListeningBehavior.FAVORITED.name
    }
}
