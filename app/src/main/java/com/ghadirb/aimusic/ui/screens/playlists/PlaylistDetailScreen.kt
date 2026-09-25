package com.ghadirb.aimusic.ui.screens.playlists

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
