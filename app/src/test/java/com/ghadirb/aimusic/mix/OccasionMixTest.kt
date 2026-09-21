package com.ghadirb.aimusic.mix

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OccasionMixTest {

    private val tracks = listOf(
        track(1, energy = 0.9f, mood = "energetic", bpm = 140),                      // gym
        track(2, energy = 0.7f, mood = "happy", bpm = 120, dur = 200_000),           // road-trip / happy
        track(3, energy = 0.2f, mood = "sad", bpm = 70),                             // sad
        track(4, energy = 0.25f, mood = "calm", bpm = 65),                           // calm / night / focus
        track(5, energy = 0.55f, mood = "neutral", bpm = 100),                       // morning / driving
        track(6, energy = 0.95f, mood = "energetic", bpm = 170, dur = 60_000),       // too short for workout/driving
        track(7)                                                                     // not analysed yet
    )

    private fun mix(type: MixType) = SmartMixGenerator.generate(type, tracks, emptyList(), NOW, zone = UTC).map { it.track.id }.toSet()

    @Test fun workoutIsHighEnergyNotSadAndLongEnough() = assertEquals(setOf(1L, 2L), mix(MixType.WORKOUT))

    @Test fun drivingWantsMidTempoTracksOfReasonableLength() = assertEquals(setOf(1L, 2L, 5L), mix(MixType.DRIVING)) // 6 is too short, 3/4 are slow

    @Test fun happyAndSadFollowMood() {
        assertTrue(mix(MixType.HAPPY).containsAll(listOf(2L)))
        assertFalse(3L in mix(MixType.HAPPY))
        assertEquals(setOf(3L), mix(MixType.SAD))
    }

    @Test fun morningIsGentleButAwake() = assertEquals(setOf(2L, 5L), mix(MixType.MORNING))

    @Test fun unanalysedTracksNeverAppearInOccasionMixes() {
        MixType.values().filter { it.occasion }.forEach { assertFalse("${it.name} contains unanalysed track", 7L in mix(it)) }
    }

    @Test fun everyOccasionHasAPersianTitleAndEmoji() {
        MixType.values().forEach { assertTrue(it.titleFa.isNotBlank() && it.emoji.isNotBlank()) }
        assertEquals(setOf("WORKOUT", "DRIVING", "HAPPY", "CHILL", "FOCUS", "NIGHT", "SAD", "MORNING", "ENERGETIC"),
            MixType.values().filter { it.occasion }.map { it.name }.toSet())
    }

    @Test fun suggestedOccasionFollowsTimeOfDay() {
        assertEquals(MixType.MORNING, MixType.suggestedFor("morning"))
        assertEquals(MixType.NIGHT, MixType.suggestedFor("night"))
    }
}

class PersonalMixTest {
    private fun mix(type: MixType, tracks: List<com.ghadirb.aimusic.data.local.entity.TrackEntity>, history: List<com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity> = emptyList()) =
        SmartMixGenerator.generate(type, tracks, history, NOW, zone = UTC).map { it.track.id }

    @Test fun persianMixUsesOnlyPersianScriptTracks() {
        val tracks = listOf(track(1, title = "دلتنگی", artist = "Someone"), track(2, title = "Song", artist = "علی"), track(3, title = "English", artist = "Band"))
        assertEquals(setOf(1L, 2L), mix(MixType.PERSIAN_MIX, tracks).toSet())
    }

    @Test fun hiddenGemsAreRarelyHeardTracksThatMatchTheTaste() {
        val tracks = listOf(
            track(1, artist = "Loved", genre = "Rock"), track(2, artist = "Loved", genre = "Rock"), // 2: same artist, never played
            track(3, artist = "Other", genre = "Jazz"),                                            // unrelated: not a gem
            track(4, artist = "Loved", genre = "Rock", fav = true)                                  // favourites are not "hidden"
        )
        val history = (1..6).map { com.ghadirb.aimusic.TestData.play(1, it.toDouble()) }
        assertEquals(setOf(2L), mix(MixType.HIDDEN_GEMS, tracks, history).toSet())
    }

    @Test fun morningMixAdaptsToWhenTheUserReallyListens() {
        // NOW is 08:00 UTC; the user plays a "not morning-like" high-energy track every morning.
        val loud = track(1, energy = 0.95f, mood = "energetic")
        val gentle = track(2, energy = 0.5f, mood = "neutral")
        val history = (1..3).map { com.ghadirb.aimusic.TestData.play(1, it.toDouble()) }
        val ids = mix(MixType.MORNING, listOf(loud, gentle), history)
        assertTrue(1L in ids)          // included because of real behaviour
        assertEquals(1L, ids.first())  // and favoured
        assertTrue(2L in ids)          // rule-based candidate still present
    }
}
