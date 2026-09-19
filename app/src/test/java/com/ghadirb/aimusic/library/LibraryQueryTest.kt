package com.ghadirb.aimusic.library

import com.ghadirb.aimusic.data.local.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private fun t(id: Long, title: String, dur: Long = 1000, fav: Boolean = false, added: Long = 0, energy: Float? = null, mood: String? = null, genre: String? = null) =
        TrackEntity(id = id, path = "p$id", title = title, artist = "A", album = "B", genre = genre, durationMs = dur, isFavorite = fav, dateAdded = added, energyLevel = energy, moodTag = mood)

    private val tracks = listOf(
        t(1, "Charlie", dur = 300, added = 10, energy = 0.2f, mood = "calm", genre = "Jazz"),
        t(2, "Alpha", dur = 100, fav = true, added = 30, energy = 0.9f, mood = "energetic", genre = "Rock"),
        t(3, "Bravo", dur = 200, added = 20, energy = 0.5f, mood = "neutral", genre = "Rock")
    )
    private val none = LibraryFilter()

    @Test fun sortsByTitle() =
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, none).map { it.title })

    @Test fun sortsByRecentlyAdded() =
        assertEquals(listOf(2L, 3L, 1L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.RECENTLY_ADDED, none).map { it.id })

    @Test fun sortsByDurationAscending() =
        assertEquals(listOf(2L, 3L, 1L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.DURATION, none).map { it.id })

    @Test fun sortsByMostAndRecentlyPlayedUsingStats() {
        val stats = mapOf(1L to TrackStat(1, 9, 100), 3L to TrackStat(3, 2, 500))
        assertEquals(1L, LibraryQuery.apply(tracks, stats, LibrarySort.MOST_PLAYED, none).first().id)
        assertEquals(3L, LibraryQuery.apply(tracks, stats, LibrarySort.RECENTLY_PLAYED, none).first().id)
    }

    @Test fun favoritesSortFirst() =
        assertEquals(2L, LibraryQuery.apply(tracks, emptyMap(), LibrarySort.FAVORITE, none).first().id)

    @Test fun filtersByFavoriteGenreMoodAndEnergy() {
        assertEquals(listOf(2L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, LibraryFilter(favoritesOnly = true)).map { it.id })
        assertEquals(listOf(2L, 3L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, LibraryFilter(genre = "rock")).map { it.id })
        assertEquals(listOf(1L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, LibraryFilter(mood = "calm")).map { it.id })
        assertEquals(listOf(1L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, LibraryFilter(energy = EnergyBand.LOW)).map { it.id })
        assertEquals(listOf(2L), LibraryQuery.apply(tracks, emptyMap(), LibrarySort.TITLE, LibraryFilter(energy = EnergyBand.HIGH)).map { it.id })
    }
}
