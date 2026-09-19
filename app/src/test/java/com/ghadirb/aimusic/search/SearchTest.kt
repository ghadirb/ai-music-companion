package com.ghadirb.aimusic.search

import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun track(id: Long, title: String, artist: String = "Unknown artist", album: String = "Unknown album", genre: String? = null) =
        TrackEntity(id = id, path = "content://t/$id", title = title, artist = artist, album = album, genre = genre, durationMs = 1000)

    @Test fun normalizesArabicLettersAndZwnj() {
        assertEquals(SearchText.normalize("علي كريمي"), SearchText.normalize("علی کریمی"))
        assertEquals(SearchText.normalize("می‌خواهم"), SearchText.normalize("می خواهم"))
    }

    @Test fun normalizesDigitsCaseAndWhitespace() {
        assertEquals("track 12", SearchText.normalize("  TRACK   ۱۲ "))
        assertEquals("", SearchText.normalize(null))
    }

    @Test fun findsPersianWithArabicKeyboardVariant() {
        val index = SearchIndex(listOf(track(1, "دلتنگی", artist = "علی")), emptyList())
        assertEquals(1, index.search("علي").tracks.size)
    }

    @Test fun requiresAllTokensAcrossFields() {
        val index = SearchIndex(
            listOf(track(1, "Blue", artist = "Miles"), track(2, "Blue", artist = "Other")), emptyList()
        )
        assertEquals(listOf(1L), index.search("blue miles").tracks.map { it.id })
    }

    @Test fun ranksTitleMatchBeforeArtistMatch() {
        val index = SearchIndex(
            listOf(track(1, "Something", artist = "Rain"), track(2, "Rain", artist = "Other")), emptyList()
        )
        assertEquals(listOf(2L, 1L), index.search("rain").tracks.map { it.id })
    }

    @Test fun groupsArtistsAlbumsGenresAndPlaylists() {
        val index = SearchIndex(
            listOf(track(1, "A", artist = "Rock Star", album = "Rock Album", genre = "Rock")),
            listOf(PlaylistEntity(id = 5, name = "Rock Mix"))
        )
        val result = index.search("rock")
        assertEquals(listOf("Rock Star"), result.artists)
        assertEquals(listOf("Rock Album"), result.albums)
        assertEquals(listOf("Rock"), result.genres)
        assertEquals(1, result.playlists.size)
    }

    @Test fun blankQueryIsEmpty() {
        assertTrue(SearchIndex(listOf(track(1, "A")), emptyList()).search("   ").isEmpty)
    }
}
