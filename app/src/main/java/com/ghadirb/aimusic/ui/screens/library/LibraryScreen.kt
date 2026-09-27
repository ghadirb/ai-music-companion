package com.ghadirb.aimusic.ui.screens.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
import com.ghadirb.aimusic.library.EnergyBand
import com.ghadirb.aimusic.library.LibraryFilter
import com.ghadirb.aimusic.library.LibrarySort
import com.ghadirb.aimusic.library.TrackFileOps
import com.ghadirb.aimusic.ui.components.LocalQueueActions
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit,
    currentTrackId: Long?,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (Long, String) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LibraryViewModel(repository, onLibraryChanged = {
                    AudioAnalysisWorker.enqueueNow(context.applicationContext)
                    // Pick up new .lrc files in the granted music folder(s) (off the main thread).
                    Thread { runCatching { com.ghadirb.aimusic.lyrics.LyricsSource(context.applicationContext).takeIf { it.hasFolder() }?.refreshIndex() } }.start()
                })
            }
        }
    )
    val state by viewModel.uiState.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    var trackForPlaylistPicker by remember { mutableStateOf<TrackEntity?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val searchHistoryPreferences = remember(context) {
        context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)
    }
    var recentSearches by remember { mutableStateOf(readSearchHistory(searchHistoryPreferences)) }

    // Save only deliberate, settled queries. This is real local search history,
    // not a fabricated discovery feed, and remains useful offline.
    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.length < 2) return@LaunchedEffect
        delay(650)
        val updated = (listOf(query) + recentSearches.filterNot { it.equals(query, ignoreCase = true) }).take(6)
        recentSearches = updated
        writeSearchHistory(searchHistoryPreferences, updated)
    }

    // --- Multi-select: delete from device, or copy/move selected tracks to another folder ---
    val scope = rememberCoroutineScope()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Ready.tracks is filtered/sorted by the active filter; search.tracks covers the search view.
    // Together they cover every track a selection or a row action could currently refer to.
    val tracksById = remember(state) {
        val ready = state as? LibraryUiState.Ready
        (ready?.tracks.orEmpty() + ready?.search?.tracks.orEmpty()).associateBy { it.id }
    }
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingCopyIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingMove by remember { mutableStateOf(false) }
    var copyOrMoveTarget by remember { mutableStateOf<Set<Long>?>(null) }
    var trackPendingDeleteConfirm by remember { mutableStateOf<TrackEntity?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingDeleteIds.isNotEmpty()) {
            viewModel.removeFromLibrary(pendingDeleteIds)
            scope.launch { snackbarHostState.showSnackbar("${pendingDeleteIds.size} آهنگ حذف شد") }
        }
        pendingDeleteIds = emptySet()
    }

    fun requestDeleteTracks(targets: List<TrackEntity>) {
        if (targets.isEmpty()) return
        scope.launch {
            val idByUri = targets.mapNotNull { t -> runCatching { Uri.parse(t.path) }.getOrNull()?.let { it to t.id } }.toMap()
            when (val outcome = TrackFileOps.requestDelete(context, idByUri.keys.toList())) {
                is TrackFileOps.DeleteOutcome.Deleted -> {
                    val ids = outcome.uris.mapNotNull { idByUri[it] }
                    viewModel.removeFromLibrary(ids)
                    snackbarHostState.showSnackbar("${ids.size} آهنگ حذف شد")
                }
                is TrackFileOps.DeleteOutcome.NeedsConfirmation -> {
                    pendingDeleteIds = outcome.uris.mapNotNull { idByUri[it] }.toSet()
                    deleteLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                }
                is TrackFileOps.DeleteOutcome.Failed -> {
                    snackbarHostState.showSnackbar(outcome.reason)
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val ids = pendingCopyIds
        val move = pendingMove
        pendingCopyIds = emptySet()
        if (treeUri == null || ids.isEmpty()) return@rememberLauncherForActivityResult
        val targets = ids.mapNotNull { tracksById[it] }
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val pairs = targets.mapNotNull { t -> runCatching { Uri.parse(t.path) }.getOrNull()?.let { it to t.title } }
            val result = TrackFileOps.copyToFolder(context, treeUri, pairs)
            val verb = if (move) "انتقال" else "کپی"
            snackbarHostState.showSnackbar(
                if (result.failed.isEmpty()) "${result.succeeded.size} آهنگ $verb شد"
                else "${result.succeeded.size} آهنگ $verb شد، ${result.failed.size} مورد ناموفق بود"
            )
            if (move && result.succeeded.isNotEmpty()) {
                val movedTracks = targets.filter { t -> runCatching { Uri.parse(t.path) }.getOrNull() in result.succeeded }
                requestDeleteTracks(movedTracks)
            } else {
                viewModel.clearSelection()
            }
        }
    }

    trackPendingDeleteConfirm?.let { track ->
        AlertDialog(
            onDismissRequest = { trackPendingDeleteConfirm = null },
            title = { Text("حذف آهنگ") },
            text = { Text("«${track.title}» از دستگاه حذف شود؟ این کار قابل بازگشت نیست.") },
            confirmButton = {
                TextButton(onClick = {
                    trackPendingDeleteConfirm = null
                    requestDeleteTracks(listOf(track))
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { trackPendingDeleteConfirm = null }) { Text("انصراف") } }
        )
    }

    copyOrMoveTarget?.let { ids ->
        AlertDialog(
            onDismissRequest = { copyOrMoveTarget = null },
            title = { Text("کپی یا انتقال") },
            text = { Text("${ids.size} آهنگ انتخاب شده در پوشهٔ مقصد چه کاری انجام شود؟") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCopyIds = ids; pendingMove = false; copyOrMoveTarget = null
                    folderPickerLauncher.launch(null)
                }) { Text("فقط کپی") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingCopyIds = ids; pendingMove = true; copyOrMoveTarget = null
                        folderPickerLauncher.launch(null)
                    }) { Text("انتقال (حذف پس از کپی)") }
                    TextButton(onClick = { copyOrMoveTarget = null }) { Text("انصراف") }
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} انتخاب شده") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "لغو انتخاب")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { copyOrMoveTarget = selectedIds },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = "کپی یا انتقال به پوشهٔ دیگر")
                        }
                        IconButton(
                            onClick = { requestDeleteTracks(selectedIds.mapNotNull { tracksById[it] }) },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف از دستگاه")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { viewModel.scanLibrary() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.scan_library))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                LibraryUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                LibraryUiState.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = null, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.empty_library), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.scanLibrary() }, enabled = !isScanning) {
                            Text(stringResource(R.string.scan_library))
                        }
                    }
                }
                is LibraryUiState.Ready -> Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it; viewModel.setQuery(it) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""; viewModel.setQuery("")
                                        } else {
                                            searchExpanded = false
                                        }
                                    }) {
                                        Icon(Icons.Filled.Close, contentDescription = "پاک‌کردن یا بستن جست‌وجو")
                                    }
                                },
                                placeholder = { Text("جست‌وجو در کتابخانه", maxLines = 1) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester)
                            )
                            LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                        } else {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "جست‌وجو در کتابخانه")
                            }
                            Text(
                                "کتابخانه",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        SortMenu(current.sort, onSelect = viewModel::setSort)
                    }
                    FilterRow(current, onChange = viewModel::setFilter)
                    scanError?.let { message ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.scanLibrary() }) { Text("تلاش دوباره") }
                        }
                    }
                    val search = current.search
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (searchExpanded && search == null) {
                            item {
                                SearchStartContent(
                                    recentSearches = recentSearches,
                                    onSearch = { query ->
                                        searchQuery = query
                                        viewModel.setQuery(query)
                                    },
                                    onClearHistory = {
                                        recentSearches = emptyList()
                                        writeSearchHistory(searchHistoryPreferences, emptyList())
                                    }
                                )
                            }
                        } else if (search != null) {
                            if (search.isEmpty) {
                                item { EmptyResult() }
                            } else {
                                if (search.artists.isNotEmpty()) item {
                                    ChipSection("خواننده‌ها", search.artists) { onArtistClick(it) }
                                }
                                if (search.albums.isNotEmpty()) item {
                                    ChipSection("آلبوم‌ها", search.albums) { onAlbumClick(it) }
                                }
                                if (search.genres.isNotEmpty()) item {
                                    ChipSection("سبک‌ها", search.genres) { genre ->
                                        searchQuery = ""; viewModel.setQuery("")
                                        viewModel.setFilter(current.filter.copy(genre = genre))
                                    }
                                }
                                if (search.playlists.isNotEmpty()) item {
                                    ChipSection("پلی‌لیست‌ها", search.playlists.map { it.name }) { name ->
                                        search.playlists.firstOrNull { it.name == name }?.let { onPlaylistClick(it.id, it.name) }
                                    }
                                }
                                if (search.tracks.isNotEmpty()) {
                                    item {
                                        Text(
                                            "آهنگ‌ها (${search.tracks.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    items(search.tracks, key = { it.id }) { track ->
                                        TrackRow(
                                            track = track,
                                            isCurrentTrack = track.id == currentTrackId,
                                            onClick = { onTrackClick(track, search.tracks) },
                                            onFavoriteClick = { viewModel.toggleFavorite(track) },
                                            onAddToPlaylistClick = { trackForPlaylistPicker = track },
                                            onNotInterestedClick = { viewModel.toggleNotInterested(track) },
                                            onDeleteClick = { trackPendingDeleteConfirm = track },
                                            onCopyMoveClick = { copyOrMoveTarget = setOf(track.id) },
                                            selectionMode = isSelectionMode,
                                            selected = track.id in selectedIds,
                                            onToggleSelect = { viewModel.toggleSelection(track.id) },
                                            onLongPress = { viewModel.startSelection(track.id) }
                                        )
                                    }
                                }
                            }
                        } else {
                            if (current.tracks.isEmpty()) {
                                item { EmptyResult() }
                            }
                            items(current.tracks, key = { it.id }) { track ->
                                TrackRow(
                                    track = track,
                                    isCurrentTrack = track.id == currentTrackId,
                                    onClick = { onTrackClick(track, current.tracks) },
                                    onFavoriteClick = { viewModel.toggleFavorite(track) },
                                    onAddToPlaylistClick = { trackForPlaylistPicker = track },
                                    onNotInterestedClick = { viewModel.toggleNotInterested(track) },
                                    onDeleteClick = { trackPendingDeleteConfirm = track },
                                    onCopyMoveClick = { copyOrMoveTarget = setOf(track.id) },
                                    selectionMode = isSelectionMode,
                                    selected = track.id in selectedIds,
                                    onToggleSelect = { viewModel.toggleSelection(track.id) },
                                    onLongPress = { viewModel.startSelection(track.id) }
                                )
                            }
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
private fun EmptyResult() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("نتیجه‌ای پیدا نشد.", style = MaterialTheme.typography.titleSmall)
        Text(
            "نام آهنگ، خواننده، آلبوم یا پلی‌لیست را با املای دیگری امتحان کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun SearchStartContent(
    recentSearches: List<String>,
    onSearch: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        if (recentSearches.isEmpty()) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("دنبال چه چیزی می‌گردید؟", style = MaterialTheme.typography.titleMedium)
            Text(
                "آهنگ، خواننده، آلبوم یا پلی‌لیست را جست‌وجو کنید.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("جست‌وجوهای اخیر", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearHistory) { Text("پاک‌کردن") }
            }
            LazyRow(contentPadding = PaddingValues(top = 8.dp)) {
                items(recentSearches, key = { it }) { query ->
                    AssistChip(
                        onClick = { onSearch(query) },
                        label = { Text(query, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

private const val SEARCH_HISTORY_KEY = "library_search_history"
private const val SEARCH_HISTORY_SEPARATOR = "\u001F"

private fun readSearchHistory(preferences: android.content.SharedPreferences): List<String> =
    preferences.getString(SEARCH_HISTORY_KEY, null)
        ?.split(SEARCH_HISTORY_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.take(6)
        .orEmpty()

private fun writeSearchHistory(preferences: android.content.SharedPreferences, queries: List<String>) {
    preferences.edit()
        .putString(SEARCH_HISTORY_KEY, queries.joinToString(SEARCH_HISTORY_SEPARATOR) { it.replace(SEARCH_HISTORY_SEPARATOR, "") })
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipSection(title: String, values: List<String>, onClick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(values, key = { it }) { value ->
                AssistChip(
                    onClick = { onClick(value) },
                    label = { Text(value, maxLines = 1) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

private fun sortLabel(sort: LibrarySort) = when (sort) {
    LibrarySort.RECENTLY_ADDED -> "تازه‌ترین افزوده‌ها"
    LibrarySort.RECENTLY_PLAYED -> "اخیراً پخش‌شده"
    LibrarySort.MOST_PLAYED -> "پرپخش‌ترین"
    LibrarySort.TITLE -> "نام آهنگ"
    LibrarySort.ARTIST -> "خواننده"
    LibrarySort.ALBUM -> "آلبوم"
    LibrarySort.DURATION -> "مدت زمان"
    LibrarySort.FAVORITE -> "علاقه‌مندی‌ها"
}

private fun moodLabel(mood: String) = when (mood) {
    "calm" -> "آرام"
    "energetic" -> "پرانرژی"
    "happy" -> "شاد"
    "sad" -> "غمگین"
    "neutral" -> "خنثی"
    else -> mood
}

private fun energyLabel(band: EnergyBand) = when (band) {
    EnergyBand.LOW -> "انرژی کم"
    EnergyBand.MEDIUM -> "انرژی متوسط"
    EnergyBand.HIGH -> "انرژی بالا"
}

@Composable
private fun SortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Sort, contentDescription = "مرتب‌سازی: ${sortLabel(current)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.values().forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sortLabel(sort), fontWeight = if (sort == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onSelect(sort); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(state: LibraryUiState.Ready, onChange: (LibraryFilter) -> Unit) {
    val filter = state.filter
    var genreMenu by remember { mutableStateOf(false) }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
        item {
            FilterChip(
                selected = filter.favoritesOnly,
                onClick = { onChange(filter.copy(favoritesOnly = !filter.favoritesOnly)) },
                label = { Text("علاقه‌مندی‌ها") },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        items(EnergyBand.values().toList(), key = { it.name }) { band ->
            FilterChip(
                selected = filter.energy == band,
                onClick = { onChange(filter.copy(energy = if (filter.energy == band) null else band)) },
                label = { Text(energyLabel(band)) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        items(state.moods, key = { "mood-$it" }) { mood ->
            FilterChip(
                selected = filter.mood == mood,
                onClick = { onChange(filter.copy(mood = if (filter.mood == mood) null else mood)) },
                label = { Text(moodLabel(mood)) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (state.genres.isNotEmpty()) {
            item {
                Box(Modifier.padding(end = 8.dp)) {
                    FilterChip(
                        selected = filter.genre != null,
                        onClick = { genreMenu = true },
                        label = { Text(filter.genre ?: "سبک") }
                    )
                    DropdownMenu(expanded = genreMenu, onDismissRequest = { genreMenu = false }) {
                        DropdownMenuItem(text = { Text("همهٔ سبک‌ها") }, onClick = { onChange(filter.copy(genre = null)); genreMenu = false })
                        state.genres.forEach { genre ->
                            DropdownMenuItem(text = { Text(genre) }, onClick = { onChange(filter.copy(genre = genre)); genreMenu = false })
                        }
                    }
                }
            }
        }
        if (filter.isActive) {
            item { TextButton(onClick = { onChange(LibraryFilter()) }) { Text("پاک‌کردن فیلتر") } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onNotInterestedClick: (() -> Unit)? = null,
    isCurrentTrack: Boolean = false,
    /** Extra trailing action rendered before the favourite/menu icons (e.g. "remove from playlist"). */
    trailingExtra: (@Composable () -> Unit)? = null,
    /** Deletes this single track from the device (shown in the row's own menu). */
    onDeleteClick: (() -> Unit)? = null,
    /** Copies/moves just this single track to another folder (shown in the row's own menu). */
    onCopyMoveClick: (() -> Unit)? = null,
    /** True while the Library is in multi-select mode: shows a checkbox and disables normal tap-to-play. */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    /** Long-press anywhere on the row enters selection mode with this track picked. */
    onLongPress: (() -> Unit)? = null
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
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "انتخاب شده" else "انتخاب نشده",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            } else if (track.albumArtUri != null) {
                AsyncImage(
                    model = track.albumArtUri,
                    contentDescription = track.album,
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(Icons.Filled.MusicNote),
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
            }
        },
        trailingContent = {
            if (selectionMode) return@ListItem
            val queueActions = LocalQueueActions.current
            var menuOpen by remember { mutableStateOf(false) }
            Row {
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (track.isFavorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها"
                    )
                }
                if (onAddToPlaylistClick != null || queueActions != null || onNotInterestedClick != null || onDeleteClick != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "گزینه‌های بیشتر")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (queueActions != null) {
                                DropdownMenuItem(
                                    text = { Text("پخش بعدی") },
                                    onClick = { queueActions.playNext(track); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("افزودن به صف پخش") },
                                    onClick = { queueActions.addToQueue(track); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("📻 رادیو از این آهنگ") },
                                    onClick = { queueActions.startRadio(listOf(track), track.title); menuOpen = false }
                                )
                            }
                            if (onAddToPlaylistClick != null) {
                                DropdownMenuItem(
                                    text = { Text("افزودن به پلی‌لیست") },
                                    onClick = { onAddToPlaylistClick(); menuOpen = false }
                                )
                            }
                            if (onCopyMoveClick != null) {
                                DropdownMenuItem(
                                    text = { Text("کپی/انتقال به پوشهٔ دیگر") },
                                    onClick = { onCopyMoveClick(); menuOpen = false }
                                )
                            }
                            if (onNotInterestedClick != null) {
                                DropdownMenuItem(
                                    text = { Text(if (track.notInterested) "برداشتن «علاقه‌ای ندارم»" else "علاقه‌ای ندارم") },
                                    onClick = { onNotInterestedClick(); menuOpen = false }
                                )
                            }
                            if (onDeleteClick != null) {
                                DropdownMenuItem(
                                    text = { Text("حذف از دستگاه", color = MaterialTheme.colorScheme.error) },
                                    onClick = { onDeleteClick(); menuOpen = false }
                                )
                            }
                        }
                    }
                }
                trailingExtra?.invoke()
            }
        },
        modifier = if (selectionMode) {
            Modifier.clickable(onClick = { onToggleSelect?.invoke() })
        } else {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
        }
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
