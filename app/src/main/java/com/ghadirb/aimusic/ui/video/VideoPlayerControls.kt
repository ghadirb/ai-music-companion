package com.ghadirb.aimusic.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.video.VideoLibraryQuery
import com.ghadirb.aimusic.video.VideoPlaybackController

/**
 * The on-screen controls drawn over the video: back, title, PiP, fullscreen, previous / play-pause /
 * next, seek bar, volume, subtitles and the video picker. The transport row is always laid out
 * left-to-right (like every standard Android video player) even though the app is RTL.
 */
@Composable
fun VideoPlayerControls(
    state: VideoPlaybackController.State,
    isFullscreen: Boolean,
    canPip: Boolean,
    volume: Int,
    maxVolume: Int,
    onVolume: (Int) -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onSubtitles: () -> Unit,
    onQueue: () -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPrev = state.index > 0
    val hasNext = state.index < state.queue.lastIndex
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color.Transparent, Color(0xCC000000))))
    ) {
        // ---- top bar ----
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = Color.White) }
            Text(
                state.current?.displayName.orEmpty(),
                color = Color.White, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (canPip) {
                IconButton(onClick = { onInteract(); onEnterPip() }) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "تصویر در تصویر", tint = Color.White)
                }
            }
            IconButton(onClick = { onInteract(); onToggleFullscreen() }) {
                Icon(
                    if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (isFullscreen) "خروج از تمام‌صفحه" else "تمام‌صفحه", tint = Color.White
                )
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            // ---- center transport ----
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                IconButton(onClick = { onInteract(); onPrevious() }, enabled = hasPrev, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "ویدئوی قبلی", tint = if (hasPrev) Color.White else Color.Gray, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { onInteract(); onPlayPause() }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "توقف" else "پخش", tint = Color.White, modifier = Modifier.size(56.dp)
                    )
                }
                IconButton(onClick = { onInteract(); onNext() }, enabled = hasNext, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "ویدئوی بعدی", tint = if (hasNext) Color.White else Color.Gray, modifier = Modifier.size(36.dp))
                }
            }

            // ---- bottom: seek bar + actions ----
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        VideoLibraryQuery.formatDuration((dragging?.toLong()) ?: state.positionMs),
                        color = Color.White, style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, duration.toFloat()),
                        onValueChange = { dragging = it; onInteract() },
                        onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(VideoLibraryQuery.formatDuration(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onInteract(); onQueue() }) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = "انتخاب ویدئو", tint = Color.White)
                    }
                    IconButton(onClick = { onInteract(); onSubtitles() }) {
                        Icon(Icons.Filled.Subtitles, contentDescription = "زیرنویس", tint = if (state.subtitlesOn) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    Box(Modifier.weight(1f))
                    Icon(Icons.Filled.VolumeUp, contentDescription = "صدا", tint = Color.White)
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { onInteract(); onVolume(it.toInt()) },
                        valueRange = 0f..maxVolume.coerceAtLeast(1).toFloat(),
                        modifier = Modifier.width(120.dp).padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
