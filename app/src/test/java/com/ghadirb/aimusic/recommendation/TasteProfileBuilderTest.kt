package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileBuilderTest {

    private val tracks = listOf(
        track(1, artist = "Top", genre = "Pop", energy = 0.3f, mood = "calm", bpm = 90, fav = true),
        track(2, artist = "Top", genre = "Pop", energy = 0.5f, mood = "calm", bpm = 110),
        track(3, artist = "Mid", genre = "Rock", energy = 0.9f, mood = "energetic", bpm = 150),
        track(4, artist = "Unplayed", genre = "Jazz")
    )
    private val history = (1..5).map { play(1, it.toDouble()) } + (1..3).map { play(2, it.toDouble()) } +
        listOf(play(3, 1.0), play(3, 2.0, completed = 0.1f, skipped = true))

    private val profile = TasteProfileBuilder.build(tracks, history, NOW, zone = UTC)

    @Test fun extractsTopArtistsGenresAndMoods() {
        assertEquals("Top", ListCodec.decode(profile.favoriteArtists).first())
        assertEquals("Pop", ListCodec.decode(profile.favoriteGenres).first())
        assertEquals("calm", ListCodec.decode(profile.favoriteMoods).first())
    }

    @Test fun computesBehaviourMetrics() {
        assertEquals(1f / 10f, profile.skipRate, 0.001f)
        assertEquals(0.25f, profile.favoriteRatio, 0.001f)
        assertTrue(profile.preferredBpm in 85..130)
        assertTrue(profile.energyRange.contains("-"))
        assertEquals("1", profile.topTrackIds.split(',').first())
        assertTrue(profile.peakHours.isNotBlank())
        assertEquals(NOW, profile.updatedAt)
    }

    @Test fun emptyLibraryGivesEmptyProfile() {
        val empty = TasteProfileBuilder.build(emptyList(), emptyList(), NOW)
        assertEquals("", empty.favoriteArtists)
        assertEquals(0, empty.preferredBpm)
    }

    @Test fun favoritesAloneProduceProfile() {
        val p = TasteProfileBuilder.build(listOf(track(1, artist = "F", fav = true)), emptyList(), NOW)
        assertEquals(listOf("F"), ListCodec.decode(p.favoriteArtists))
    }

    @Test fun listCodecKeepsCommasAndReadsLegacy() {
        assertEquals(listOf("Doe, John", "B"), ListCodec.decode(ListCodec.encode(listOf("Doe, John", "B"))))
        assertEquals(listOf("A", "B"), ListCodec.decode("A,B"))
        assertTrue(ListCodec.decode("").isEmpty())
    }
}
