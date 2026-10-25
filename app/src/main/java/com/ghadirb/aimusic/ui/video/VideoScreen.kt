package com.ghadirb.aimusic.ui.video

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.repository.VideoRepository
import com.ghadirb.aimusic.library.TrackFileOps
import com.ghadirb.aimusic.ui.components.EmptyState
import com.ghadirb.aimusic.ui.components.LoadingState
import com.ghadirb.aimusic.video.VideoFilter
import com.ghadirb.aimusic.video.VideoLibraryQuery
import com.ghadirb.aimusic.video.VideoSort
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.launch

/**
 * The "ویدئو" tab: local videos only (MediaStore), independent of the music Library.
 * Video permission is requested here, when the user actually opens the tab.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VideoScreen(repository: VideoRepository, modifier: Modifier = Modifier) {
    val permission = rememberPermissionState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    )
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    if (permission.status is PermissionStatus.Granted) {
        VideoLibraryContent(repository, modifier)
    } else {
        val permanentlyDenied = askedOnce && !permission.status.shouldShowRationale
        VideoPermissionState(
            askedBefore = askedOnce,
            permanentlyDenied = permanentlyDenied,
            onRequest = { askedOnce = true; permission.launchPermissionRequest() },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = modifier
        )
    }
}

@Composable
private fun VideoPermissionState(
    askedBefore: Boolean,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (askedBefore) "برای نمایش ویدئوهای گوشی، دسترسی رسانه لازم است."
            else "برای نمایش و پخش ویدئوهای ذخیره‌شده روی گوشی، به اجازهٔ دسترسی به ویدئوها نیاز داریم. هیچ فایلی از دستگاه خارج نمی‌شود.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        if (permanentlyDenied) {
            Text(
                "دسترسی قبلاً رد شده است. آن را از تنظیمات برنامه فعال کنید.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("باز کردن تنظیمات") }
        } else {
            Button(onClick = onRequest) { Text(if (askedBefore) "تلاش دوباره" else "اعطای دسترسی") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoLibraryContent(repository: VideoRepository, modifier: Modifier = Modifier) {
    val vm: VideoViewModel = viewModel(factory = viewModelFactory { initializer { VideoViewModel(repository) } })
    val appContext = LocalContext.current.applicationContext as com.ghadirb.aimusic.AiMusicApp
    val plVm: VideoPlaylistViewModel = viewModel(factory = viewModelFactory {
        initializer {
            VideoPlaylistViewModel(
                com.ghadirb.aimusic.data.repository.VideoPlaylistRepository(appContext.database.videoPlaylistDao()),
                repository
            )
        }
    })
    var showPlaylists by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<VideoEntity?>(null) }
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sync with MediaStore every time the tab is opened (off the main thread inside the scanner).
    LaunchedEffect(Unit) { vm.refresh() }

    var pendingDelete by remember { mutableStateOf<VideoEntity?>(null) }
    val deletingByUri = remember { mutableMapOf<Uri, VideoEntity>() }
    val deleteFlow = com.ghadirb.aimusic.ui.common.rememberDeleteFlow(
        onDeleted = { uris ->
            val gone = uris.mapNotNull { deletingByUri[it] }
            if (gone.isNotEmpty()) {
                vm.onDeleted(gone)
                android.widget.Toast.makeText(context, "ویدئو از حافظهٔ گوشی حذف شد", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onMessage = { text -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }
    )

    // ---- file operations: rename (MediaStore), copy / move (system folder picker + shared delete flow) ----
    var renameTarget by remember { mutableStateOf<VideoEntity?>(null) }
    var pendingRename by remember { mutableStateOf<Pair<VideoEntity, String>?>(null) }
    var copyMoveTarget by remember { mutableStateOf<Pair<List<VideoEntity>, Boolean>?>(null) }
    // Selection mode (long press) + bulk actions
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var bulkPlaylist by remember { mutableStateOf<List<VideoEntity>?>(null) }
    var bulkDelete by remember { mutableStateOf<List<VideoEntity>?>(null) }
    // Id-preserving move: which videos are waiting for a destination folder / a system permission
    var moveDialog by remember { mutableStateOf<List<VideoEntity>?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<List<VideoEntity>, String>?>(null) }
    val moveRef = remember { arrayOfNulls<(List<VideoEntity>, String) -> Unit>(1) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var copyProgress by remember { mutableStateOf<com.ghadirb.aimusic.library.CopyProgress?>(null) }
    var copyJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    val renameRef = remember { arrayOfNulls<(VideoEntity, String) -> Unit>(1) }

    val renamePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val p = pendingRename
        pendingRename = null
        if (p != null && result.resultCode == android.app.Activity.RESULT_OK) renameRef[0]?.invoke(p.first, p.second)
        else toast("تغییر نام لغو شد.")
    }
    renameRef[0] = { video, newName ->
        scope.launch {
            when (val o = com.ghadirb.aimusic.library.VideoFileOps.rename(context, Uri.parse(video.contentUri), newName)) {
                is com.ghadirb.aimusic.library.VideoFileOps.RenameOutcome.Done -> { toast("نام فایل تغییر کرد."); vm.refresh() }
                is com.ghadirb.aimusic.library.VideoFileOps.RenameOutcome.NeedsPermission -> {
                    pendingRename = video to newName
                    renamePermissionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(o.intentSender).build())
                }
                is com.ghadirb.aimusic.library.VideoFileOps.RenameOutcome.Failed -> toast(o.reason)
            }
        }
    }

    val movePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val p = pendingMove
        pendingMove = null
        if (p != null && result.resultCode == android.app.Activity.RESULT_OK) moveRef[0]?.invoke(p.first, p.second)
        else toast("انتقال لغو شد.")
    }
    moveRef[0] = { videos, relPath ->
        scope.launch {
            val uris = videos.map { Uri.parse(it.contentUri) }
            when (val o = com.ghadirb.aimusic.library.VideoFileOps.moveToRelativePath(context, uris, relPath)) {
                is com.ghadirb.aimusic.library.VideoFileOps.MoveOutcome.Done -> {
                    toast("${o.moved} ویدئو منتقل شد؛ پسندیده‌ها و پلی‌لیست‌ها حفظ شدند.")
                    selected = emptySet(); vm.refresh(deep = true)
                }
                is com.ghadirb.aimusic.library.VideoFileOps.MoveOutcome.NeedsPermission -> {
                    pendingMove = videos to relPath
                    movePermissionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(o.intentSender).build())
                }
                is com.ghadirb.aimusic.library.VideoFileOps.MoveOutcome.Unsupported ->
                    toast(o.reason + " می‌توانید «انتخاب پوشهٔ دیگر» را بزنید.")
            }
        }
    }

    val treeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        val op = copyMoveTarget
        copyMoveTarget = null
        if (tree == null || op == null) return@rememberLauncherForActivityResult
        val (videos, isMove) = op
        busyText = if (isMove) "در حال انتقال…" else "در حال کپی…"
        copyProgress = null
        copyJob = scope.launch {
            try {
                val pairs = videos.map { Uri.parse(it.contentUri) to it.displayName.substringBeforeLast('.') }
                val result = com.ghadirb.aimusic.library.TrackFileOps.copyToFolder(context, tree, pairs) { p -> copyProgress = p }
                val copiedUris = result.succeeded.toSet()
                when {
                    copiedUris.isEmpty() ->
                        toast("کپی انجام نشد (فضای کافی نیست یا دسترسی رد شد). فایل اصلی دست‌نخورده ماند.")
                    isMove -> {
                        // The original is deleted ONLY after its copy succeeded, and only with the user's system confirmation.
                        // The copy gets a new id, so favourites / resume / playlists are remembered and handed to it later.
                        val moved = videos.filter { Uri.parse(it.contentUri) in copiedUris }
                        vm.rememberForMove(moved)
                        toast("کپی شد؛ برای تکمیل انتقال، حذف فایل اصلی را تأیید کنید.")
                        deletingByUri.clear(); moved.forEach { deletingByUri[Uri.parse(it.contentUri)] = it }
                        selected = emptySet()
                        deleteFlow(moved.map { Uri.parse(it.contentUri) })
                        vm.refresh(deep = true)
                        if (result.failed.isNotEmpty()) toast("${result.failed.size} فایل کپی نشد و دست‌نخورده ماند.")
                    }
                    else -> {
                        toast(if (result.failed.isEmpty()) "فایل‌ها کپی شدند." else "${copiedUris.size} فایل کپی شد، ${result.failed.size} فایل نشد.")
                        selected = emptySet(); vm.refresh(deep = true)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("لغو شد؛ فایل نیمه‌کاره پاک شد و فایل اصلی دست‌نخورده ماند.")
                throw e
            } finally {
                busyText = null
                copyProgress = null
                copyJob = null
            }
        }
    }

    moveDialog?.let { videos ->
        var folder by remember { mutableStateOf("") }
        val rel = com.ghadirb.aimusic.video.VideoFolderPaths.relativePath(folder)
        AlertDialog(
            onDismissRequest = { moveDialog = null },
            title = { Text("انتقال ${videos.size} ویدئو") },
            text = {
                Column {
                    Text("به پوشهٔ Movies/ … (خالی = خود Movies). پسندیده‌ها و پلی‌لیست‌ها حفظ می‌شوند.", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.material3.OutlinedTextField(
                        value = folder, onValueChange = { folder = it }, singleLine = true,
                        placeholder = { Text("مثلاً Family") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (rel == null) Text("نام پوشه معتبر نیست.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    else Text("مقصد: $rel", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { moveDialog = null; copyMoveTarget = videos to true; treeLauncher.launch(null) }) {
                        Text("انتخاب پوشهٔ دیگر (کپی و حذف)")
                    }
                }
            },
            confirmButton = { TextButton(enabled = rel != null, onClick = { moveDialog = null; moveRef[0]?.invoke(videos, rel!!) }) { Text("انتقال") } },
            dismissButton = { TextButton(onClick = { moveDialog = null }) { Text("انصراف") } }
        )
    }
    bulkPlaylist?.let { AddManyToPlaylistDialog(it, plVm, onDone = { bulkPlaylist = null; selected = emptySet() }, onDismiss = { bulkPlaylist = null }) }
    bulkDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { bulkDelete = null },
            title = { Text("حذف ${list.size} ویدئو") },
            text = { Text("این ویدئوها از حافظهٔ گوشی حذف می‌شوند و قابل بازگشت نیستند.") },
            confirmButton = {
                TextButton(onClick = {
                    bulkDelete = null; selected = emptySet()
                    deletingByUri.clear(); list.forEach { deletingByUri[Uri.parse(it.contentUri)] = it }
                    deleteFlow(list.map { Uri.parse(it.contentUri) })
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { bulkDelete = null }) { Text("انصراف") } }
        )
    }

    renameTarget?.let { video ->
        var text by remember(video.id) { mutableStateOf(video.displayName.substringBeforeLast('.')) }
        val check = com.ghadirb.aimusic.video.VideoFileNames.check(text, video.displayName)
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("تغییر نام") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                    if (check.error != null && text.isNotBlank()) Text(check.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                TextButton(enabled = check.name != null, onClick = { renameTarget = null; renameRef[0]?.invoke(video, check.name!!) }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("انصراف") } }
        )
    }
    busyText?.let { msg ->
        val progress = copyProgress
        AlertDialog(
            onDismissRequest = {},
            title = { Text(msg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (progress != null && progress.totalBytes > 0) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${progress.percent}٪   (${VideoLibraryQuery.formatSize(progress.doneBytes)} از ${VideoLibraryQuery.formatSize(progress.totalBytes)})")
                        if (progress.fileCount > 1) Text("فایل ${progress.fileIndex} از ${progress.fileCount}", style = MaterialTheme.typography.labelMedium)
                        Text(progress.fileName, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("در حال آماده‌سازی…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { copyJob?.cancel() }) { Text("لغو") } }
        )
    }

    fun open(video: VideoEntity) {
        VideoPlayerActivity.start(context, video.id, state.videos.map { it.id })
    }

    addTarget?.let { AddToPlaylistDialog(it, plVm, onDismiss = { addTarget = null }) }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف ویدئو") },
            text = { Text("آیا مطمئن هستید؟ «${video.displayName}» از حافظهٔ گوشی حذف می‌شود.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val uri = Uri.parse(video.contentUri)
                    deletingByUri.clear()
                    deletingByUri[uri] = video
                    deleteFlow(listOf(uri))
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("انصراف") } }
        )
    }

    if (selected.isNotEmpty()) androidx.activity.compose.BackHandler { selected = emptySet() }
    Column(modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            val picked = state.videos.filter { it.id in selected }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Clear, contentDescription = "لغو انتخاب") }
                Text("${picked.size} انتخاب", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { selected = state.videos.map { it.id }.toSet() }) { Text("همه") }
                IconButton(onClick = { vm.setFavorites(picked.map { it.id }, !picked.all { it.isFavorite }); selected = emptySet() }) {
                    Icon(Icons.Filled.Favorite, contentDescription = "پسندیده")
                }
                IconButton(onClick = { bulkPlaylist = picked }) { Icon(Icons.Filled.PlaylistAdd, contentDescription = "افزودن به پلی‌لیست") }
                IconButton(onClick = { moveDialog = picked }) { Icon(Icons.Filled.DriveFileMove, contentDescription = "انتقال") }
                IconButton(onClick = { copyMoveTarget = picked to false; treeLauncher.launch(null) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "کپی") }
                IconButton(onClick = { bulkDelete = picked }) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
            }
            androidx.compose.material3.HorizontalDivider()
        }
        // Search
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("جستجوی ویدئو") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "پاک کردن") }
                }
            }
        )
        // Filter chips + sort / view / refresh
        // Chips get their own full-width, scrollable line (they used to share it with 4 icon buttons and got cut off).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = showPlaylists, onClick = { showPlaylists = !showPlaylists }, label = { Text("پلی‌لیست‌ها") })
            VideoFilter.values().forEach { f ->
                FilterChip(selected = !showPlaylists && state.filter == f, onClick = { showPlaylists = false; vm.setFilter(f) }, label = { Text(f.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            var sortOpen by remember { mutableStateOf(false) }
            Column {
                IconButton(onClick = { sortOpen = true }, enabled = state.filter == VideoFilter.ALL) {
                    Icon(Icons.Filled.Sort, contentDescription = "مرتب‌سازی")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    VideoSort.values().forEach { s ->
                        DropdownMenuItem(
                            text = { Text(if (s == state.sort) "✓ ${s.label}" else s.label) },
                            onClick = { vm.setSort(s); sortOpen = false }
                        )
                    }
                }
            }
            IconButton(onClick = vm::toggleByFolder) {
                if (state.byFolder) Icon(Icons.Filled.FolderOpen, contentDescription = "نمایش همهٔ ویدئوها")
                else Icon(Icons.Filled.Folder, contentDescription = "نمایش بر اساس پوشه")
            }
            IconButton(onClick = vm::toggleGrid) {
                if (state.grid) Icon(Icons.Filled.ViewList, contentDescription = "نمایش فهرستی")
                else Icon(Icons.Filled.GridView, contentDescription = "نمایش شبکه‌ای")
            }
            var confirmClear by remember { mutableStateOf(false) }
            if (state.filter == VideoFilter.RECENT || state.filter == VideoFilter.UNFINISHED) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Clear, contentDescription = "پاک‌کردن تاریخچه") }
            }
            if (confirmClear) AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("پاک‌کردن تاریخچه") },
                text = { Text("فهرست «اخیراً پخش‌شده» و محل ادامهٔ تماشا پاک می‌شود. خود ویدئوها و پسندیده‌ها حذف نمی‌شوند.") },
                confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("پاک کن") } },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("انصراف") } }
            )
            IconButton(onClick = { vm.refresh(deep = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "بازخوانی") }
        }
        if (state.syncing && state.videos.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())

        // Inside an opened folder: back to the folder list (also bound to the system back button).
        if (state.byFolder && state.openFolder != null) {
            BackHandler(onBack = vm::closeFolder)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = vm::closeFolder) { Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت به پوشه‌ها") }
                Text(state.openFolderName, style = MaterialTheme.typography.titleMedium)
            }
        }

        when {
            showPlaylists -> VideoPlaylistsPane(
                vm = plVm,
                onPlay = { queue, start -> VideoPlayerActivity.start(context, start.id, queue.map { it.id }) }
            )
            state.videos.isEmpty() && state.syncing && state.totalCount == 0 -> LoadingState()
            state.videos.isEmpty() && state.totalCount == 0 -> EmptyState(
                Icons.Filled.VideoLibrary,
                "ویدئویی پیدا نشد",
                if (state.syncFailed) "برای نمایش ویدئوهای گوشی، دسترسی رسانه لازم است."
                else "ویدئوهای ذخیره‌شده روی گوشی پس از اعطای دسترسی در اینجا نمایش داده می‌شوند."
            )
            state.videos.isEmpty() -> EmptyState(
                Icons.Filled.VideoLibrary,
                if (state.query.isNotBlank()) "نتیجه‌ای پیدا نشد" else when (state.filter) {
                    VideoFilter.RECENT -> "هنوز ویدئویی پخش نکرده‌اید"
                    VideoFilter.FAVORITES -> "هنوز ویدئویی را پسند نکرده‌اید"
                    VideoFilter.UNFINISHED -> "ویدئوی ناتمامی ندارید"
                    VideoFilter.NEW -> "ویدئوی جدیدی نیست"
                    else -> "نتیجه‌ای پیدا نشد"
                }
            )
            state.byFolder && state.openFolder == null -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.folders, key = { it.key }) { f ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        headlineContent = { Text(f.name) },
                        supportingContent = { Text("${f.count} ویدئو") },
                        modifier = Modifier.clickable { vm.openFolder(f.key) }
                    )
                }
            }
            state.grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.videos, key = { it.id }) { v ->
                    VideoGridItem(v, onClick = { if (selected.isNotEmpty()) selected = if (v.id in selected) selected - v.id else selected + v.id else open(v) }, onDelete = { pendingDelete = v }, onFavorite = { vm.toggleFavorite(v) }, onStartOver = { vm.startOver(v) }, onAddToPlaylist = { addTarget = v }, onRename = { renameTarget = v }, onCopy = { copyMoveTarget = listOf(v) to false; treeLauncher.launch(null) }, onMove = { moveDialog = listOf(v) },
                        selected = v.id in selected, onLongClick = { selected = selected + v.id })
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.videos, key = { it.id }) { v ->
                    VideoListItem(v, onClick = { if (selected.isNotEmpty()) selected = if (v.id in selected) selected - v.id else selected + v.id else open(v) }, onDelete = { pendingDelete = v }, onFavorite = { vm.toggleFavorite(v) }, onStartOver = { vm.startOver(v) }, onAddToPlaylist = { addTarget = v }, onRename = { renameTarget = v }, onCopy = { copyMoveTarget = listOf(v) to false; treeLauncher.launch(null) }, onMove = { moveDialog = listOf(v) },
                        selected = v.id in selected, onLongClick = { selected = selected + v.id })
                }
            }
        }
    }
}
