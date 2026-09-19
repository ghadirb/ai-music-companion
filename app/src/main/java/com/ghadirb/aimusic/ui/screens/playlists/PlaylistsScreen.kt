package com.ghadirb.aimusic.ui.screens.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.repository.MusicRepository

/** MVP manual playlists: create / list / delete / open. Reordering and renaming are not implemented yet. */
@Composable
fun PlaylistsScreen(
    repository: MusicRepository,
    onOpenPlaylist: (Long, String) -> Unit
) {
    val viewModel: PlaylistsViewModel = viewModel(
        factory = viewModelFactory { initializer { PlaylistsViewModel(repository) } }
    )
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "ساخت پلی‌لیست")
            }
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("هنوز پلی‌لیستی نساخته‌اید")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        repository = repository,
                        onClick = { onOpenPlaylist(playlist.id, playlist.name) },
                        onDelete = { viewModel.deletePlaylist(playlist.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    repository: MusicRepository,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val trackCount by repository.observePlaylistTrackCount(playlist.id).collectAsState(initial = 0)
    ListItem(
        headlineContent = { Text(playlist.name) },
        supportingContent = { Text("$trackCount آهنگ") },
        leadingContent = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "حذف پلی‌لیست")
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("پلی‌لیست جدید") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("نام پلی‌لیست") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("ساخت") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
