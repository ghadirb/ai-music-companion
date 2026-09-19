package com.ghadirb.aimusic.ui.screens.favorites

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.ui.screens.library.TrackRow

@Composable
fun FavoritesScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val favorites by repository.observeFavorites().collectAsState(initial = emptyList())
    LazyColumn {
        items(favorites, key = { it.id }) { track ->
            TrackRow(
                track = track,
                onClick = { onTrackClick(track, favorites) },
                onFavoriteClick = { /* handled in Library; kept read-focused here per MVP scope */ }
            )
        }
    }
}
