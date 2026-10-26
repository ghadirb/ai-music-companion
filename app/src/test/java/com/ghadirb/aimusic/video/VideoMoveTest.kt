package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMoveTest {
    // ---- folder paths ----
    @Test fun emptyMeansMoviesRoot() = assertEquals("Movies/", VideoFolderPaths.relativePath("  "))
    @Test fun simpleFolder() = assertEquals("Movies/Family/", VideoFolderPaths.relativePath("Family"))
    @Test fun nestedAndBackslashes() = assertEquals("Movies/Family/2025/", VideoFolderPaths.relativePath("Family\\2025/"))
    @Test fun typingMoviesPrefixIsNotDuplicated() = assertEquals("Movies/Trips/", VideoFolderPaths.relativePath("movies/Trips"))
    @Test fun parentTraversalRejected() = assertNull(VideoFolderPaths.relativePath("../DCIM"))
    @Test fun forbiddenCharsRejected() = assertNull(VideoFolderPaths.relativePath("a:b"))
    @Test fun persianFolderOk() = assertEquals("Movies/خانواده/", VideoFolderPaths.relativePath("خانواده"))
    @Test fun displayName() {
        assertEquals("Family", VideoFolderPaths.displayName("Movies/Family/"))
        assertEquals("Movies", VideoFolderPaths.displayName("Movies/"))
    }

    // ---- carry-over after a copy+delete move (new id) ----
    private fun v(id: Long, name: String, size: Long = 1000, added: Long = id) = VideoEntity(
        id = id, contentUri = "content://v/$id", displayName = name, durationMs = 1, sizeBytes = size,
        width = 0, height = 0, mimeType = "video/mp4", folderName = "", relativePath = "", dateAdded = added, dateModified = added
    )
    private val pending = VideoMoveCarryOver.Pending(5, "Trip.mkv", 1000, 4000, 99, true, 0)

    @Test fun matchesSameNameAndSizeWithNewId() = assertEquals(9L, VideoMoveCarryOver.match(pending, listOf(v(9, "Trip.mkv")))?.id)
    @Test fun matchesRenamedCopySuffix() = assertEquals(9L, VideoMoveCarryOver.match(pending, listOf(v(9, "Trip (2).mkv")))?.id)
    @Test fun differentSizeDoesNotMatch() = assertNull(VideoMoveCarryOver.match(pending, listOf(v(9, "Trip.mkv", size = 5))))
    @Test fun differentNameDoesNotMatch() = assertNull(VideoMoveCarryOver.match(pending, listOf(v(9, "Other.mkv"))))
    @Test fun oldIdIsNeverTheMatch() = assertNull(VideoMoveCarryOver.match(pending, listOf(v(5, "Trip.mkv"))))
    @Test fun newestCopyWinsWhenSeveral() =
        assertEquals(11L, VideoMoveCarryOver.match(pending, listOf(v(9, "Trip.mkv", added = 1), v(11, "Trip.mkv", added = 5)))?.id)

    @Test fun expiry() {
        assertFalse(VideoMoveCarryOver.isExpired(pending, 1000))
        assertTrue(VideoMoveCarryOver.isExpired(pending, VideoMoveCarryOver.EXPIRE_MS + 1))
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(pending, pending.copy(oldId = 6, name = "a\tb\nc.mp4", favorite = false))
        val back = VideoMoveCarryOver.decode(VideoMoveCarryOver.encode(list))
        assertEquals(2, back.size)
        assertEquals(pending, back[0])
        assertEquals("a b c.mp4", back[1].name)
        assertFalse(back[1].favorite)
    }

    @Test fun garbageLinesAreIgnored() = assertTrue(VideoMoveCarryOver.decode("x\ny\tz").isEmpty())
    @Test fun emptyTextDecodesToNothing() = assertTrue(VideoMoveCarryOver.decode("").isEmpty())
}
