package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/**
 * One row of the history screen: a track, how often it was listened to, and when it was last played.
 * [partial] = this session was left before the track finished (skipped); [completedPercentage] is how much was heard.
 */
data class HistoryEntry(
    val track: TrackEntity,
    val plays: Int,
    val lastPlayedAt: Long,
    val partial: Boolean = false,
    val completedPercentage: Float = 1f
)

/**
 * Pure (Android-free, unit-testable) aggregation behind the History screen.
 *
 * A session counts as "listened" when it was finished/completed OR the user heard at least
 * [MIN_LISTEN_MS] before skipping. Accidental taps (skipped within a few seconds) are ignored.
 * Tracks that no longer exist in the library are dropped.
 */
object HistoryAggregator {
    const val MIN_LISTEN_MS = 10_000L

    private fun ListeningHistoryEntity.counts() = !skipped || listenDurationMs >= MIN_LISTEN_MS

    /** Latest listened sessions, newest first. */
    fun recent(history: List<ListeningHistoryEntity>, tracks: Map<Long, TrackEntity>, limit: Int = 100): List<HistoryEntry> {
        val listened = history.filter { it.counts() }
        val counts = listened.groupingBy { it.trackId }.eachCount()
        return listened
            .sortedByDescending { it.startTime }
            .mapNotNull { e ->
                tracks[e.trackId]?.let {
                    HistoryEntry(it, counts[e.trackId] ?: 1, e.startTime, partial = e.skipped, completedPercentage = e.completedPercentage)
                }
            }
            .take(limit)
    }

    /** Most-listened tracks (ties broken by most recent), each with its count and last play time. */
    fun top(history: List<ListeningHistoryEntity>, tracks: Map<Long, TrackEntity>, limit: Int = 50): List<HistoryEntry> {
        val byTrack = history.filter { it.counts() }.groupBy { it.trackId }
        return byTrack.entries
            .mapNotNull { (id, sessions) ->
                tracks[id]?.let { HistoryEntry(it, sessions.size, sessions.maxOf { s -> s.startTime }) }
            }
            .sortedWith(compareByDescending<HistoryEntry> { it.plays }.thenByDescending { it.lastPlayedAt })
            .take(limit)
    }
}
