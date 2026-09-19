package com.ghadirb.aimusic.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/** Queue shortcuts available from any track row (provided once in MainScaffold). */
data class QueueActions(
    val playNext: (TrackEntity) -> Unit,
    val addToQueue: (TrackEntity) -> Unit
)

val LocalQueueActions = staticCompositionLocalOf<QueueActions?> { null }
