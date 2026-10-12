package com.ghadirb.aimusic.ui.screens.online

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * Full-size "now playing" page for a streamed song, the online twin of the library's PlayerScreen:
 * big cover (the picture embedded in the file; a placeholder when it has none), seek bar and transport controls.
 * Drawn on top of the "new songs" lists, so their scroll position survives closing it.
 */
@Composable
internal fun OnlinePlayerScreen(state: OnlineUiState, vm: OnlineMusicViewModel, onCollapse: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) { vm.tick(); delay(500) }
    }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) {}, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onCollapse) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "بستن پخش‌کننده") }
            }
            Crossfade(targetState = state.nowArtwork, label = "onlineCover") { cover ->
                Box(
                    Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) {
                        Image(cover, contentDescription = "جلد آهنگ ${state.nowTitle.orEmpty()}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        CoverPlaceholder(state.nowTitle.orEmpty(), Modifier.fillMaxSize(), iconSize = 96)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                state.nowTitle.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!state.nowArtist.isNullOrBlank()) {
                Text(state.nowArtist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            val duration = state.durationMs.coerceAtLeast(1L)
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { vm.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.semantics { contentDescription = "نوار پیشرفت آهنگ" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle, contentDescription = "پخش تصادفی",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = vm::skipPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "آهنگ قبلی", modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = vm::togglePlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "توقف" else "پخش", modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = vm::skipNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "آهنگ بعدی", modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "تکرار",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val track = state.nowTrack
            if (track != null) {
                val favorite = track.key in state.favoriteKeys
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { vm.toggleFavorite(track) }) {
                        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (favorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها")
                    }
                    IconButton(onClick = { vm.downloadSong(track) }) {
                        Icon(Icons.Filled.Download, contentDescription = "دانلود آهنگ")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Cover stand-in for files without embedded art: a gradient whose hue is stable per title, with a note icon. */
@Composable
internal fun CoverPlaceholder(seed: String, modifier: Modifier = Modifier, iconSize: Int = 24) {
    val hue = ((seed.hashCode() % 360) + 360) % 360
    val top = Color.hsv(hue.toFloat(), 0.45f, 0.75f)
    val bottom = Color.hsv(((hue + 40) % 360).toFloat(), 0.55f, 0.45f)
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize.dp))
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
