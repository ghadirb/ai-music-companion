package com.ghadirb.aimusic.ui.screens.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val tracks by repository.observeTracksInPlaylist(playlistId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("این پلی‌لیست خالی است — از صفحه کتابخانه آهنگ اضافه کنید")
        }
        return
    }

    LazyColumn {
        item { com.ghadirb.aimusic.ui.components.RadioStartButton(tracks, "این پلی‌لیست") }
        items(tracks, key = { it.id }) { track ->
            ListItem(
                headlineContent = { Text(track.title) },
                supportingContent = { Text(track.artist) },
                trailingContent = {
                    IconButton(onClick = {
                        scope.launch { repository.removeTrackFromPlaylist(playlistId, track.id) }
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "حذف از پلی‌لیست")
                    }
                },
                modifier = Modifier.clickable { onTrackClick(track, tracks) }
            )
        }
    }
}
