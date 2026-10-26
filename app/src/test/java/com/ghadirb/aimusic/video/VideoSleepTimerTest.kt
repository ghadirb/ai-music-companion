package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSleepTimerTest {
    @Test fun startComputesEndTime() {
        val m = VideoSleepTimer.start(30, 1_000L) as SleepMode.Minutes
        assertEquals(1_000L + 30 * 60_000L, m.endsAtMs)
    }
    @Test fun nonPositiveMinutesMeansOff() = assertEquals(SleepMode.Off, VideoSleepTimer.start(0, 5))
    @Test fun remainingNeverNegative() {
        val m = SleepMode.Minutes(1_000)
        assertEquals(400L, VideoSleepTimer.remainingMs(m, 600))
        assertEquals(0L, VideoSleepTimer.remainingMs(m, 5_000))
        assertEquals(0L, VideoSleepTimer.remainingMs(SleepMode.Off, 5_000))
    }
    @Test fun offNeverPauses() = assertFalse(VideoSleepTimer.shouldPause(SleepMode.Off, 0, 0, 999, 1000))
    @Test fun countdownPausesOnlyWhenTimeIsUp() {
        val m = SleepMode.Minutes(1_000)
        assertFalse(VideoSleepTimer.shouldPause(m, 999, 0, 0, 10_000))
        assertTrue(VideoSleepTimer.shouldPause(m, 1_000, 0, 0, 10_000))
    }
    @Test fun endOfVideoPausesNearTheEnd() {
        val m = SleepMode.EndOfVideo(2)
        assertFalse(VideoSleepTimer.shouldPause(m, 0, 2, 5_000, 10_000))
        assertTrue(VideoSleepTimer.shouldPause(m, 0, 2, 9_200, 10_000))
    }
    @Test fun endOfVideoPausesIfQueueMovedOn() = assertTrue(VideoSleepTimer.shouldPause(SleepMode.EndOfVideo(2), 0, 3, 100, 10_000))
    @Test fun unknownDurationDoesNotPauseEarly() = assertFalse(VideoSleepTimer.shouldPause(SleepMode.EndOfVideo(0), 0, 0, 5_000, 0))
    @Test fun formatting() {
        assertEquals("01:15", VideoSleepTimer.formatRemaining(75_000))
        assertEquals("1:02:05", VideoSleepTimer.formatRemaining(3_725_000))
        assertEquals("00:00", VideoSleepTimer.formatRemaining(-5))
        assertEquals("00:01", VideoSleepTimer.formatRemaining(1))
    }
}
