package com.ghadirb.aimusic.ui.screens.folders

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.ui.common.rememberDeleteFlow
import com.ghadirb.aimusic.ui.screens.library.TrackRow
import kotlinx.coroutines.launch

/**
 * Music folders. Inside a folder a track can be deleted from the phone one by one (row menu) or several at once
 * (long-press to select) - the same real deletion as the Library ([rememberDeleteFlow]): a track leaves the list
 * only after its file is really gone from the device.
 */
@Composable
fun FoldersScreen(
    repository: MusicRepository,
    currentTrackId: Long?,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val tracks by repository.observeTracks().collectAsState(initial = emptyList())
    val folders = tracks.mapNotNull { it.folderPath?.takeIf(String::isNotBlank) }.distinct().sorted()
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val idByUri = remember { mutableMapOf<Uri, Long>() }

    val deleteFlow = rememberDeleteFlow(
        onDeleted = { uris ->
            val ids = uris.mapNotNull { idByUri[it] }
            if (ids.isNotEmpty()) {
                selected = selected - ids.toSet()
                scope.launch {
                    repository.removeTracksFromLibrary(ids)
                    snackbar.showSnackbar("${ids.size} آهنگ از دستگاه حذف شد")
                }
            }
        },
        onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } }
    )

    fun requestDelete(targets: List<TrackEntity>) {
        if (targets.isEmpty()) return
        idByUri.clear()
        targets.forEach { t -> runCatching { Uri.parse(t.path) }.getOrNull()?.let { idByUri[it] = t.id } }
        deleteFlow(idByUri.keys.toList())
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && selectedFolder != null) { selectedFolder = null }

    Box(Modifier.fillMaxSize()) {
        val folder = selectedFolder
        if (folder != null) {
            val folderTracks = tracks.filter { it.folderPath == folder }
            LaunchedEffect(folderTracks.isEmpty()) { if (folderTracks.isEmpty()) { selectedFolder = null; selected = emptySet() } }
            val selecting = selected.isNotEmpty()
            Column(Modifier.fillMaxSize()) {
                if (selecting) {
                    ListItem(
                        headlineContent = { Text("${selected.size} انتخاب شده") },
                        leadingContent = {
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "لغو انتخاب")
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { selected = folderTracks.map { it.id }.toSet() }) {
                                    Icon(Icons.Filled.SelectAll, contentDescription = "انتخاب همه")
                                }
                                IconButton(onClick = { confirmDelete = folderTracks.filter { it.id in selected } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف از دستگاه")
                                }
                            }
                        }
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(folder.substringAfterLast('/')) },
                        supportingContent = { Text("${folderTracks.size} آهنگ") },
                        leadingContent = {
                            IconButton(onClick = { selectedFolder = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                            }
                        }
                    )
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(folderTracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isCurrentTrack = track.id == currentTrackId,
                            onClick = { onTrackClick(track, folderTracks) },
                            onFavoriteClick = { scope.launch { repository.setFavorite(track.id, !track.isFavorite) } },
                            onDeleteClick = { confirmDelete = listOf(track) },
                            selectionMode = selecting,
                            selected = track.id in selected,
                            onToggleSelect = { selected = if (track.id in selected) selected - track.id else selected + track.id },
                            onLongPress = { selected = selected + track.id }
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                if (folders.isEmpty()) Text("پس از اسکن کتابخانه، پوشه‌های موسیقی اینجا نمایش داده می‌شوند.")
                else LazyColumn {
                    items(folders, key = { it }) { f ->
                        ListItem(
                            headlineContent = { Text(f.substringAfterLast('/')) },
                            supportingContent = { Text("${tracks.count { it.folderPath == f }} آهنگ") },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.clickable { selectedFolder = f }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmDelete.isNotEmpty()) {
        val targets = confirmDelete
        AlertDialog(
            onDismissRequest = { confirmDelete = emptyList() },
            title = { Text("حذف از دستگاه") },
            text = {
                Text(
                    if (targets.size == 1) "«${targets[0].title}» برای همیشه از حافظهٔ گوشی پاک می‌شود. ادامه می‌دهید؟"
                    else "${targets.size} آهنگ برای همیشه از حافظهٔ گوشی پاک می‌شوند. ادامه می‌دهید؟"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = emptyList(); requestDelete(targets) }) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = emptyList() }) { Text("انصراف") } }
        )
    }
}
