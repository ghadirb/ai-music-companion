package com.ghadirb.aimusic.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkManager
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

@Composable
fun LibraryScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit,
    currentTrackId: Long?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(repository, WorkManager.getInstance(context)) }
        }
    )
    val tracks by viewModel.tracks.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    var trackForPlaylistPicker by remember { mutableStateOf<TrackEntity?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) tracks else tracks.filter { track ->
            listOf(track.title, track.artist, track.album, track.genre.orEmpty()).any { it.contains(query, ignoreCase = true) }
        }
    }

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
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("جست‌وجو در آهنگ، خواننده، آلبوم یا سبک") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredTracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isCurrentTrack = track.id == currentTrackId,
                            onClick = { onTrackClick(track, filteredTracks) },
                            onFavoriteClick = { viewModel.toggleFavorite(track) },
                            onAddToPlaylistClick = { trackForPlaylistPicker = track }
                        )
                    }
                    if (filteredTracks.isEmpty()) {
                        item { Text("نتیجه‌ای پیدا نشد.", modifier = Modifier.padding(24.dp)) }
                    }
                    }
                }
            }
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    trackForPlaylistPicker?.let { track ->
        AddToPlaylistDialog(
            repository = repository,
            track = track,
            onDismiss = { trackForPlaylistPicker = null }
        )
    }
}

@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onAddToPlaylistClick: (() -> Unit)? = null,
    isCurrentTrack: Boolean = false
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, maxLines = 1, modifier = Modifier.weight(1f))
                if (isCurrentTrack) Icon(Icons.Filled.PlayArrow, contentDescription = "در حال پخش")
            }
        },
        supportingContent = {
            if (track.artist != "Unknown artist") Text(track.artist, maxLines = 1)
        },
        leadingContent = {
            if (track.albumArtUri != null) {
                AsyncImage(track.albumArtUri, contentDescription = track.album, modifier = Modifier.size(56.dp))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }
        },
        trailingContent = {
            Row {
                if (onAddToPlaylistClick != null) {
                    IconButton(onClick = onAddToPlaylistClick) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = "افزودن به پلی‌لیست")
                    }
                }
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * Lets the user add [track] to an existing playlist, or create a new one on
 * the fly. Kept as a simple list + inline "create new" row instead of a
 * separate flow to minimize taps for the common case.
 */
@Composable
private fun AddToPlaylistDialog(
    repository: MusicRepository,
    track: TrackEntity,
    onDismiss: () -> Unit
) {
    val playlists by repository.observePlaylists().collectAsState(initial = emptyList())
    var newPlaylistName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن به پلی‌لیست") },
        text = {
            Column {
                playlists.forEach { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        modifier = Modifier.clickable {
                            scope.launch {
                                repository.addTrackToPlaylist(playlist.id, track.id)
                                onDismiss()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("پلی‌لیست جدید") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    val id = repository.createPlaylist(name)
                                    repository.addTrackToPlaylist(id, track.id)
                                    onDismiss()
                                }
                            }
                        },
                        enabled = newPlaylistName.isNotBlank()
                    ) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = "ساخت و افزودن")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
