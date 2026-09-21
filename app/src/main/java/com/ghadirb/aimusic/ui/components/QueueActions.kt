package com.ghadirb.aimusic.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/** Queue shortcuts available from any track row (provided once in MainScaffold). */
data class QueueActions(
    val playNext: (TrackEntity) -> Unit,
    val addToQueue: (TrackEntity) -> Unit,
    /** Smart Radio from a set of seed tracks (a song, an artist, an album, a playlist, favourites or a mix). */
    val startRadio: (List<TrackEntity>, String) -> Unit = { _, _ -> }
)

val LocalQueueActions = staticCompositionLocalOf<QueueActions?> { null }
