package com.ghadirb.aimusic.ui.screens.artists

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
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
fun ArtistDetailScreen(
    artistName: String,
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val tracks by repository.observeByArtist(artistName).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val list = tracks
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState(Icons.Filled.MusicOff, "آهنگی پیدا نشد")
        else -> LazyColumn {
            item { com.ghadirb.aimusic.ui.components.RadioStartButton(list, artistName) }
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
