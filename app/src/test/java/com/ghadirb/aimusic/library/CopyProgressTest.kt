package com.ghadirb.aimusic.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyProgressTest {
    @Test fun percentOfBasics() {
        assertEquals(0, CopyProgress.percentOf(0, 100))
        assertEquals(50, CopyProgress.percentOf(50, 100))
        assertEquals(100, CopyProgress.percentOf(100, 100))
    }
    @Test fun unknownTotalIsZeroNotCrash() = assertEquals(0, CopyProgress.percentOf(10, 0))
    @Test fun overshootIsCapped() = assertEquals(100, CopyProgress.percentOf(500, 100))
    @Test fun bigFilesDoNotOverflow() = assertEquals(50, CopyProgress.percentOf(2_000_000_000_000L, 4_000_000_000_000L))
    @Test fun percentProperty() = assertEquals(25, CopyProgress(25, 100, 1, 1, "a").percent)
}
