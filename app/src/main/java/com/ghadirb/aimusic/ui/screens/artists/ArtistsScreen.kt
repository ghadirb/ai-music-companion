package com.ghadirb.aimusic.ui.screens.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.repository.MusicRepository
import coil.compose.AsyncImage

@Composable
fun ArtistsScreen(repository: MusicRepository, onArtistClick: (String) -> Unit) {
    val tracks by repository.observeTracks().collectAsState(initial = emptyList())
    val artists = androidx.compose.runtime.remember(tracks) { tracks.groupBy { it.artist }.filterKeys { it != "Unknown artist" }.toSortedMap() }
    if (artists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("پس از اسکن کتابخانه، هنرمندان اینجا نمایش داده می‌شوند.")
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            items(artists.entries.toList(), key = { it.key }) { (artist, artistTracks) ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onArtistClick(artist) },
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val cover = artistTracks.firstOrNull { it.albumArtUri != null }?.albumArtUri
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = artist,
                                contentScale = ContentScale.Crop,
                                error = rememberVectorPainter(Icons.Filled.Person),
                                modifier = Modifier.size(64.dp)
                            )
                        } else {
                            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(64.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(artist, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text("${artistTracks.size} آهنگ", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
