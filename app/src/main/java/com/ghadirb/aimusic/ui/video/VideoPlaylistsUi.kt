package com.ghadirb.aimusic.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.video.VideoLibraryQuery

/** Name entry used for "new playlist" and "rename". */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, placeholder = { Text("نام پلی‌لیست") })
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

/** Lists all playlists; opening one shows its videos in the user's order with reorder / remove. */
@Composable
fun VideoPlaylistsPane(
    vm: VideoPlaylistViewModel,
    onPlay: (queue: List<VideoEntity>, start: VideoEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.ui.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val open = state.open

    if (open != null) BackHandler(onBack = vm::close)

    if (creating) NameDialog("پلی‌لیست جدید", "", "ایجاد", { vm.create(it); creating = false }, { creating = false })
    renaming?.let { id ->
        val current = state.playlists.firstOrNull { it.playlist.id == id }?.playlist?.name.orEmpty()
        NameDialog("تغییر نام", current, "ذخیره", { vm.rename(id, it); renaming = null }, { renaming = null })
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف پلی‌لیست") },
            text = { Text("فقط خود پلی‌لیست حذف می‌شود؛ ویدئوها روی گوشی و در کتابخانه می‌مانند.") },
            confirmButton = { TextButton(onClick = { vm.delete(id); deleting = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("انصراف") } }
        )
    }

    if (open == null) {
        Column(modifier.fillMaxSize()) {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  پلی‌لیست جدید")
            }
            if (state.playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("هنوز پلی‌لیستی نساخته‌اید.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.playlists, key = { it.playlist.id }) { p ->
                        var menu by remember { mutableStateOf(false) }
                        ListItem(
                            modifier = Modifier.clickable { vm.open(p.playlist.id) },
                            leadingContent = {
                                val cover = p.cover
                                if (cover != null) VideoThumbnail(cover, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                                else Box(Modifier.width(96.dp).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                                }
                            },
                            headlineContent = { Text(p.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${p.count} ویدئو") },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "گزینه‌ها") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("تغییر نام" ) },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = { menu = false; renaming = p.playlist.id }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("حذف پلی‌لیست") },
                                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                            onClick = { menu = false; deleting = p.playlist.id }
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت") }
                Column(Modifier.weight(1f)) {
                    Text(open.playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${open.count} ویدئو", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = open.videos.isNotEmpty(), onClick = { onPlay(open.videos, open.videos.first()) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("پخش همه")
                }
            }
            HorizontalDivider()
            if (open.videos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("این پلی‌لیست خالی است. از منوی هر ویدئو «افزودن به پلی‌لیست» را بزنید.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(open.videos.size, key = { open.videos[it].id }) { index ->
                        val v = open.videos[index]
                        Row(
                            Modifier.fillMaxWidth().clickable { onPlay(open.videos, v) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VideoThumbnail(v, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(v.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(VideoLibraryQuery.formatSize(v.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column {
                                IconButton(enabled = index > 0, onClick = { vm.move(open.playlist.id, index, index - 1) }) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "بالا")
                                }
                                IconButton(enabled = index < open.videos.lastIndex, onClick = { vm.move(open.playlist.id, index, index + 1) }) {
                                    Icon(Icons.Filled.ArrowDownward, contentDescription = "پایین")
                                }
                            }
                            // Removes from the playlist only; the file is never touched.
                            IconButton(onClick = { vm.remove(open.playlist.id, v.id) }) { Icon(Icons.Filled.Close, contentDescription = "حذف از پلی‌لیست") }
                        }
                    }
                }
            }
        }
    }
}

/** "افزودن به پلی‌لیست": tick playlists, or make a new one that already contains the video. */
@Composable
fun AddToPlaylistDialog(video: VideoEntity, vm: VideoPlaylistViewModel, onDismiss: () -> Unit) {
    val state by vm.ui.collectAsState()
    var newName by remember { mutableStateOf("") }
    val member = state.playlistIdsOf(video.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن به پلی‌لیست") },
        text = {
            Column {
                Text(video.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                state.playlists.forEach { p ->
                    val inIt = p.playlist.id in member
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (inIt) vm.remove(p.playlist.id, video.id) else vm.add(p.playlist.id, listOf(video.id))
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = inIt, onCheckedChange = null)
                        Text(p.playlist.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(60) }, singleLine = true,
                    placeholder = { Text("پلی‌لیست جدید…") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) vm.create(newName, listOf(video.id))
                onDismiss()
            }) { Text(if (newName.isNotBlank()) "ایجاد و افزودن" else "تمام") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

/** Adds several selected videos to one playlist (existing or new). Videos already inside are skipped. */
@Composable
fun AddManyToPlaylistDialog(videos: List<VideoEntity>, vm: VideoPlaylistViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val state by vm.ui.collectAsState()
    var newName by remember { mutableStateOf("") }
    val ids = videos.map { it.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن ${ids.size} ویدئو به پلی‌لیست") },
        text = {
            Column {
                state.playlists.forEach { p ->
                    Text(
                        "${p.playlist.name}  (${p.count})",
                        modifier = Modifier.fillMaxWidth().clickable { vm.add(p.playlist.id, ids); onDone() }.padding(vertical = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(60) }, singleLine = true,
                    placeholder = { Text("پلی‌لیست جدید…") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { vm.create(newName, ids); onDone() }) { Text("ایجاد و افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
