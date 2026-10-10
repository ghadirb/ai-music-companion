package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFoldersTest {
    private fun v(id: Long, folder: String, path: String = "", dur: Long = 1000L) = VideoEntity(
        id = id, contentUri = "content://v/$id", displayName = "v$id.mp4", durationMs = dur, sizeBytes = 1,
        width = 1, height = 1, mimeType = "video/mp4", folderName = folder, relativePath = path,
        dateAdded = 0, dateModified = 0
    )

    @Test fun groupsByFolderWithCountsAndDuration() {
        val g = VideoFolders.group(listOf(v(1, "Movies", "Movies/", 100), v(2, "Camera", "DCIM/Camera/", 50), v(3, "Movies", "Movies/", 200)))
        assertEquals(listOf("Camera", "Movies"), g.map { it.name })
        assertEquals(2, g.first { it.name == "Movies" }.count)
        assertEquals(300L, g.first { it.name == "Movies" }.totalDurationMs)
    }

    @Test fun sameNameInDifferentPathsStaysSeparate() {
        val g = VideoFolders.group(listOf(v(1, "Downloads", "Download/"), v(2, "Downloads", "Telegram/Download/")))
        assertEquals(2, g.size)
    }

    @Test fun unknownFolderGetsFallbackName() {
        assertEquals(VideoFolders.UNKNOWN_NAME, VideoFolders.group(listOf(v(1, "", ""))).single().name)
    }

    @Test fun nameFallsBackToLastPathSegment() {
        assertEquals("Trips", VideoFolders.group(listOf(v(1, "", "Movies/Trips/"))).single().name)
    }

    @Test fun inFolderFiltersByKey() {
        val list = listOf(v(1, "A", "A/"), v(2, "B", "B/"), v(3, "A", "A/"))
        assertEquals(listOf(1L, 3L), VideoFolders.inFolder(list, "A/").map { it.id })
    }
}
