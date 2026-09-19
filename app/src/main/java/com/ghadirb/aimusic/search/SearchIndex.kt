package com.ghadirb.aimusic.search

import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity

data class SearchResults(
    val tracks: List<TrackEntity>,
    val artists: List<String>,
    val albums: List<String>,
    val genres: List<String>,
    val playlists: List<PlaylistEntity>
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && genres.isEmpty() && playlists.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Pre-normalised search index. Build it once per library change (off the main thread) and reuse
 * it for every keystroke, so search stays instant for large libraries.
 */
class SearchIndex(tracks: List<TrackEntity>, playlists: List<PlaylistEntity>) {

    private class TrackEntry(val track: TrackEntity) {
        val title = SearchText.normalize(track.title)
        val artist = SearchText.normalize(track.artist)
        val album = SearchText.normalize(track.album)
        val genre = SearchText.normalize(track.genre)
        val all = "$title $artist $album $genre"
    }

    private class Named<T>(val value: T, val normalized: String)

    private val entries = tracks.map { TrackEntry(it) }
    private val artists = tracks.map { it.artist }.filter { it != UNKNOWN_ARTIST && it.isNotBlank() }
        .distinct().map { Named(it, SearchText.normalize(it)) }
    private val albums = tracks.map { it.album }.filter { it != UNKNOWN_ALBUM && it.isNotBlank() }
        .distinct().map { Named(it, SearchText.normalize(it)) }
    private val genres = tracks.mapNotNull { it.genre?.takeIf(String::isNotBlank) }
        .distinct().map { Named(it, SearchText.normalize(it)) }
    private val playlistEntries = playlists.map { Named(it, SearchText.normalize(it.name)) }

    fun search(query: String, maxTracks: Int = 300): SearchResults {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return SearchResults.EMPTY
        val joined = tokens.joinToString(" ")

        val matchedTracks = entries
            .filter { SearchText.matchesAll(it.all, tokens) }
            .sortedWith(
                compareBy<TrackEntry> { rank(it, joined, tokens) }
                    .thenBy { it.title }
            )
            .take(maxTracks)
            .map { it.track }

        return SearchResults(
            tracks = matchedTracks,
            artists = artists.filter { SearchText.matchesAll(it.normalized, tokens) }.map { it.value }.take(MAX_GROUP),
            albums = albums.filter { SearchText.matchesAll(it.normalized, tokens) }.map { it.value }.take(MAX_GROUP),
            genres = genres.filter { SearchText.matchesAll(it.normalized, tokens) }.map { it.value }.take(MAX_GROUP),
            playlists = playlistEntries.filter { SearchText.matchesAll(it.normalized, tokens) }.map { it.value }.take(MAX_GROUP)
        )
    }

    private fun rank(entry: TrackEntry, joined: String, tokens: List<String>): Int = when {
        entry.title == joined -> 0
        entry.title.startsWith(tokens.first()) -> 1
        SearchText.matchesAll(entry.title, tokens) -> 2
        SearchText.matchesAll(entry.artist, tokens) -> 3
        else -> 4
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        const val MAX_GROUP = 12
    }
}
