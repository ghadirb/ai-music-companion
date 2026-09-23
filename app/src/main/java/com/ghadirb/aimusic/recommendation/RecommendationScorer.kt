package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ReasonType {
    FAVORITE, FAVORITE_ARTIST, GENRE_MATCH, SIMILAR_TO_RECENT, RECENTLY_LOVED,
    NOT_PLAYED_LONG, TIME_OF_DAY_MATCH, EXPLORE, NEW_ADDITION
}

/** A short, human-readable reason for a suggestion (rendered by [ReasonText]). */
data class Reason(val type: ReasonType, val detail: String? = null, val number: Int? = null)

data class Recommendation(val track: TrackEntity, val score: Double, val reasons: List<Reason>)

/** All tunable weights in one place; the scorer is deterministic for a given input + `nowMs`. */
data class ScoringConfig(
    val completedThreshold: Float = 0.8f,
    val decayHalfLifeDays: Double = 21.0,
    val favoriteWeight: Double = 3.0,
    val playWeight: Double = 1.6,
    val playCap: Double = 8.0,
    val replayWeight: Double = 1.2,
    val replayCap: Double = 3.0,
    val skipWeight: Double = 1.4,
    val skipCap: Double = 4.0,
    val artistWeight: Double = 2.5,
    val genreWeight: Double = 1.5,
    val energyWeight: Double = 1.5,
    val bpmWeight: Double = 1.0,
    val rediscoverWeight: Double = 2.0,
    val rediscoverAfterDays: Int = 30,
    val exploreWeight: Double = 0.6,
    val recentWindowDays: Int = 14,
    val newTrackDays: Int = 14
)

/** Per-track behavioural signals derived from listening history. */
data class TrackSignals(
    val trackId: Long,
    val plays: Int,
    val completedRaw: Int,
    val completedDecayed: Double,
    val skipsRaw: Int,
    val skipsDecayed: Double,
    val replays: Int,
    val lastPlayedAt: Long?,
    val recentCompletions: Int
)

class ListeningStats(
    val signals: Map<Long, TrackSignals>,
    val artistAffinity: Map<String, Double>,
    val genreAffinity: Map<String, Double>,
    val preferredEnergy: Double?,
    val preferredEnergyByBucket: Map<String, Double>,
    val preferredBpm: Double?,
    val recentArtists: Set<String>,
    val recentGenres: Set<String>
) {
    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val UNKNOWN_ARTIST = "Unknown artist"

        fun build(
            tracks: List<TrackEntity>,
            history: List<ListeningHistoryEntity>,
            nowMs: Long,
            config: ScoringConfig = ScoringConfig(),
            zone: TimeZone = TimeZone.getDefault()
        ): ListeningStats {
            val tracksById = tracks.associateBy { it.id }
            fun decay(ageMs: Long): Double = 0.5.pow(max(0.0, ageMs.toDouble() / DAY_MS) / config.decayHalfLifeDays)

            val signals = HashMap<Long, TrackSignals>()
            val energyAll = WeightedAverage()
            val bpmAll = WeightedAverage()
            val energyByBucket = HashMap<String, WeightedAverage>()
            val artistCountsRecent = HashMap<String, Int>()
            val genreCountsRecent = HashMap<String, Int>()

            for ((trackId, entries) in history.groupBy { it.trackId }) {
                val track = tracksById[trackId] ?: continue
                var completedRaw = 0; var completedDecayed = 0.0
                var skipsRaw = 0; var skipsDecayed = 0.0
                var replays = 0; var last: Long? = null; var recent = 0
                for (e in entries) {
                    val age = (nowMs - e.startTime).coerceAtLeast(0L)
                    val w = decay(age)
                    val completed = !e.skipped && e.completedPercentage >= config.completedThreshold
                    if (completed) {
                        completedRaw++; completedDecayed += w
                        if (age <= config.recentWindowDays * DAY_MS) recent++
                        track.energyLevel?.let {
                            energyAll.add(it.toDouble(), w)
                            energyByBucket.getOrPut(TimeBuckets.bucketOf(e.startTime, zone)) { WeightedAverage() }.add(it.toDouble(), w)
                        }
                        track.bpm?.let { bpmAll.add(it.toDouble(), w) }
                        if (age <= 3 * DAY_MS) {
                            if (track.artist != UNKNOWN_ARTIST) artistCountsRecent.merge(track.artist, 1, Int::plus)
                            track.genre?.let { genreCountsRecent.merge(it, 1, Int::plus) }
                        }
                    }
                    if (e.skipped) { skipsRaw++; skipsDecayed += w }
                    replays += e.replayCount
                    if (last == null || e.startTime > last) last = e.startTime
                }
                signals[trackId] = TrackSignals(trackId, entries.size, completedRaw, completedDecayed, skipsRaw, skipsDecayed, replays, last, recent)
            }

            val artistRaw = HashMap<String, Double>()
            val genreRaw = HashMap<String, Double>()
            for (track in tracks) {
                val s = signals[track.id]
                var w = (s?.completedDecayed ?: 0.0) + 0.6 * (s?.replays ?: 0) - 0.5 * (s?.skipsDecayed ?: 0.0)
                if (track.isFavorite) w += 1.5
                if (w <= 0.0) continue
                if (track.artist != UNKNOWN_ARTIST) artistRaw.merge(track.artist, w, Double::plus)
                track.genre?.let { genreRaw.merge(it, w, Double::plus) }
            }

            return ListeningStats(
                signals = signals,
                artistAffinity = normalise(artistRaw),
                genreAffinity = normalise(genreRaw),
                preferredEnergy = energyAll.value(),
                preferredEnergyByBucket = energyByBucket.mapNotNull { (k, v) -> v.value()?.let { k to it } }.toMap(),
                preferredBpm = bpmAll.value(),
                recentArtists = artistCountsRecent.filterValues { it >= 2 }.keys,
                recentGenres = genreCountsRecent.filterValues { it >= 3 }.keys
            )
        }

        private fun normalise(raw: Map<String, Double>): Map<String, Double> {
            val top = raw.values.maxOrNull() ?: return emptyMap()
            return if (top <= 0.0) emptyMap() else raw.mapValues { it.value / top }
        }
    }

    private class WeightedAverage {
        private var sum = 0.0
        private var weight = 0.0
        fun add(value: Double, w: Double) { sum += value * w; weight += w }
        fun value(): Double? = if (weight > 0.0) sum / weight else null
    }
}

/**
 * Explainable, deterministic scoring. No ML: a weighted sum of clear behavioural signals
 * (favourites, completed plays, replays, skips, artist/genre affinity, time-of-day energy match,
 * BPM, rediscovery and light exploration), each of which can produce a short reason.
 */
object RecommendationScorer {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun score(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        config: ScoringConfig = ScoringConfig(),
        zone: TimeZone = TimeZone.getDefault(),
        stats: ListeningStats = ListeningStats.build(tracks, history, nowMs, config, zone)
    ): List<Recommendation> {
        val bucket = TimeBuckets.bucketOf(nowMs, zone)
        val energyPref = stats.preferredEnergyByBucket[bucket] ?: stats.preferredEnergy
        val usingBucketEnergy = stats.preferredEnergyByBucket.containsKey(bucket)

        // "Not interested" tracks are excluded from every suggestion surface (Home picks, smart
        // mixes, smart playlists) but stay untouched in the Library itself.
        return tracks.filterNot { it.notInterested }.map { track -> scoreOne(track, stats, config, nowMs, energyPref, usingBucketEnergy) }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenByDescending { it.track.dateAdded }.thenBy { it.track.title })
    }

    private fun scoreOne(
        track: TrackEntity,
        stats: ListeningStats,
        c: ScoringConfig,
        nowMs: Long,
        energyPref: Double?,
        usingBucketEnergy: Boolean
    ): Recommendation {
        val s = stats.signals[track.id]
        val contributions = ArrayList<Pair<Double, Reason>>()
        var total = 0.0

        if (track.isFavorite) {
            total += c.favoriteWeight
            contributions += c.favoriteWeight to Reason(ReasonType.FAVORITE)
        }
        if (s != null) {
            total += min(c.playCap, s.completedDecayed * c.playWeight)
            total += min(c.replayCap, s.replays * c.replayWeight)
            total -= min(c.skipCap, s.skipsDecayed) * c.skipWeight
            if (s.recentCompletions >= 2) {
                contributions += (1.0 + s.recentCompletions * 0.4) to Reason(ReasonType.RECENTLY_LOVED, number = s.recentCompletions)
            }
        }

        val artistAff = stats.artistAffinity[track.artist] ?: 0.0
        total += c.artistWeight * artistAff
        if (artistAff >= 0.4) contributions += (c.artistWeight * artistAff) to Reason(ReasonType.FAVORITE_ARTIST, detail = track.artist)

        val genreAff = track.genre?.let { stats.genreAffinity[it] } ?: 0.0
        total += c.genreWeight * genreAff
        if (genreAff >= 0.5) contributions += (c.genreWeight * genreAff) to Reason(ReasonType.GENRE_MATCH, detail = track.genre)

        val energy = track.energyLevel
        if (energyPref != null && energy != null) {
            val closeness = (1.0 - abs(energy - energyPref) * 2.5).coerceIn(0.0, 1.0)
            total += closeness * c.energyWeight
            if (usingBucketEnergy && closeness >= 0.8) contributions += (closeness * c.energyWeight) to Reason(ReasonType.TIME_OF_DAY_MATCH)
        }
        val bpm = track.bpm
        val bpmPref = stats.preferredBpm
        if (bpmPref != null && bpm != null) {
            total += (1.0 - abs(bpm - bpmPref) / 60.0).coerceIn(0.0, 1.0) * c.bpmWeight
        }

        val lastPlayed = s?.lastPlayedAt
        val liked = track.isFavorite || (s?.completedRaw ?: 0) >= 2
        val daysSince = when {
            lastPlayed != null -> ((nowMs - lastPlayed) / DAY_MS).toInt()
            track.isFavorite -> 60 // favourited but never played on this device
            else -> null
        }
        if (liked && daysSince != null && daysSince >= c.rediscoverAfterDays) {
            val bonus = c.rediscoverWeight * min(1.0, daysSince / 120.0)
            total += bonus
            contributions += (bonus + 1.0) to Reason(ReasonType.NOT_PLAYED_LONG, number = daysSince)
        }

        if (s == null) {
            val similarToRecent = track.artist in stats.recentArtists || (track.genre != null && track.genre in stats.recentGenres)
            if (similarToRecent) contributions += 1.2 to Reason(ReasonType.SIMILAR_TO_RECENT)
            if (artistAff >= 0.3 || genreAff >= 0.3) {
                total += c.exploreWeight
                contributions += (c.exploreWeight + 0.5) to Reason(ReasonType.EXPLORE)
            }
            if (nowMs - track.dateAdded in 0..(c.newTrackDays * DAY_MS)) contributions += 0.3 to Reason(ReasonType.NEW_ADDITION)
        }

        val reasons = contributions.sortedByDescending { it.first }.map { it.second }.distinctBy { it.type }.take(2)
        return Recommendation(track, total, reasons)
    }
}
