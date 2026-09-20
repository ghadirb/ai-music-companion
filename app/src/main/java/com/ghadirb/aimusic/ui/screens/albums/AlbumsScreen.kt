package com.ghadirb.aimusic.ui.screens.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.ui.components.EmptyState
import com.ghadirb.aimusic.ui.components.LoadingState

@Composable
fun AlbumsScreen(repository: MusicRepository, onAlbumClick: (String) -> Unit) {
    val albums by repository.observeAlbums().collectAsState(initial = null)
    val list = albums
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState(Icons.Filled.Album, "آلبومی پیدا نشد", "کتابخانه را از تب «کتابخانه» اسکن کنید.")
        else -> LazyColumn {
            items(list) { album ->
                ListItem(headlineContent = { Text(album) }, modifier = Modifier.clickable { onAlbumClick(album) })
            }
        }
    }
}
