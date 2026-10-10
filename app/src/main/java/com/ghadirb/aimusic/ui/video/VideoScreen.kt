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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
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
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sync with MediaStore every time the tab is opened (off the main thread inside the scanner).
    LaunchedEffect(Unit) { vm.refresh() }

    var pendingDelete by remember { mutableStateOf<VideoEntity?>(null) }
    var awaitingSystemConfirm by remember { mutableStateOf<VideoEntity?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val video = awaitingSystemConfirm
        awaitingSystemConfirm = null
        if (video != null && result.resultCode == android.app.Activity.RESULT_OK) vm.onDeleted(listOf(video))
    }

    fun open(video: VideoEntity) {
        VideoPlayerActivity.start(context, video.id, state.videos.map { it.id })
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف ویدئو") },
            text = { Text("آیا مطمئن هستید؟ «${video.displayName}» از حافظهٔ گوشی حذف می‌شود.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        when (val outcome = TrackFileOps.requestDelete(context, listOf(Uri.parse(video.contentUri)))) {
                            is TrackFileOps.DeleteOutcome.Deleted -> if (outcome.uris.isNotEmpty()) vm.onDeleted(listOf(video))
                            is TrackFileOps.DeleteOutcome.NeedsConfirmation -> {
                                awaitingSystemConfirm = video
                                deleteLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                            }
                            is TrackFileOps.DeleteOutcome.Failed -> android.widget.Toast.makeText(context, outcome.reason, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("انصراف") } }
        )
    }

    Column(modifier.fillMaxSize()) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoFilter.values().forEach { f ->
                    FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                }
            }
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
            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "بازخوانی") }
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
            state.videos.isEmpty() && state.syncing && state.totalCount == 0 -> LoadingState()
            state.videos.isEmpty() && state.totalCount == 0 -> EmptyState(
                Icons.Filled.VideoLibrary,
                "ویدئویی پیدا نشد",
                if (state.syncFailed) "برای نمایش ویدئوهای گوشی، دسترسی رسانه لازم است."
                else "ویدئوهای ذخیره‌شده روی گوشی پس از اعطای دسترسی در اینجا نمایش داده می‌شوند."
            )
            state.videos.isEmpty() -> EmptyState(
                Icons.Filled.VideoLibrary,
                if (state.filter == VideoFilter.RECENT && state.query.isBlank()) "هنوز ویدئویی پخش نکرده‌اید" else "نتیجه‌ای پیدا نشد"
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
                    VideoGridItem(v, onClick = { open(v) }, onDelete = { pendingDelete = v })
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.videos, key = { it.id }) { v ->
                    VideoListItem(v, onClick = { open(v) }, onDelete = { pendingDelete = v })
                }
            }
        }
    }
}
