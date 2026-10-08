package com.ghadirb.aimusic.recommendation.exploration

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.RecommendationContext
import com.ghadirb.aimusic.recommendation.candidate.CandidateSourceId
import com.ghadirb.aimusic.recommendation.config.ExplorationConfig
import com.ghadirb.aimusic.recommendation.ranking.ScoreSignal
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack
import com.ghadirb.aimusic.recommendation.signals.SkipModel
import kotlin.math.pow
import kotlin.math.roundToInt

/** How the user reacted to tracks they had never played before. */
data class DiscoveryOutcomes(val accepted: Int = 0, val rejected: Int = 0) {
    /** Laplace-smoothed acceptance in 0..1; 0.5 = no evidence. */
    val acceptance: Double get() = (accepted + 1.0) / (accepted + rejected + 2.0)
}

/** Number of slots per kind for a list of a given size. */
data class ExplorationPlan(val exploitation: Int, val similarDiscovery: Int, val exploration: Int, val rate: Double)

/**
 * Spec §14 — baseline 70 % exploitation / 20 % similar discovery / 10 % exploration, adapted to the
 * user: accepting new tracks raises exploration, skipping them lowers it (within min/max bounds).
 */
class ExplorationManager(private val base: ExplorationConfig = ExplorationConfig()) {

    fun adaptedConfig(outcomes: DiscoveryOutcomes, from: ExplorationConfig = base): ExplorationConfig {
        // factor 1 at 50 % acceptance, ~2 when (almost) everything new is accepted, ~0.2 when almost nothing is.
        val factor = (outcomes.acceptance / 0.5).coerceIn(0.2, 2.0)
        val exploration = (from.exploration * factor).coerceIn(from.minExploration, from.maxExploration)
        val similar = (from.similarDiscovery * (0.5 + 0.5 * factor)).coerceIn(0.0, 0.5)
        val exploitation = (1.0 - exploration - similar).coerceAtLeast(0.2)
        return from.copy(exploitation = exploitation, similarDiscovery = similar, exploration = exploration)
    }

    fun plan(size: Int, outcomes: DiscoveryOutcomes, from: ExplorationConfig = base): ExplorationPlan {
        // A config that explicitly asks for no discovery (min 0, exploration 0) is respected as is.
        val cfg = if (from.exploration == 0.0 && from.similarDiscovery == 0.0) from else adaptedConfig(outcomes, from)
        var explore = (size * cfg.exploration).roundToInt()
        var similar = (size * cfg.similarDiscovery).roundToInt()
        if (explore + similar > size) similar = (size - explore).coerceAtLeast(0)
        if (size >= 8 && cfg.exploration > 0.0 && explore == 0) explore = 1
        val exploit = (size - similar - explore).coerceAtLeast(0)
        return ExplorationPlan(exploit, similar, explore, cfg.exploration)
    }

    /**
     * Picks [size] items from [ranked] (best-first) following [plan]. Known tracks fill the exploitation
     * slots, unplayed tracks that fit the taste the similar-discovery slots, other unplayed tracks the
     * exploration slots (seeded-random). A pool that runs dry is topped up from the others, so small
     * libraries still produce a full list. Slot kinds are spread over the list, not appended at the end.
     */
    fun compose(ranked: List<ScoredTrack>, ctx: RecommendationContext, size: Int, plan: ExplorationPlan): List<ScoredTrack> {
        if (ranked.isEmpty() || size <= 0) return emptyList()
        val known = ArrayList<ScoredTrack>()
        val similar = ArrayList<ScoredTrack>()
        val explore = ArrayList<ScoredTrack>()
        for (s in ranked) {
            val v = s.vector
            when {
                !v.isUnplayed || v.favorite -> known += s
                isSimilarDiscovery(s) -> similar += s
                else -> explore += s
            }
        }
        val exploreShuffled = explore.shuffled(ctx.random)

        val slots = slotKinds(size, plan)
        val used = HashSet<Long>()
        val out = ArrayList<ScoredTrack>(size)
        val queues = mapOf(Kind.EXPLOIT to known, Kind.SIMILAR to similar, Kind.EXPLORE to exploreShuffled)
        val cursors = HashMap<Kind, Int>()
        fun next(kind: Kind): ScoredTrack? {
            val list = queues.getValue(kind)
            var i = cursors[kind] ?: 0
            while (i < list.size && list[i].trackId in used) i++
            if (i >= list.size) { cursors[kind] = i; return null }
            cursors[kind] = i + 1
            return list[i]
        }
        val fallbackOrder = listOf(Kind.EXPLOIT, Kind.SIMILAR, Kind.EXPLORE)
        for (kind in slots) {
            val pick = next(kind) ?: fallbackOrder.firstNotNullOfOrNull { next(it) } ?: break
            used += pick.trackId
            out += pick
        }
        return out
    }

    private enum class Kind { EXPLOIT, SIMILAR, EXPLORE }

    private fun isSimilarDiscovery(s: ScoredTrack): Boolean {
        val src = s.candidate.sources
        if (CandidateSourceId.DISCOVERY_SIMILAR in src || CandidateSourceId.SIMILAR_TRACK in src ||
            CandidateSourceId.SIMILAR_ARTIST in src || CandidateSourceId.SAME_GENRE in src
        ) return true
        return (s.signals[ScoreSignal.SIMILARITY] ?: 0.0) >= 0.3 || (s.signals[ScoreSignal.TASTE] ?: 0.0) >= 0.6
    }

    /** Spreads the slot kinds evenly over [size] positions (exploitation naturally leads). */
    private fun slotKinds(size: Int, plan: ExplorationPlan): List<Kind> {
        val keyed = ArrayList<Pair<Double, Kind>>()
        fun spread(kind: Kind, count: Int) {
            for (k in 0 until count) keyed += ((k + 0.5) * size / count) to kind
        }
        spread(Kind.EXPLOIT, plan.exploitation)
        spread(Kind.SIMILAR, plan.similarDiscovery)
        spread(Kind.EXPLORE, plan.exploration)
        val sorted = keyed.sortedBy { it.first }.map { it.second }
        // Plan counts always add up to `size`; pad defensively.
        return if (sorted.size >= size) sorted.take(size) else sorted + List(size - sorted.size) { Kind.EXPLOIT }
    }

    companion object {
        private const val WINDOW_DAYS = 60.0
        private const val DAY_MS = 24.0 * 60 * 60 * 1000

        /**
         * First-ever sessions of tracks in the last 60 days are "discoveries": completed = accepted,
         * skipped = rejected. Needs no extra storage — the history already contains it.
         */
        fun outcomesFromHistory(
            history: List<ListeningHistoryEntity>,
            nowMs: Long,
            skipModel: SkipModel = SkipModel()
        ): DiscoveryOutcomes {
            var accepted = 0
            var rejected = 0
            for ((_, sessions) in history.groupBy { it.trackId }) {
                val first = sessions.minByOrNull { it.startTime } ?: continue
                if ((nowMs - first.startTime) / DAY_MS > WINDOW_DAYS) continue
                if (skipModel.isCompleted(first)) accepted++ else if (first.skipped) rejected++
            }
            return DiscoveryOutcomes(accepted, rejected)
        }
    }
}
