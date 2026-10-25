package com.ghadirb.aimusic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsSettingsTest {
    private val fiveBands = listOf(60, 230, 910, 3600, 14000)   // typical Android 5-band equaliser

    // ---- presets ----
    @Test fun flatPresetIsAllZero() =
        assertEquals(List(5) { 0 }, EqPresets.gainsMb(EqPresets.byId(0)!!, fiveBands, -1500, 1500))

    @Test fun bassPresetBoostsLowsMoreThanHighs() {
        val g = EqPresets.gainsMb(EqPresets.byId(1)!!, fiveBands, -1500, 1500)
        assertTrue(g.first() > 0)
        assertTrue(g.first() > g.last())
    }

    @Test fun gainsAreClampedToDeviceRange() {
        val g = EqPresets.gainsMb(EqPresets.byId(1)!!, fiveBands, -300, 300)
        assertTrue(g.all { it in -300..300 })
        assertEquals(300, g.first())
    }

    @Test fun interpolationIsBetweenAnchorsAndFlatOutside() {
        val a = listOf(100 to 0f, 10000 to 10f)
        assertEquals(0f, EqPresets.dbAt(a, 50), 0.001f)
        assertEquals(10f, EqPresets.dbAt(a, 20000), 0.001f)
        val mid = EqPresets.dbAt(a, 1000)           // geometric middle of 100..10000
        assertEquals(5f, mid, 0.01f)
    }

    @Test fun emptyAnchorsAreFlat() = assertEquals(0f, EqPresets.dbAt(emptyList(), 500), 0f)
    @Test fun presetIdsAreUnique() = assertEquals(EqPresets.ALL.size, EqPresets.ALL.map { it.id }.distinct().size)
    @Test fun worksWithOtherBandCounts() {
        assertEquals(10, EqPresets.gainsMb(EqPresets.byId(4)!!, listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000), -1200, 1200).size)
        assertEquals(0, EqPresets.gainsMb(EqPresets.byId(4)!!, emptyList(), -1200, 1200).size)
    }

    // ---- settings ----
    @Test fun roundTrip() {
        val s = AudioEffectsSettings(true, 4, listOf(100, -200, 0, 300, 50), 400, 250, 700, true)
        assertEquals(s, AudioEffectsSettings.decode(s.encode()))
    }
    @Test fun emptyBandsRoundTrip() {
        val s = AudioEffectsSettings(enabled = true)
        assertEquals(s, AudioEffectsSettings.decode(s.encode()))
    }
    @Test fun garbageDecodesToDefaults() {
        assertEquals(AudioEffectsSettings(), AudioEffectsSettings.decode("nonsense"))
        assertEquals(AudioEffectsSettings(), AudioEffectsSettings.decode(null))
        assertEquals(AudioEffectsSettings(), AudioEffectsSettings.decode("1;x;;1;1;1;1"))
    }
    @Test fun outOfRangeValuesAreClamped() {
        val s = AudioEffectsSettings(bassStrength = 5000, virtualizerStrength = -4, loudnessMb = 99999).sanitized()
        assertEquals(1000, s.bassStrength); assertEquals(0, s.virtualizerStrength); assertEquals(AudioEffectsSettings.MAX_LOUDNESS_MB, s.loudnessMb)
    }
    @Test fun savedBandsWinWhenCountMatches() {
        val s = AudioEffectsSettings(preset = 1, bandGainsMb = listOf(1, 2, 3, 4, 5))
        assertEquals(listOf(1, 2, 3, 4, 5), s.gainsFor(fiveBands, -1500, 1500))
    }
    @Test fun presetUsedWhenBandCountDiffers() {
        val s = AudioEffectsSettings(preset = 1, bandGainsMb = listOf(1, 2, 3))
        assertEquals(5, s.gainsFor(fiveBands, -1500, 1500).size)
        assertTrue(s.gainsFor(fiveBands, -1500, 1500).first() > 100)
    }
    @Test fun customWithoutBandsIsFlat() =
        assertEquals(List(5) { 0 }, AudioEffectsSettings(preset = EqPresets.CUSTOM).gainsFor(fiveBands, -1500, 1500))

    // ---- A-B loop ----
    @Test fun loopNeedsBothPointsInOrder() {
        assertFalse(AbLoop().active)
        assertFalse(AbLoop(1000, null).active)
        assertFalse(AbLoop(5000, 1000).active)
        assertTrue(AbLoop(1000, 5000).active)
    }
    @Test fun setBWithoutAIsIgnored() = assertEquals(AbLoop(), AbLoopLogic.setB(AbLoop(), 5000))
    @Test fun tooShortLoopIsIgnored() {
        val a = AbLoopLogic.setA(AbLoop(), 1000)
        assertEquals(a, AbLoopLogic.setB(a, 1200))
    }
    @Test fun goodLoopIsStored() = assertEquals(AbLoop(1000, 4000), AbLoopLogic.setB(AbLoopLogic.setA(AbLoop(), 1000), 4000))
    @Test fun movingAPastBClearsB() = assertNull(AbLoopLogic.setA(AbLoop(1000, 4000), 9000).bMs)
    @Test fun movingAKeepsFarEnoughB() = assertEquals(4000L, AbLoopLogic.setA(AbLoop(1000, 4000), 2000).bMs)
    @Test fun seekBackOnlyAtOrAfterB() {
        val loop = AbLoop(1000, 4000)
        assertNull(AbLoopLogic.seekTarget(loop, 3999))
        assertEquals(1000L, AbLoopLogic.seekTarget(loop, 4000))
        assertEquals(1000L, AbLoopLogic.seekTarget(loop, 4500))
        assertNull(AbLoopLogic.seekTarget(AbLoop(), 99999))
    }
}
