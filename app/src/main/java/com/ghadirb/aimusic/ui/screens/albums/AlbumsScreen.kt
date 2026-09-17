package com.ghadirb.aimusic.ui.screens.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ghadirb.aimusic.data.repository.MusicRepository

@Composable
fun AlbumsScreen(repository: MusicRepository, onAlbumClick: (String) -> Unit) {
    val albums by repository.observeAlbums().collectAsState(initial = emptyList())
    LazyColumn {
        items(albums) { album ->
            ListItem(
                headlineContent = { Text(album) },
                modifier = androidx.compose.ui.Modifier.clickable { onAlbumClick(album) }
            )
        }
    }
}
