package com.ghadirb.aimusic.ui.screens.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.ui.components.EmptyState
import com.ghadirb.aimusic.ui.components.LoadingState
import com.ghadirb.aimusic.ui.components.LocalQueueActions
import com.ghadirb.aimusic.ui.screens.library.TrackRow
import kotlinx.coroutines.launch

/**
 * Playlist detail: header (collage artwork, name, track count, total
 * duration), Play All / Shuffle, and the track list — spec §7. Works for
 * both user playlists and smart-generated ones (same PlaylistEntity shape).
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val playlistName by produceState<String?>(initialValue = null, playlistId) {
        value = repository.getPlaylist(playlistId)?.name
    }
    val tracks by repository.observeTracksInPlaylist(playlistId).collectAsState(initial = null)
    val queueActions = LocalQueueActions.current
    val scope = rememberCoroutineScope()

    val list = tracks
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState(
            icon = Icons.Filled.QueueMusic,
            title = "این پلی‌لیست خالی است",
            subtitle = "از صفحهٔ کتابخانه آهنگ اضافه کنید."
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                PlaylistHeader(
                    name = playlistName ?: "",
                    tracks = list,
                    onPlayAll = { list.firstOrNull()?.let { onTrackClick(it, list) } },
                    onShuffle = {
                        val shuffled = list.shuffled()
                        shuffled.firstOrNull()?.let { onTrackClick(it, shuffled) }
                    },
                    onStartRadio = { queueActions?.startRadio(list, playlistName ?: "این پلی‌لیست") }
                )
            }
            items(list, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onTrackClick(track, list) },
                    onFavoriteClick = { scope.launch { repository.setFavorite(track.id, !track.isFavorite) } },
                    onAddToPlaylistClick = null,
                    trailingExtra = {
                        IconButton(onClick = { scope.launch { repository.removeTrackFromPlaylist(playlistId, track.id) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "حذف از پلی‌لیست")
                        }
                    }
                )
            }
        }
    }
}

/** Spec §7: collage artwork, name, track count + total duration, Play All / Shuffle / Radio. */
@Composable
private fun PlaylistHeader(
    name: String,
    tracks: List<TrackEntity>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onStartRadio: () -> Unit
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    val artUris = remember(tracks) { tracks.mapNotNull { it.albumArtUri }.distinct().take(4) }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistCollage(artUris, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${tracks.size} آهنگ • ${formatPlaylistDuration(totalDurationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onPlayAll, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("پخش همه")
            }
            OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("تصادفی")
            }
            OutlinedIconButton(onClick = onStartRadio) {
                Text("📻")
            }
        }
    }
}

/** 2x2 collage when the playlist has 4+ distinct covers, a single cover, or a placeholder — never stretched art. */
@Composable
private fun PlaylistCollage(artUris: List<String>, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            artUris.size >= 4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    CollageTile(artUris[0], Modifier.weight(1f).fillMaxHeight())
                    CollageTile(artUris[1], Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    CollageTile(artUris[2], Modifier.weight(1f).fillMaxHeight())
                    CollageTile(artUris[3], Modifier.weight(1f).fillMaxHeight())
                }
            }
            artUris.isNotEmpty() -> AsyncImage(
                model = artUris.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = rememberVectorPainter(Icons.Filled.QueueMusic),
                modifier = Modifier.fillMaxSize()
            )
            else -> Icon(
                Icons.Filled.QueueMusic, contentDescription = null,
                modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CollageTile(uri: String, modifier: Modifier) {
    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

private fun formatPlaylistDuration(ms: Long): String {
    val totalMinutes = ms / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours ساعت و $minutes دقیقه" else "$minutes دقیقه"
}
