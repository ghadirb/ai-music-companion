package com.ghadirb.aimusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRestoreTest {

    private fun saved(ids: List<Long>, index: Int, pos: Long = 42_000L) =
        SavedPlayback(trackIds = ids, index = index, positionMs = pos, shuffle = false, repeatMode = 0)

    @Test fun `everything available keeps queue index and position`() {
        val plan = SessionRestore.plan(saved(listOf(1, 2, 3, 4), index = 2)) { true }!!
        assertEquals(listOf(1L, 2L, 3L, 4L), plan.keptIds)
        assertEquals(2, plan.index)
        assertEquals(42_000L, plan.positionMs)
    }

    @Test fun `missing track before current shifts the index but keeps position`() {
        val plan = SessionRestore.plan(saved(listOf(1, 2, 3, 4), index = 2)) { it != 1L }!!
        assertEquals(listOf(2L, 3L, 4L), plan.keptIds)
        assertEquals(1, plan.index) // still track 3
        assertEquals(42_000L, plan.positionMs)
    }

    @Test fun `missing current track continues with next survivor from the start`() {
        val plan = SessionRestore.plan(saved(listOf(1, 2, 3, 4), index = 1)) { it != 2L }!!
        assertEquals(listOf(1L, 3L, 4L), plan.keptIds)
        assertEquals(1, plan.index) // track 3
        assertEquals(0L, plan.positionMs) // the old position belonged to the vanished track
    }

    @Test fun `missing current and all later tracks falls back to the first survivor`() {
        val plan = SessionRestore.plan(saved(listOf(1, 2, 3), index = 1)) { it == 1L }!!
        assertEquals(listOf(1L), plan.keptIds)
        assertEquals(0, plan.index)
        assertEquals(0L, plan.positionMs)
    }

    @Test fun `nothing left means nothing to restore`() {
        assertNull(SessionRestore.plan(saved(listOf(1, 2), index = 0)) { false })
    }

    @Test fun `out of range saved index never crashes`() {
        val plan = SessionRestore.plan(saved(listOf(1, 2), index = 9)) { true }!!
        assertEquals(listOf(1L, 2L), plan.keptIds)
        assertEquals(0, plan.index)
        assertEquals(0L, plan.positionMs)
    }

    @Test fun `negative saved position is clamped`() {
        val plan = SessionRestore.plan(saved(listOf(1), index = 0, pos = -5L)) { true }!!
        assertEquals(0L, plan.positionMs)
    }

    @Test fun `only the current track and a short look-ahead get a real file check`() {
        val s = saved((1L..10L).toList(), index = 4)
        assertFalse(SessionRestore.needsFileCheck(s, 4L)) // before the current one
        assertTrue(SessionRestore.needsFileCheck(s, 5L)) // current
        assertTrue(SessionRestore.needsFileCheck(s, 8L)) // current + 3
        assertFalse(SessionRestore.needsFileCheck(s, 9L))
        assertFalse(SessionRestore.needsFileCheck(s, 99L)) // unknown id
    }
}
