package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import java.util.Calendar
import java.util.TimeZone

data class RankedTrack(val track: TrackEntity, val plays: Int)
data class RankedName(val name: String, val plays: Int)

data class StatsSummary(
    // Basic (Free)
    val sessions: Int,
    val completedPlays: Int,
    val listenMs: Long,
    val uniqueTracks: Int,
    val skipRate: Float,
    val topTracks: List<RankedTrack>,
    val topArtists: List<RankedName>,
    // Advanced (Premium)
    val topGenres: List<RankedName>,
    /** Completed plays per hour of day (size 24). */
    val playsByHour: List<Int>,
    /** Completed plays per weekday, Sunday-first (size 7). */
    val playsByWeekday: List<Int>,
    val moodCounts: Map<String, Int>,
    /** Listening minutes for each of the last 14 days, oldest first. */
    val dailyMinutes: List<Int>,
    val topAlbums: List<RankedName> = emptyList(),
    /** Hour of day (0..23) with the most completed plays, or null without data. */
    val peakHour: Int? = null,
    /** Weekday index (0 = Sunday … 6 = Saturday) with the most completed plays, or null without data. */
    val peakWeekday: Int? = null,
    /** Tracks whose very first play in the history happened inside this period. */
    val discoveredTracks: Int = 0,
    /** discoveredTracks / uniqueTracks (0 when nothing was played). */
    val discoveryRate: Float = 0f,
    val topSkipped: List<RankedTrack> = emptyList(),
    val favoriteCount: Int = 0
) {
    val isEmpty: Boolean get() = sessions == 0
}

/** Pure statistics over local listening history. */
object StatsCalculator {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val UNKNOWN_ARTIST = "Unknown artist"

    fun compute(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        zone: TimeZone = TimeZone.getDefault(),
        topN: Int = 5
    ): StatsSummary {
        val byId = tracks.associateBy { it.id }
        val known = history.filter { it.trackId in byId }
        val entries = known.filter { it.startTime >= sinceMs && it.startTime < untilMs }
        val completed = entries.filter { !it.skipped }

        val topTracks = completed.groupingBy { it.trackId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(topN).map { RankedTrack(byId.getValue(it.key), it.value) }

        fun ranked(selector: (TrackEntity) -> String?): List<RankedName> =
            completed.mapNotNull { e -> selector(byId.getValue(e.trackId)) }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(topN).map { RankedName(it.key, it.value) }

        val hours = IntArray(24)
        val weekdays = IntArray(7)
        for (e in completed) {
            val cal = Calendar.getInstance(zone).apply { timeInMillis = e.startTime }
            hours[cal.get(Calendar.HOUR_OF_DAY)]++
            weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]++
        }

        val dayZero = startOfDay(nowMs, zone) - 13 * DAY_MS
        val minutes = LongArray(14)
        for (e in entries) {
            val index = ((startOfDay(e.startTime, zone) - dayZero) / DAY_MS).toInt()
            if (index in 0..13) minutes[index] += e.listenDurationMs
        }

        val firstPlay = known.groupBy { it.trackId }.mapValues { (_, list) -> list.minOf { it.startTime } }
        val uniqueIds = completed.map { it.trackId }.toSet()
        val discovered = uniqueIds.count { (firstPlay[it] ?: Long.MAX_VALUE) in sinceMs until untilMs }

        val topAlbums = completed.mapNotNull { e ->
            byId.getValue(e.trackId).let { t -> t.album.takeIf { it != "Unknown album" && it.isNotBlank() }?.let { a -> if (t.artist != UNKNOWN_ARTIST) "$a — ${t.artist}" else a } }
        }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topN).map { RankedName(it.key, it.value) }

        val topSkipped = entries.filter { it.skipped }.groupingBy { it.trackId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(topN).map { RankedTrack(byId.getValue(it.key), it.value) }

        return StatsSummary(
            sessions = entries.size,
            completedPlays = completed.size,
            listenMs = entries.sumOf { it.listenDurationMs },
            uniqueTracks = completed.map { it.trackId }.toSet().size,
            skipRate = if (entries.isEmpty()) 0f else entries.count { it.skipped }.toFloat() / entries.size,
            topTracks = topTracks,
            topArtists = ranked { it.artist.takeIf { a -> a != UNKNOWN_ARTIST } },
            topGenres = ranked { it.genre?.takeIf(String::isNotBlank) },
            playsByHour = hours.toList(),
            playsByWeekday = weekdays.toList(),
            moodCounts = completed.mapNotNull { byId.getValue(it.trackId).moodTag }.groupingBy { it }.eachCount(),
            dailyMinutes = minutes.map { (it / 60_000L).toInt() },
            topAlbums = topAlbums,
            peakHour = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
            peakWeekday = weekdays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
            discoveredTracks = discovered,
            discoveryRate = if (uniqueIds.isEmpty()) 0f else discovered.toFloat() / uniqueIds.size,
            topSkipped = topSkipped,
            favoriteCount = tracks.count { it.isFavorite }
        )
    }

    private fun startOfDay(timeMs: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
