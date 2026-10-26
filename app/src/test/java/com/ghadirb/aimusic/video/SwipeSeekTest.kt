package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeSeekTest {
    @Test fun fullWidthSwipeMovesNinetySeconds() {
        assertEquals(190_000L, SwipeSeek.target(100_000L, 3_600_000L, 1000f, 1000f))
        assertEquals(10_000L, SwipeSeek.target(100_000L, 3_600_000L, -1000f, 1000f))
    }

    @Test fun halfWidthMovesHalfAsFar() {
        assertEquals(145_000L, SwipeSeek.target(100_000L, 3_600_000L, 500f, 1000f))
    }

    @Test fun clampsAtStartAndNearEnd() {
        assertEquals(0L, SwipeSeek.target(5_000L, 600_000L, -1000f, 1000f))
        assertEquals(599_000L, SwipeSeek.target(590_000L, 600_000L, 1000f, 1000f))
    }

    @Test fun unknownDurationOrWidthDoesNotSeek() {
        assertEquals(42_000L, SwipeSeek.target(42_000L, 0L, 500f, 1000f))
        assertEquals(42_000L, SwipeSeek.target(42_000L, 600_000L, 500f, 0f))
    }

    @Test fun formatsTimeAndDelta() {
        assertEquals("01:05", SwipeSeek.format(65_000L))
        assertEquals("1:01:05", SwipeSeek.format(3_665_000L))
        assertEquals("+00:12", SwipeSeek.formatDelta(12_000L))
        assertEquals("-00:05", SwipeSeek.formatDelta(-5_000L))
    }
}
