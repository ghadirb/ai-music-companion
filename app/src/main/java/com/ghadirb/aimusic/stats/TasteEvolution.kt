package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

data class PeriodProfile(
    val completedPlays: Int,
    val listenMs: Long,
    /** Share (0..1) of completed plays per genre, among plays whose track has a genre. */
    val genreShare: Map<String, Float>,
    val genrePlays: Int,
    val topGenre: String?,
    val dominantMood: String?,
    val avgBpm: Int?,
    val avgEnergy: Float?,
    val skipRate: Float,
    val artists: Set<String>,
    val newArtists: Set<String>,
    val discoveredTracks: Int
)

data class GenreChange(val genre: String, val previousShare: Float, val currentShare: Float) {
    val delta: Float get() = currentShare - previousShare
}

data class EvolutionResult(
    val current: PeriodProfile,
    val previous: PeriodProfile?,
    /** False when the current period has too little data: the UI must say so instead of showing numbers. */
    val enoughData: Boolean,
    /** Comparison is only produced when the previous period also has enough data. */
    val comparable: Boolean,
    val genreChanges: List<GenreChange>,
    val listenTimeChangePercent: Int?,
    val bpmChange: Int?,
    val energyChange: Float?,
    val skipRateChange: Float?
)

/**
 * "Taste evolution": how listening changed versus the previous week/month, computed entirely from local history.
 * Nothing is invented: with fewer than [MIN_PLAYS] completed plays a period is reported as "not enough data".
 */
object TasteEvolution {
    const val MIN_PLAYS = 10
    private const val MIN_GENRE_PLAYS = 5
    private const val UNKNOWN_ARTIST = "Unknown artist"

    fun compute(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        current: PeriodBounds,
        previous: PeriodBounds?,
        completedThreshold: Float = 0.8f
    ): EvolutionResult {
        val byId = tracks.associateBy { it.id }
        val known = history.filter { it.trackId in byId }
        val firstPlay = known.groupBy { it.trackId }.mapValues { (_, l) -> l.minOf { it.startTime } }
        val firstPlayByArtist = HashMap<String, Long>()
        for ((id, t) in firstPlay) {
            val artist = byId.getValue(id).artist
            if (artist != UNKNOWN_ARTIST) firstPlayByArtist.merge(artist, t) { a, b -> minOf(a, b) }
        }

        fun profile(bounds: PeriodBounds): PeriodProfile {
            val entries = known.filter { bounds.contains(it.startTime) }
            val completed = entries.filter { !it.skipped && it.completedPercentage >= completedThreshold }
            val playedTracks = completed.map { byId.getValue(it.trackId) }
            val genres = playedTracks.mapNotNull { it.genre?.takeIf(String::isNotBlank) }
            val genreCounts = genres.groupingBy { it }.eachCount()
            val artists = playedTracks.map { it.artist }.filter { it != UNKNOWN_ARTIST }.toSet()
            return PeriodProfile(
                completedPlays = completed.size,
                listenMs = entries.sumOf { it.listenDurationMs },
                genreShare = if (genres.isEmpty()) emptyMap() else genreCounts.mapValues { it.value.toFloat() / genres.size },
                genrePlays = genres.size,
                topGenre = genreCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key,
                dominantMood = playedTracks.mapNotNull { it.moodTag }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                avgBpm = playedTracks.mapNotNull { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgEnergy = playedTracks.mapNotNull { it.energyLevel }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                skipRate = if (entries.isEmpty()) 0f else entries.count { it.skipped }.toFloat() / entries.size,
                artists = artists,
                newArtists = artists.filter { (firstPlayByArtist[it] ?: Long.MAX_VALUE) in bounds.startMs until bounds.endMs }.toSet(),
                discoveredTracks = completed.map { it.trackId }.toSet().count { (firstPlay[it] ?: Long.MAX_VALUE) in bounds.startMs until bounds.endMs }
            )
        }

        val cur = profile(current)
        val prev = previous?.let(::profile)
        val enough = cur.completedPlays >= MIN_PLAYS
        val comparable = enough && prev != null && prev.completedPlays >= MIN_PLAYS

        val changes = if (comparable && prev != null && cur.genrePlays >= MIN_GENRE_PLAYS && prev.genrePlays >= MIN_GENRE_PLAYS) {
            (cur.genreShare.keys + prev.genreShare.keys).map { g ->
                GenreChange(g, prev.genreShare[g] ?: 0f, cur.genreShare[g] ?: 0f)
            }.sortedByDescending { abs(it.delta) }.take(4).filter { abs(it.delta) >= 0.03f }
        } else emptyList()

        val curBpm = cur.avgBpm
        val prevBpm = prev?.avgBpm
        val curEnergy = cur.avgEnergy
        val prevEnergy = prev?.avgEnergy
        val prevListen = prev?.listenMs ?: 0L
        return EvolutionResult(
            current = cur,
            previous = prev,
            enoughData = enough,
            comparable = comparable,
            genreChanges = changes,
            listenTimeChangePercent = if (comparable && prevListen > 0) (((cur.listenMs - prevListen) * 100.0) / prevListen).roundToInt() else null,
            bpmChange = if (comparable && curBpm != null && prevBpm != null) curBpm - prevBpm else null,
            energyChange = if (comparable && curEnergy != null && prevEnergy != null) curEnergy - prevEnergy else null,
            skipRateChange = if (comparable && prev != null) cur.skipRate - prev.skipRate else null
        )
    }
}
