package com.ghadirb.aimusic.ui.screens.artists

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
fun ArtistsScreen(repository: MusicRepository, onArtistClick: (String) -> Unit) {
    val artists by repository.observeArtists().collectAsState(initial = emptyList())
    LazyColumn {
        items(artists) { artist ->
            ListItem(
                headlineContent = { Text(artist) },
                modifier = androidx.compose.ui.Modifier.clickable { onArtistClick(artist) }
            )
        }
    }
}
