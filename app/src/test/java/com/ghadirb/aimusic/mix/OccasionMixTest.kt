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
