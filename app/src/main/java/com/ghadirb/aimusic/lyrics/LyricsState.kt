package com.ghadirb.aimusic.lyrics

sealed interface LyricsState {
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data class Found(val parsed: LrcParser.Parsed) : LyricsState
}
