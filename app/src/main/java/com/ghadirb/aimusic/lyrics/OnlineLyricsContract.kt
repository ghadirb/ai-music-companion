package com.ghadirb.aimusic.lyrics

import com.ghadirb.aimusic.data.local.entity.TrackEntity

/**
 * Boundary for a future licensed lyrics supplier. It intentionally has no
 * scraper implementation: downloading lyrics from arbitrary search results or
 * music sites can breach copyright and site terms, and is not suitable for a
 * Play-distributed commercial app.
 *
 * Local sidecar .lrc files remain the first source. A concrete provider may be
 * enabled only after the user opts in and a licensed backend is configured.
 */
interface LicensedLyricsProvider {
    suspend fun findLyrics(track: TrackEntity): LyricsLookupResult
}

sealed interface LyricsLookupResult {
    data object NotConfigured : LyricsLookupResult
    data object NotFound : LyricsLookupResult
    data class Found(val providerName: String, val lines: List<SyncedLyricLine>) : LyricsLookupResult
}

data class SyncedLyricLine(val timeMs: Long?, val text: String)
