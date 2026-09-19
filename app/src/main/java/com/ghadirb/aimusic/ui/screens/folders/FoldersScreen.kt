package com.ghadirb.aimusic.ui.screens.folders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.ui.screens.library.TrackRow
import kotlinx.coroutines.launch

@Composable
fun FoldersScreen(
    repository: MusicRepository,
    currentTrackId: Long?,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val tracks by repository.observeTracks().collectAsState(initial = emptyList())
    val folders = tracks.mapNotNull { it.folderPath?.takeIf(String::isNotBlank) }.distinct().sorted()
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    selectedFolder?.let { folder ->
        val folderTracks = tracks.filter { it.folderPath == folder }
        Column(Modifier.fillMaxSize()) {
            ListItem(
                headlineContent = { Text(folder.substringAfterLast('/')) },
                supportingContent = { Text("${folderTracks.size} آهنگ") },
                leadingContent = {
                    IconButton(onClick = { selectedFolder = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(folderTracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCurrentTrack = track.id == currentTrackId,
                        onClick = { onTrackClick(track, folderTracks) },
                        onFavoriteClick = { scope.launch { repository.setFavorite(track.id, !track.isFavorite) } }
                    )
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (folders.isEmpty()) Text("پس از اسکن کتابخانه، پوشه‌های موسیقی اینجا نمایش داده می‌شوند.")
        else LazyColumn {
            items(folders, key = { it }) { folder ->
                ListItem(
                    headlineContent = { Text(folder.substringAfterLast('/')) },
                    supportingContent = { Text("${tracks.count { it.folderPath == folder }} آهنگ") },
                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    modifier = Modifier.clickable { selectedFolder = folder }
                )
            }
        }
    }
}
