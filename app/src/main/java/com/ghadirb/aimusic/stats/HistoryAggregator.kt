package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/** One row of the history screen: a track, how often it was played, and when it was last played. */
data class HistoryEntry(val track: TrackEntity, val plays: Int, val lastPlayedAt: Long)

/**
 * Pure (Android-free, unit-testable) aggregation behind the History screen.
 * Skipped sessions never count as a "play"; tracks that no longer exist in the library are dropped.
 */
object HistoryAggregator {

    /** Latest non-skipped plays, newest first. Each row's [HistoryEntry.lastPlayedAt] is that session's start time. */
    fun recent(history: List<ListeningHistoryEntity>, tracks: Map<Long, TrackEntity>, limit: Int = 100): List<HistoryEntry> {
        val played = history.filter { !it.skipped }
        val counts = played.groupingBy { it.trackId }.eachCount()
        return played
            .sortedByDescending { it.startTime }
            .mapNotNull { e ->
                tracks[e.trackId]?.let { HistoryEntry(it, counts[e.trackId] ?: 1, e.startTime) }
            }
            .take(limit)
    }

    /** Most-played tracks (ties broken by most recent play), each with its play count and last play time. */
    fun top(history: List<ListeningHistoryEntity>, tracks: Map<Long, TrackEntity>, limit: Int = 50): List<HistoryEntry> {
        val played = history.filter { !it.skipped }
        val byTrack = played.groupBy { it.trackId }
        return byTrack.entries
            .mapNotNull { (id, sessions) ->
                tracks[id]?.let { HistoryEntry(it, sessions.size, sessions.maxOf { s -> s.startTime }) }
            }
            .sortedWith(compareByDescending<HistoryEntry> { it.plays }.thenByDescending { it.lastPlayedAt })
            .take(limit)
    }
}
