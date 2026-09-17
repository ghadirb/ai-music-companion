package com.ghadirb.aimusic.ui.screens.albums

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.ui.screens.library.TrackRow

@Composable
fun AlbumDetailScreen(
    albumName: String,
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val tracks by repository.observeByAlbum(albumName).collectAsState(initial = emptyList())
    LazyColumn {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                onClick = { onTrackClick(track, tracks) },
                onFavoriteClick = { /* favoriting is handled from Library per MVP scope */ }
            )
        }
    }
}
