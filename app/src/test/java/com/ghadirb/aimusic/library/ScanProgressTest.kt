package com.ghadirb.aimusic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanProgressTest {
    @Test fun percentIsClampedAndNeverDividesByZero() {
        assertEquals(0, ScanProgress(0, 0).percent)
        assertFalse(ScanProgress(5, 0).known)
        assertEquals(50, ScanProgress(50, 100).percent)
        assertEquals(100, ScanProgress(120, 100).percent)      // more rows than expected still tops out at 100
        assertEquals(0, ScanProgress(-4, 100).percent)
        assertEquals(33, ScanProgress(1, 3).percent)
        assertTrue(ScanProgress(1, 3).known)
    }

    @Test fun reportsInStepsAndAlwaysTheLastRow() {
        val reported = (1..120).filter { ScanProgress.shouldReport(it, 120) }
        assertEquals(listOf(50, 100, 120), reported)
        assertEquals(listOf(7), (1..7).filter { ScanProgress.shouldReport(it, 7) })   // tiny library: only the final row
        assertTrue(ScanProgress.shouldReport(100, 100))
    }
}
