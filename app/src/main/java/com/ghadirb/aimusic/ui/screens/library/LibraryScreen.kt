package com.ghadirb.aimusic.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository

@Composable
fun LibraryScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(repository) } }
    )
    val tracks by viewModel.tracks.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.scanLibrary() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.scan_library))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tracks.isEmpty() && !isScanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.empty_library))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.scanLibrary() }) {
                            Text(stringResource(R.string.scan_library))
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { onTrackClick(track, tracks) },
                            onFavoriteClick = { viewModel.toggleFavorite(track) }
                        )
                    }
                }
            }
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(track.title) },
        supportingContent = { Text("${track.artist} • ${track.album}") },
        leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
