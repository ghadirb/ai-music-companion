package com.ghadirb.aimusic.history

import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.stats.HistoryAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAggregatorTest {

    private val tracks = listOf(track(1), track(2), track(3)).associateBy { it.id }

    /** A session the user left after [ms] milliseconds (skipped flag set, like ListeningRecorder does < 80%). */
    private fun skippedAfter(id: Long, daysAgo: Double, ms: Long) =
        play(id, daysAgo, completed = 0.2f, skipped = true).copy(listenDurationMs = ms)

    @Test fun recentIsNewestFirstAndIgnoresMissingTracks() {
        val history = listOf(play(1, daysAgo = 3.0), play(2, daysAgo = 1.0), play(99, daysAgo = 0.1))
        val rows = HistoryAggregator.recent(history, tracks)
        assertEquals(listOf(2L, 1L), rows.map { it.track.id })
        assertTrue(rows[0].lastPlayedAt > rows[1].lastPlayedAt)
    }

    @Test fun skippedTrackAfterRealListeningStillShowsInHistoryAsPartial() {
        // The user played a song for 40 s and pressed next: it must appear (bug: it used to be hidden).
        val rows = HistoryAggregator.recent(listOf(skippedAfter(1, 0.1, 40_000)), tracks)
        assertEquals(1, rows.size)
        assertTrue(rows[0].partial)
    }

    @Test fun accidentalTapSkippedWithinSecondsIsIgnored() {
        assertTrue(HistoryAggregator.recent(listOf(skippedAfter(1, 0.1, 2_000)), tracks).isEmpty())
        assertTrue(HistoryAggregator.top(listOf(skippedAfter(1, 0.1, 2_000)), tracks).isEmpty())
    }

    @Test fun completedSessionIsNotPartial() {
        assertFalse(HistoryAggregator.recent(listOf(play(1, 1.0)), tracks).single().partial)
    }

    @Test fun recentRespectsLimit() {
        val history = (1..10).map { play(1, daysAgo = it.toDouble()) }
        assertEquals(3, HistoryAggregator.recent(history, tracks, limit = 3).size)
    }

    @Test fun topCountsListensAndBreaksTiesByRecency() {
        val history = listOf(
            play(1, 5.0), play(1, 4.0), play(1, 3.0),   // 3 listens
            play(2, 2.0), play(2, 1.0),                 // 2 listens, more recent
            play(3, 6.0), play(3, 7.0),                 // 2 listens, older
            skippedAfter(3, 0.2, 1_000)                 // accidental tap: not counted
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
