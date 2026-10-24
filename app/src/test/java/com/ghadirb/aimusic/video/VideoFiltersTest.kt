package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFiltersTest {
    private val now = 10_000_000L

    private fun v(
        id: Long, added: Long = now, dur: Long = 100_000, pos: Long = 0, played: Long = 0, fav: Boolean = false
    ) = VideoEntity(
        id = id, contentUri = "content://v/$id", displayName = "v$id", durationMs = dur, sizeBytes = 1,
        width = 0, height = 0, mimeType = "video/mp4", folderName = "", relativePath = "",
        dateAdded = added, dateModified = added, lastPositionMs = pos, lastPlayedAt = played, isFavorite = fav
    )

    private fun ids(list: List<VideoEntity>, f: VideoFilter) =
        VideoLibraryQuery.apply(list, "", VideoSort.NEWEST, f, now).map { it.id }

    @Test fun favoritesOnlyFavorites() {
        assertEquals(listOf(2L), ids(listOf(v(1), v(2, fav = true)), VideoFilter.FAVORITES))
    }

    @Test fun unfinishedNeedsRealResumePosition() {
        val list = listOf(
            v(1, pos = 50_000, played = 5),     // halfway -> unfinished
            v(2, pos = 1_000, played = 9),      // barely started -> not
            v(3, pos = 99_000, played = 7),     // almost done -> not
            v(4, pos = 0)                        // never played -> not
        )
        assertEquals(listOf(1L), ids(list, VideoFilter.UNFINISHED))
    }

    @Test fun newIsRecentAndNeverPlayed() {
        val old = now - VideoLibraryQuery.NEW_WINDOW_SEC - 1
        val list = listOf(v(1, added = now - 10), v(2, added = old), v(3, added = now - 5, played = 3))
        assertEquals(listOf(1L), ids(list, VideoFilter.NEW))
    }

    @Test fun favoriteFilterKeepsSortChoice() {
        val list = listOf(v(1, added = 1, fav = true), v(2, added = 2, fav = true))
        assertEquals(listOf(2L, 1L), ids(list, VideoFilter.FAVORITES))
    }
}
