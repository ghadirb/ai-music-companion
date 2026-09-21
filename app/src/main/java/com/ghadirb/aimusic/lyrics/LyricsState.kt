package com.ghadirb.aimusic.lyrics

/** Where the displayed lyrics came from (shown to the user so it is clear why a song is/isn't synced). */
enum class LyricsOrigin { IMPORTED, SIDECAR, EMBEDDED }

sealed interface LyricsState {
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data class Found(val parsed: LrcParser.Parsed, val origin: LyricsOrigin = LyricsOrigin.SIDECAR) : LyricsState
}
