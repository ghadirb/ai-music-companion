package com.ghadirb.aimusic.radio

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RadioEngineTest {

    private val noExplore = RadioConfig(explorationRate = 0.0)

    private fun batch(
        seeds: List<TrackEntity>, library: List<TrackEntity>, history: List<ListeningHistoryEntity> = emptyList(),
        queued: List<Long> = emptyList(), count: Int = 5, config: RadioConfig = noExplore
    ) = RadioEngine.nextBatch(seeds, queued, library, history, NOW, count, config, Random(1), UTC).map { it.track.id }

    private val seed = track(1, artist = "A", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140)

    @Test fun neverRepeatsQueuedOrSeedTracks() {
        val library = listOf(seed) + (2L..12L).map { track(it, artist = "X$it", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140) }
        val ids = batch(listOf(seed), library, queued = listOf(1, 2, 3), count = 6)
        assertEquals(6, ids.size)
        assertTrue(ids.none { it in listOf(1L, 2L, 3L) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun similarTracksBeatUnrelatedOnes() {
        val library = listOf(
            seed,
            track(2, artist = "B", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 138),
            track(3, artist = "C", genre = "Classical", energy = 0.1f, mood = "calm", bpm = 60)
        )
        assertEquals(2L, batch(listOf(seed), library, count = 1).first())
    }

    @Test fun favoritesAreBoostedAndRecentlyPlayedAreDemoted() {
        val library = listOf(
            seed,
            track(2, artist = "B", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140),
            track(3, artist = "C", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140, fav = true),
            track(4, artist = "D", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140)
        )
        val history = listOf(play(4, 0.01)) // played a few minutes ago
        val ids = batch(listOf(seed), library, history, count = 3)
        assertEquals(3L, ids.first())
        assertEquals(4L, ids.last())
    }

    @Test fun repeatedlySkippedTracksAreAvoidedUntilNothingElseIsLeft() {
        val library = listOf(
            seed,
            track(2, artist = "B", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140),
            track(3, artist = "C", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140)
        )
        val skips = (1..4).map { play(2, it.toDouble(), completed = 0.05f, skipped = true) }
        assertEquals(listOf(3L), batch(listOf(seed), library, skips, count = 1))
        // relaxation: asking for more than the clean candidates still returns the skipped one last
        assertEquals(listOf(3L, 2L), batch(listOf(seed), library, skips, count = 2))
    }

    @Test fun sameArtistIsNotPlayedBackToBackWhenAlternativesExist() {
        val library = listOf(seed) +
            (2L..5L).map { track(it, artist = "Same", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140) } +
            (6L..9L).map { track(it, artist = "Other$it", genre = "Rock", energy = 0.75f, mood = "energetic", bpm = 135) }
        val picks = RadioEngine.nextBatch(listOf(seed), emptyList(), library, emptyList(), NOW, 4, noExplore, Random(1), UTC).map { it.track.artist }
        assertTrue("artists: $picks", picks.zipWithNext().none { (a, b) -> a == b })
    }

    @Test fun energyFlowsInSmallSteps() {
        val low = track(2, artist = "L", genre = "Pop", energy = 0.2f, mood = "energetic", bpm = 140)
        val near = track(3, artist = "N", genre = "Pop", energy = 0.75f, mood = "energetic", bpm = 140)
        val ids = batch(listOf(seed), listOf(seed, low, near), count = 1)
        assertEquals(3L, ids.first())
    }

    @Test fun relaxationStillFillsTheBatchForUnrelatedLibraries() {
        val library = listOf(seed) + (2L..8L).map { track(it, artist = "X", genre = "Jazz", energy = 0.1f, mood = "calm", bpm = 60) }
        val ids = batch(listOf(seed), library, count = 5)
        assertEquals(5, ids.size) // similarity/artist limits were relaxed step by step
    }

    @Test fun explorationInjectsNeverPlayedTracksButIsDeterministic() {
        val library = listOf(seed) + (2L..30L).map { track(it, artist = "A$it", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140) }
        val history = (2L..10L).flatMap { id -> (1..3).map { play(id, it.toDouble() + 2) } }
        val cfg = RadioConfig(explorationRate = 1.0)
        val a = batch(listOf(seed), library, history, config = cfg)
        val b = batch(listOf(seed), library, history, config = cfg)
        assertEquals(a, b)
        assertTrue(a.any { it > 10L }) // includes tracks that were never played
    }

    @Test fun emptyInputsAreSafe() {
        assertTrue(batch(emptyList(), listOf(seed)).isEmpty())
        assertTrue(batch(listOf(seed), emptyList()).isEmpty())
        assertTrue(batch(listOf(seed), listOf(seed)).isEmpty()) // only the seed exists
    }

    @Test fun coefficientsAreConfigurable() {
        val library = listOf(
            seed,
            track(2, artist = "B", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140),
            track(3, artist = "C", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140, fav = true)
        )
        assertEquals(3L, batch(listOf(seed), library, count = 1, config = noExplore.copy(favoriteBoost = 5.0)).first())
        assertEquals(2L, batch(listOf(seed), library, count = 1, config = noExplore.copy(favoriteBoost = -5.0)).first())
    }

    @Test fun helperCompatibilityFunctionsAreBounded() {
        assertEquals(1.0, RadioEngine.moodCompatibility("calm", "calm"), 0.0)
        assertEquals(0.6, RadioEngine.moodCompatibility("calm", "neutral"), 0.0)
        assertEquals(0.0, RadioEngine.moodCompatibility("calm", "energetic"), 0.0)
        assertEquals(0.5, RadioEngine.moodCompatibility(null, "calm"), 0.0)
        assertFalse(RadioEngine.bpmCompatibility(100.0, 400) < 0)
        assertEquals(1.0, RadioEngine.energyContinuity(0.5f, 0.5f), 0.0)
    }
}
