package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import java.util.TimeZone

/** Encodes list fields with a unit-separator so names containing commas survive; decodes legacy comma lists too. */
object ListCodec {
    private const val SEP = '\u001F'
    fun encode(values: List<String>): String = values.joinToString(SEP.toString())
    fun decode(raw: String): List<String> = when {
        raw.isBlank() -> emptyList()
        raw.contains(SEP) -> raw.split(SEP).filter { it.isNotBlank() }
        else -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** Pure builder: turns library + listening history into a [UserPreferenceEntity]. Fully local. */
object TasteProfileBuilder {
    private const val UNKNOWN_ARTIST = "Unknown artist"

    fun build(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        config: ScoringConfig = ScoringConfig(),
        zone: TimeZone = TimeZone.getDefault()
    ): UserPreferenceEntity {
        if (tracks.isEmpty()) return UserPreferenceEntity(updatedAt = nowMs)
        val stats = ListeningStats.build(tracks, history, nowMs, config, zone)
        val tracksById = tracks.associateBy { it.id }

        // Track "love" weight: completed plays + replays + favourite bonus - skips.
        val weights = HashMap<Long, Double>()
        for (track in tracks) {
            val s = stats.signals[track.id]
            val w = (s?.completedDecayed ?: 0.0) + 0.6 * (s?.replays ?: 0) - 0.5 * (s?.skipsDecayed ?: 0.0) +
                (if (track.isFavorite) 1.5 else 0.0)
            if (w > 0.0) weights[track.id] = w
        }
        val loved = weights.keys.mapNotNull { tracksById[it] }

        fun topBy(selector: (TrackEntity) -> String?, n: Int): List<String> {
            val scores = HashMap<String, Double>()
            for (t in loved) selector(t)?.let { scores.merge(it, weights.getValue(t.id), Double::plus) }
            return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(n).map { it.key }
        }

        val energies = loved.mapNotNull { it.energyLevel }.sorted()
        val energyRange = if (energies.size >= 3) {
            "%.2f-%.2f".format(java.util.Locale.US, percentile(energies, 0.2), percentile(energies, 0.8))
        } else ""

        val completedEntries = history.filter { !it.skipped && it.completedPercentage >= config.completedThreshold && it.trackId in tracksById }
        val hours = completedEntries.groupingBy { TimeBuckets.hourOf(it.startTime, zone) }.eachCount()
        val buckets = completedEntries.groupingBy { TimeBuckets.bucketOf(it.startTime, zone) }.eachCount()
        val validHistory = history.filter { it.trackId in tracksById }

        val durations = completedEntries.mapNotNull { tracksById[it.trackId]?.durationMs }
        return UserPreferenceEntity(
            favoriteArtists = ListCodec.encode(topBy({ it.artist.takeIf { a -> a != UNKNOWN_ARTIST } }, 5)),
            favoriteGenres = ListCodec.encode(topBy({ it.genre }, 5)),
            favoriteMoods = ListCodec.encode(topBy({ it.moodTag }, 3)),
            favoriteEnergyLevel = stats.preferredEnergy?.let { "%.2f".format(java.util.Locale.US, it) } ?: "unknown",
            energyRange = energyRange,
            preferredBpm = stats.preferredBpm?.toInt() ?: 0,
            preferredDurationMs = if (durations.isNotEmpty()) durations.sum() / durations.size else 0L,
            preferredTimeOfDay = buckets.maxByOrNull { it.value }?.key ?: "unknown",
            peakHours = hours.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .take(3).joinToString(",") { it.key.toString() },
            topTrackIds = weights.entries.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
                .take(10).joinToString(",") { it.key.toString() },
            skipRate = if (validHistory.isEmpty()) 0f else validHistory.count { it.skipped }.toFloat() / validHistory.size,
            favoriteRatio = tracks.count { it.isFavorite }.toFloat() / tracks.size,
            updatedAt = nowMs
        )
    }

    private fun percentile(sorted: List<Float>, p: Double): Double {
        val index = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lo = index.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo)
    }
}
