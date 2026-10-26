package com.ghadirb.aimusic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeLogicTest {
    private val dur = 200_000L

    @Test fun offOrUnknownDurationNeverChangesVolume() {
        assertEquals(1f, FadeLogic.factor(0, dur, 0), 0f)
        assertEquals(1f, FadeLogic.factor(199_999, dur, 0), 0f)
        assertEquals(1f, FadeLogic.factor(5_000, 0L, 5), 0f)
        assertEquals(1f, FadeLogic.factor(5_000, -1L, 5), 0f)
    }

    @Test fun middleOfSongIsFullVolumeAndEndsAreSilentToFull() {
        assertEquals(0f, FadeLogic.factor(0, dur, 5), 0.001f)
        assertEquals(1f, FadeLogic.factor(100_000, dur, 5), 0f)
        assertEquals(0f, FadeLogic.factor(dur, dur, 5), 0.001f)
        assertEquals(0.5f, FadeLogic.factor(2_500, dur, 5), 0.001f)           // half-way through the fade-in
        assertEquals(0.5f, FadeLogic.factor(dur - 2_500, dur, 5), 0.001f)     // half-way through the fade-out
    }

    @Test fun curveRisesMonotonicallyAndStaysInRange() {
        var prev = -1f
        for (pos in 0..5_000 step 100) {
            val f = FadeLogic.factor(pos.toLong(), dur, 5)
            assertTrue(f in 0f..1f)
            assertTrue(f >= prev)
            prev = f
        }
    }

    @Test fun shortSongsUseAtMostAThirdForTheFade() {
        assertEquals(10_000L, FadeLogic.effectiveFadeMs(12, 30_000L))
        assertEquals(1f, FadeLogic.factor(15_000, 30_000L, 12), 0f)           // middle third stays at full volume
        assertEquals(5_000L, FadeLogic.effectiveFadeMs(5, 200_000L))
    }

    @Test fun outOfRangeInputsAreClamped() {
        assertEquals(12_000L, FadeLogic.effectiveFadeMs(99, 600_000L))
        assertEquals(0L, FadeLogic.effectiveFadeMs(-3, 600_000L))
        assertEquals(0f, FadeLogic.factor(-50, dur, 5), 0.001f)
        assertEquals(0f, FadeLogic.factor(dur + 5_000, dur, 5), 0.001f)
    }

    @Test fun fadeSettingRoundTripAndOldFormats() {
        val s = AudioEffectsSettings(enabled = true, replayGain = true, fadeSeconds = 6)
        assertEquals(6, AudioEffectsSettings.decode(s.encode()).fadeSeconds)
        assertEquals(12, AudioEffectsSettings(fadeSeconds = 99).sanitized().fadeSeconds)
        // 8-field string saved before fade existed (with ReplayGain on) still loads, fade off
        val old = AudioEffectsSettings.decode("1;4;;0;0;0;0;1")
        assertEquals(0, old.fadeSeconds); assertTrue(old.replayGain)
        // 7-field string (before ReplayGain) too
        assertEquals(0, AudioEffectsSettings.decode("1;4;;0;0;0;0").fadeSeconds)
        // garbage never crashes and falls back to defaults
        assertEquals(AudioEffectsSettings(), AudioEffectsSettings.decode("1;2;3"))
    }
}
