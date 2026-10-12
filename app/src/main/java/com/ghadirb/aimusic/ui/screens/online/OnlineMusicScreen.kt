package com.ghadirb.aimusic.ui.screens.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.music.ArchiveTrack
import com.ghadirb.aimusic.music.OnlineArtist
import com.ghadirb.aimusic.music.OnlineCollection
import com.ghadirb.aimusic.music.OnlineTrack
import java.util.Locale

/**
 * «گلچین خوانندگان» (singer ZIP collections) is switched off for now: detection of singer collections was
 * unreliable (mixed in albums and mixes). The code stays; flip this to true to bring the tab back.
 */
const val SINGERS_ENABLED = false

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1048576.0)

/** "آهنگ‌های جدید": new songs of the last 3/6 months, singer collections (ZIP) and online favourites. */
@UnstableApi
@Composable
fun OnlineMusicScreen(viewModel: OnlineMusicViewModel) {
    val state by viewModel.ui.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(ctx.getString(m.res, *m.args.toTypedArray()))
        viewModel.messageShown()
    }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Nothing streamed is playing any more -> the full player has nothing to show.
    LaunchedEffect(state.nowTitle) { if (state.nowTitle == null) playerOpen = false }
    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = state.tab == OnlineTab.SINGERS && (state.openArtist != null || state.archive != null)) { viewModel.back() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val tabs = remember { OnlineTab.values().filter { it != OnlineTab.SINGERS || SINGERS_ENABLED } }
            TabRow(selectedTabIndex = tabs.indexOf(state.tab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    val label = when (tab) {
                        OnlineTab.NEW -> R.string.online_tab_new
                        OnlineTab.SINGERS -> R.string.online_tab_singers
                        OnlineTab.FAVORITES -> R.string.online_tab_favorites
                    }
                    Tab(selected = state.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(stringResource(label)) })
                }
            }
            if (state.downloadPercent != null) DownloadBar(state.downloadPercent!!, viewModel::cancel)
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    OnlineTab.NEW -> NewTab(state, viewModel)
                    OnlineTab.SINGERS -> SingersTab(state, viewModel)
                    OnlineTab.FAVORITES -> TrackList(state.favorites, state, viewModel, stringResource(R.string.online_empty_favorites))
                }
            }
            if (state.nowTitle != null) NowPlayingBar(state, viewModel, onOpen = { playerOpen = true })
        }
        if (playerOpen && state.nowTitle != null) OnlinePlayerScreen(state, viewModel, onCollapse = { playerOpen = false })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DownloadBar(percent: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (percent >= 0) stringResource(R.string.online_downloading, percent) else stringResource(R.string.online_preparing),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.online_cancel)) }
        }
        if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NowPlayingBar(state: OnlineUiState, vm: OnlineMusicViewModel, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val cover = state.nowArtwork
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) {
                if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                else CoverPlaceholder(state.nowTitle.orEmpty(), Modifier.size(44.dp), iconSize = 22)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.nowTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (!state.nowArtist.isNullOrBlank()) Text(state.nowArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = vm::togglePlayPause) { Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
            IconButton(onClick = vm::skipNext) { Icon(Icons.Filled.SkipNext, null) }
        }
    }
}

@Composable
private fun NewTab(state: OnlineUiState, vm: OnlineMusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.windowDays == 90, onClick = { vm.setWindow(90) }, label = { Text(stringResource(R.string.online_window_3)) })
            FilterChip(selected = state.windowDays == 183, onClick = { vm.setWindow(183) }, label = { Text(stringResource(R.string.online_window_6)) })
        }
        OutlinedTextField(
            value = state.query, onValueChange = vm::setQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.online_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (!state.serverReachable) Text(stringResource(R.string.online_server_down), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (state.loading && state.tracks.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else TrackList(state.tracks, state, vm, stringResource(R.string.online_empty))
    }
}

@Composable
private fun TrackList(tracks: List<OnlineTrack>, state: OnlineUiState, vm: OnlineMusicViewModel, emptyText: String) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(emptyText, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { it.id }) { t ->
            val index = tracks.indexOf(t)
            TrackRow(t, t.key in state.favoriteKeys, onPlay = { vm.playFrom(tracks, index) }, vm = vm)
            HorizontalDivider()
        }
        item { Text(stringResource(R.string.online_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TrackRow(t: OnlineTrack, favorite: Boolean, onPlay: () -> Unit, vm: OnlineMusicViewModel) {
    var menu by remember { mutableStateOf(false) }
    val best = t.versions.firstOrNull()
    val sub = buildString {
        if (!t.artist.isNullOrBlank()) append(t.artist)
        if (best != null) { if (isNotEmpty()) append(" • "); append(best.source) }
        best?.quality?.let { append(" • ").append(it).append(" kbps") }
        if (t.versions.size > 1) append(" • +").append(t.versions.size - 1)
        append(" • ").append(t.folderIso)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleFavorite(t) }) {
                    Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(if (favorite) R.string.online_favorite_remove else R.string.online_favorite_add))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.online_play_next)) }, onClick = { menu = false; vm.addNext(t) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.online_add_queue)) }, onClick = { menu = false; vm.addLast(t) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.online_download_song)) }, onClick = { menu = false; vm.downloadSong(t) })
                    }
                }
            }
        },
    )
}

@Composable
private fun SingersTab(state: OnlineUiState, vm: OnlineMusicViewModel) {
    val archive = state.archive
    val artist = state.openArtist
    when {
        archive != null -> ArchiveView(archive, vm)
        artist != null -> CollectionsView(artist, state.collections, vm)
        else -> SingersList(state, vm)
    }
}

@Composable
private fun SingersList(state: OnlineUiState, vm: OnlineMusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.scanRemaining > 0 || state.artistsLoading) {
            Text(stringResource(R.string.online_scan_progress, state.scanRemaining), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.artists.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.online_singers_empty), Modifier.padding(24.dp)) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.artists, key = { it.key }) { a ->
                ListItem(
                    modifier = Modifier.clickable { vm.openArtist(a) },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(R.string.online_collections_count, a.collections)) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CollectionsView(artist: OnlineArtist, collections: List<OnlineCollection>, vm: OnlineMusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.online_back)) }
            Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (collections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(collections, key = { it.url }) { c ->
                val sub = buildString {
                    append(c.source)
                    c.size?.let { append(" • ").append(stringResource0(it)) }
                    append(" • ").append(c.folderIso)
                }
                ListItem(
                    modifier = Modifier.clickable { vm.openCollection(c) },
                    leadingContent = { Icon(Icons.Filled.Folder, null) },
                    headlineContent = { Text(c.collection, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(sub) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** "123.4 MB" without a string resource round trip (digits are locale-neutral on purpose, like the file sizes elsewhere). */
private fun stringResource0(bytes: Long): String = mb(bytes) + " MB"

@Composable
private fun ArchiveView(archive: ArchiveState, vm: OnlineMusicViewModel) {
    val c = archive.collection
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.online_back)) }
            Column(Modifier.weight(1f)) {
                Text(c.collection, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!c.artist.isNullOrBlank()) Text(c.artist, style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            archive.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Text(stringResource(R.string.online_reading_archive), Modifier.padding(top = 12.dp))
                }
            }
            archive.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(archive.error.res)); OutlinedButton(onClick = { vm.openCollection(c) }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.online_retry)) }
                }
            }
            else -> {
                Button(onClick = { vm.downloadFromArchive(c, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Icon(Icons.Filled.Download, null); Text(stringResource(R.string.online_download_all), Modifier.padding(start = 8.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(archive.entries, key = { it.path }) { e -> EntryRow(c, e, vm); HorizontalDivider() }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(c: OnlineCollection, e: ArchiveTrack, vm: OnlineMusicViewModel) {
    ListItem(
        modifier = Modifier.clickable { vm.playFromArchive(c, e) },
        leadingContent = { Icon(Icons.Filled.PlayArrow, null) },
        headlineContent = { Text(e.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(mb(e.size) + " MB") },
        trailingContent = { IconButton(onClick = { vm.downloadFromArchive(c, setOf(e.path)) }) { Icon(Icons.Filled.Download, stringResource(R.string.online_download_song)) } },
    )
}
