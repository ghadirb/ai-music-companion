package com.ghadirb.aimusic.ui.screens.favorites

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.ui.components.EmptyState
import com.ghadirb.aimusic.ui.components.LoadingState
import com.ghadirb.aimusic.ui.screens.library.TrackRow
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val favorites by repository.observeFavorites().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val list = favorites
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState(Icons.Filled.FavoriteBorder, "هنوز علاقه‌مندی‌ای ندارید", "روی قلب کنار هر آهنگ بزنید تا اینجا جمع شود.")
        else -> LazyColumn {
            items(list, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onTrackClick(track, list) },
                    onFavoriteClick = { scope.launch { repository.setFavorite(track.id, !track.isFavorite) } }
                )
            }
        }
    }
}
