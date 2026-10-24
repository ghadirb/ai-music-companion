package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaylistLogicTest {
    private fun v(id: Long) = VideoEntity(
        id = id, contentUri = "content://v/$id", displayName = "v$id", durationMs = 1, sizeBytes = 1,
        width = 0, height = 0, mimeType = "video/mp4", folderName = "", relativePath = "", dateAdded = id, dateModified = id
    )
    private fun pl(id: Long) = VideoPlaylistEntity(id, "p$id", 0, 0)
    private fun e(p: Long, v: Long, pos: Int) = VideoPlaylistEntryEntity(p, v, pos, 0)

    @Test fun resolveKeepsManualOrder() {
        val r = VideoPlaylistLogic.resolve(listOf(pl(1)), listOf(e(1, 10, 2), e(1, 11, 0), e(1, 12, 1)), listOf(v(10), v(11), v(12)))
        assertEquals(listOf(11L, 12L, 10L), r.single().videos.map { it.id })
    }

    @Test fun missingVideosAreHiddenNotCounted() {
        val r = VideoPlaylistLogic.resolve(listOf(pl(1)), listOf(e(1, 10, 0), e(1, 99, 1)), listOf(v(10)))
        assertEquals(1, r.single().count)
        assertEquals(10L, r.single().cover?.id)
    }

    @Test fun sameVideoInTwoPlaylists() {
        val r = VideoPlaylistLogic.resolve(listOf(pl(1), pl(2)), listOf(e(1, 10, 0), e(2, 10, 0)), listOf(v(10)))
        assertEquals(listOf(1, 1), r.map { it.count })
    }

    @Test fun emptyPlaylistHasNoCover() {
        assertNull(VideoPlaylistLogic.resolve(listOf(pl(1)), emptyList(), emptyList()).single().cover)
    }

    @Test fun moveWorksAndIgnoresBadIndexes() {
        assertEquals(listOf(2, 1, 3), VideoPlaylistLogic.move(listOf(1, 2, 3), 1, 0))
        assertEquals(listOf(2, 3, 1), VideoPlaylistLogic.move(listOf(1, 2, 3), 0, 2))
        assertEquals(listOf(1, 2, 3), VideoPlaylistLogic.move(listOf(1, 2, 3), 0, 5))
        assertEquals(listOf(1, 2, 3), VideoPlaylistLogic.move(listOf(1, 2, 3), 1, 1))
    }

    @Test fun cleanNameTrimsAndCollapses() {
        assertEquals("فیلم ها", VideoPlaylistLogic.cleanName("  فیلم   ها \n"))
        assertEquals("", VideoPlaylistLogic.cleanName("   "))
        assertEquals(60, VideoPlaylistLogic.cleanName("a".repeat(100)).length)
    }

    @Test fun endOfPlaylistBehaviour() {
        assertEquals(1, VideoPlaylistLogic.nextIndex(0, 3, false))
        assertNull(VideoPlaylistLogic.nextIndex(2, 3, false))
        assertEquals(0, VideoPlaylistLogic.nextIndex(2, 3, true))
        assertNull(VideoPlaylistLogic.nextIndex(0, 0, true))
    }
}
