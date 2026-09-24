package com.ghadirb.aimusic.history

import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.stats.HistoryAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAggregatorTest {

    private val tracks = listOf(track(1), track(2), track(3)).associateBy { it.id }

    @Test fun recentIsNewestFirstAndIgnoresSkipsAndMissingTracks() {
        val history = listOf(
            play(1, daysAgo = 3.0),
            play(2, daysAgo = 1.0),
            play(3, daysAgo = 0.5, skipped = true),   // skipped: not a play
            play(99, daysAgo = 0.1)                   // deleted from library
        )
        val rows = HistoryAggregator.recent(history, tracks)
        assertEquals(listOf(2L, 1L), rows.map { it.track.id })
        assertTrue(rows[0].lastPlayedAt > rows[1].lastPlayedAt)
    }

    @Test fun recentRespectsLimit() {
        val history = (1..10).map { play(1, daysAgo = it.toDouble()) }
        assertEquals(3, HistoryAggregator.recent(history, tracks, limit = 3).size)
    }

    @Test fun topCountsPlaysAndBreaksTiesByRecency() {
        val history = listOf(
            play(1, 5.0), play(1, 4.0), play(1, 3.0),   // 3 plays
            play(2, 2.0), play(2, 1.0),                 // 2 plays, more recent
            play(3, 6.0), play(3, 7.0),                 // 2 plays, older
            play(3, 0.2, skipped = true)                // skip does not count
        )
        val rows = HistoryAggregator.top(history, tracks)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.track.id })
        assertEquals(listOf(3, 2, 2), rows.map { it.plays })
    }

    @Test fun emptyHistoryGivesEmptyLists() {
        assertTrue(HistoryAggregator.recent(emptyList(), tracks).isEmpty())
        assertTrue(HistoryAggregator.top(emptyList(), tracks).isEmpty())
    }
}
