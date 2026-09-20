package com.ghadirb.aimusic.mix

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMixGeneratorTest {

    private val tracks = listOf(
        track(1, fav = true, energy = 0.2f, mood = "calm"),
        track(2, energy = 0.9f, mood = "energetic"),
        track(3, energy = 0.35f, mood = "neutral", dur = 240_000),
        track(4, fav = true, energy = 0.8f),
        track(5)
    )
    private val history = listOf(play(1, 100.0), play(2, 1.0), play(2, 2.0), play(4, 3.0))

    private fun mix(type: MixType) = SmartMixGenerator.generate(type, tracks, history, NOW, zone = UTC).map { it.track.id }

    @Test fun myFavoritesContainsOnlyFavorites() = assertEquals(setOf(1L, 4L), mix(MixType.MY_FAVORITES).toSet())

    @Test fun recentlyLovedNeedsRecentCompletions() = assertEquals(setOf(2L, 4L), mix(MixType.RECENTLY_LOVED).toSet())

    @Test fun rediscoverContainsLikedTracksNotPlayedForLong() = assertEquals(listOf(1L), mix(MixType.REDISCOVER))

    @Test fun chillIsLowEnergyOrCalm() = assertEquals(setOf(1L, 3L), mix(MixType.CHILL).toSet())

    @Test fun energeticIsHighEnergyOrEnergeticMood() = assertEquals(setOf(2L, 4L), mix(MixType.ENERGETIC).toSet())

    @Test fun focusNeedsCalmishMidLowEnergyAndLength() = assertEquals(setOf(1L, 3L), mix(MixType.FOCUS).toSet())

    @Test fun nightIncludesCalmAndLowEnergy() = assertTrue(mix(MixType.NIGHT).containsAll(listOf(1L, 3L)))

    @Test fun randomMixIsDeterministicPerDayAndBounded() {
        val a = SmartMixGenerator.generate(MixType.RANDOM_FROM_TASTE, tracks, history, NOW, limit = 3, zone = UTC)
        val b = SmartMixGenerator.generate(MixType.RANDOM_FROM_TASTE, tracks, history, NOW, limit = 3, zone = UTC)
        assertEquals(a.map { it.track.id }, b.map { it.track.id })
        assertEquals(3, a.size)
    }

    @Test fun generateAllSkipsEmptyMixesAndHandlesEmptyLibrary() {
        assertTrue(SmartMixGenerator.generateAll(emptyList(), emptyList(), NOW).isEmpty())
        val all = SmartMixGenerator.generateAll(tracks, history, NOW, zone = UTC)
        assertTrue(all.all { it.tracks.isNotEmpty() })
        assertTrue(all.any { it.type == MixType.MY_FAVORITES })
    }
}
