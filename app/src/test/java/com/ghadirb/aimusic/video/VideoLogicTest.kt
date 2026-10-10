package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLogicTest {

    private fun v(
        id: Long, name: String, added: Long = id, dur: Long = 1000, size: Long = 1, played: Long = 0, folder: String = ""
    ) = VideoEntity(
        id = id, contentUri = "content://v/$id", displayName = name, durationMs = dur, sizeBytes = size,
        width = 1920, height = 1080, mimeType = "video/mp4", folderName = folder, relativePath = "",
        dateAdded = added, dateModified = added, lastPlayedAt = played
    )

    // ---- resume ----
    @Test fun resumeKeepsMiddlePosition() {
        // 60 min video, left at minute 23.
        val dur = 60 * 60_000L
        assertEquals(23 * 60_000L, VideoResume.positionToSave(23 * 60_000L, dur))
        assertEquals(23 * 60_000L, VideoResume.startPosition(23 * 60_000L, dur))
    }

    @Test fun resumeResetsPastNinetyFivePercent() {
        val dur = 100_000L
        assertEquals(0L, VideoResume.positionToSave(95_000L, dur))
        assertEquals(0L, VideoResume.positionToSave(99_000L, dur))
        assertEquals(94_000L, VideoResume.positionToSave(94_000L, dur))
        assertEquals(0L, VideoResume.startPosition(96_000L, dur))
    }

    @Test fun resumeIgnoresTinyAndInvalidPositions() {
        assertEquals(0L, VideoResume.positionToSave(1_000L, 100_000L))
        assertEquals(0L, VideoResume.positionToSave(-5L, 100_000L))
        assertEquals(0L, VideoResume.startPosition(-1L, 100_000L))
        assertFalse(VideoResume.hasResume(0L, 100_000L))
        assertTrue(VideoResume.hasResume(30_000L, 100_000L))
    }

    @Test fun resumeWithUnknownDurationKeepsPosition() {
        assertEquals(20_000L, VideoResume.positionToSave(20_000L, 0L))
    }

    // ---- sort ----
    private val sample = listOf(
        v(1, "beta", added = 10, dur = 500, size = 300),
        v(2, "Alpha", added = 30, dur = 900, size = 100),
        v(3, "gamma", added = 20, dur = 100, size = 200)
    )

    private fun ids(list: List<VideoEntity>) = list.map { it.id }

    @Test fun sortModes() {
        assertEquals(listOf(2L, 3L, 1L), ids(VideoLibraryQuery.apply(sample, "", VideoSort.NEWEST, VideoFilter.ALL)))
        assertEquals(listOf(1L, 3L, 2L), ids(VideoLibraryQuery.apply(sample, "", VideoSort.OLDEST, VideoFilter.ALL)))
        assertEquals(listOf(2L, 1L, 3L), ids(VideoLibraryQuery.apply(sample, "", VideoSort.NAME, VideoFilter.ALL)))
        assertEquals(listOf(2L, 1L, 3L), ids(VideoLibraryQuery.apply(sample, "", VideoSort.DURATION, VideoFilter.ALL)))
        assertEquals(listOf(1L, 3L, 2L), ids(VideoLibraryQuery.apply(sample, "", VideoSort.SIZE, VideoFilter.ALL)))
    }

    // ---- search ----
    @Test fun searchIsCaseInsensitiveAndMatchesFolder() {
        val list = listOf(v(1, "Holiday.MP4", folder = "Trips"), v(2, "lesson1.mp4", folder = "Study"))
        assertEquals(listOf(1L), ids(VideoLibraryQuery.apply(list, "holiday", VideoSort.NEWEST, VideoFilter.ALL)))
        assertEquals(listOf(2L), ids(VideoLibraryQuery.apply(list, "study", VideoSort.NEWEST, VideoFilter.ALL)))
        assertTrue(VideoLibraryQuery.apply(list, "zzz", VideoSort.NEWEST, VideoFilter.ALL).isEmpty())
    }

    @Test fun searchFoldsArabicLettersAndDigits() {
        val list = listOf(v(1, "کلیپ ۱۲ علی"))
        assertEquals(listOf(1L), ids(VideoLibraryQuery.apply(list, "كليپ 12", VideoSort.NEWEST, VideoFilter.ALL)))
    }

    // ---- recent ----
    @Test fun recentShowsOnlyPlayedOrderedByLastPlayed() {
        val list = listOf(v(1, "a", played = 100), v(2, "b", played = 0), v(3, "c", played = 300))
        assertEquals(listOf(3L, 1L), ids(VideoLibraryQuery.apply(list, "", VideoSort.NAME, VideoFilter.RECENT)))
    }

    // ---- formatting ----
    @Test fun formatting() {
        assertEquals("5:09", VideoLibraryQuery.formatDuration(309_000))
        assertEquals("1:05:09", VideoLibraryQuery.formatDuration(3_909_000))
        assertEquals("0:00", VideoLibraryQuery.formatDuration(-1))
        assertEquals("1.5 MB", VideoLibraryQuery.formatSize(1_572_864))
        assertEquals("1920×1080", VideoLibraryQuery.formatResolution(1920, 1080))
        assertNull(VideoLibraryQuery.formatResolution(0, 0))
    }
}
