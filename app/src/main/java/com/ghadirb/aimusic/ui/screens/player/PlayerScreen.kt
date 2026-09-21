package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.lyrics.LyricsState
import com.ghadirb.aimusic.playback.SleepTimerController
import com.ghadirb.aimusic.playback.SleepTimerState
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    repository: MusicRepository,
    playerViewModel: PlayerViewModel
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val similarTracks by playerViewModel.similarTracks.collectAsState()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val lyricsHasFolder by playerViewModel.lyricsHasFolder.collectAsState()
    val diagnosis by playerViewModel.diagnosis.collectAsState()
    val onlineAiMessage by playerViewModel.onlineAiMessage.collectAsState()
    val queue by playerViewModel.queue.collectAsState()
    val radio by playerViewModel.radio.collectAsState()
    val queueActions = com.ghadirb.aimusic.ui.components.LocalQueueActions.current
    val sleepState by playerViewModel.sleepTimer.collectAsState()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val currentTrack = uiState.currentTrack

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playerViewModel.importLyrics(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) playerViewModel.addLyricsFolder(uri)
    }

    // Poll playback position while this screen is visible (display only).
    LaunchedEffect(Unit) {
        while (true) {
            playerViewModel.refreshProgress()
            delay(300) // fine enough for line-by-line lyric highlighting
        }
    }

    if (currentTrack == null) {
        EmptyPlayer()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(280.dp).clip(RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (currentTrack.albumArtUri != null) {
                AsyncImage(
                    model = currentTrack.albumArtUri,
                    contentDescription = "جلد آلبوم ${currentTrack.album}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            currentTrack.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(currentTrack.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(20.dp))
        val duration = uiState.durationMs.coerceAtLeast(1L)
        Slider(
            value = uiState.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { playerViewModel.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.semantics { contentDescription = "نوار پیشرفت آهنگ" }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(uiState.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(uiState.durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playerViewModel.skipPrevious() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "آهنگ قبلی", modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(16.dp))
            FilledIconButton(onClick = { playerViewModel.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "توقف" else "پخش",
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = { playerViewModel.skipNext() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "آهنگ بعدی", modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { playerViewModel.toggleFavorite(currentTrack) }) {
            Icon(
                imageVector = if (currentTrack.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (currentTrack.isFavorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها")
        }

        radio?.let { session ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("📻 رادیو: ${session.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = playerViewModel::stopRadio) { Text("توقف رادیو") }
            }
        } ?: OutlinedButton(onClick = { queueActions?.startRadio(listOf(currentTrack), currentTrack.title) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("📻 رادیو از این آهنگ")
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { playerViewModel.setShuffle(!uiState.shuffleEnabled) }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = if (uiState.shuffleEnabled) "پخش تصادفی روشن" else "پخش تصادفی خاموش",
                    tint = if (uiState.shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = { playerViewModel.cycleRepeatMode() }) {
                Icon(
                    if (uiState.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = when (uiState.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "تکرار یک آهنگ"
                        Player.REPEAT_MODE_ALL -> "تکرار همهٔ آهنگ‌ها"
                        else -> "تکرار خاموش"
                    },
                    tint = if (uiState.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = { showLyrics = true }) {
                Icon(Icons.Filled.Lyrics, contentDescription = "متن آهنگ")
            }
            IconButton(onClick = { showSleepTimer = true }) {
                Icon(
                    Icons.Filled.Timer, contentDescription = "تایمر خواب",
                    tint = if (sleepState !is SleepTimerState.Off) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.Filled.QueueMusic, contentDescription = "صف پخش")
            }
        }

        SleepTimerStatus(sleepState, onCancel = playerViewModel::cancelSleepTimer)

        LyricsCard(
            state = lyrics,
            positionMs = uiState.positionMs,
            hasFolder = lyricsHasFolder,
            onExpand = { showLyrics = true },
            onSeek = playerViewModel::seekTo,
            onImportFile = { importLauncher.launch(arrayOf("*/*")) },
            onPickFolder = { folderLauncher.launch(null) },
            onDiagnose = playerViewModel::diagnoseLyrics
        )

        if (similarTracks.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("آهنگ‌های مشابه", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = playerViewModel::improveSimilarWithAi) { Text("بهبود با AI") }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow {
                items(similarTracks, key = { it.id }) { track ->
                    AssistChip(
                        onClick = { playerViewModel.playSimilarTrack(track) },
                        label = { Text(track.title, maxLines = 1) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
        onlineAiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (showLyrics) {
        LyricsFullScreen(
            title = currentTrack.title,
            state = lyrics,
            positionMs = uiState.positionMs,
            hasFolder = lyricsHasFolder,
            onDismiss = { showLyrics = false },
            onSeek = playerViewModel::seekTo,
            onImportFile = { importLauncher.launch(arrayOf("*/*")) },
            onPickFolder = { folderLauncher.launch(null) },
            onDiagnose = playerViewModel::diagnoseLyrics
        )
    }
    diagnosis?.let { text ->
        AlertDialog(
            onDismissRequest = playerViewModel::dismissDiagnosis,
            title = { Text("عیب‌یابی متن آهنگ") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = playerViewModel::dismissDiagnosis) { Text("بستن") } }
        )
    }
    if (showSleepTimer) {
        SleepTimerDialog(
            state = sleepState,
            onMinutes = { minutes -> playerViewModel.startSleepTimer(minutes); showSleepTimer = false },
            onEndOfTrack = { playerViewModel.startSleepTimerEndOfTrack(); showSleepTimer = false },
            onCancelTimer = { playerViewModel.cancelSleepTimer(); showSleepTimer = false },
            onDismiss = { showSleepTimer = false }
        )
    }
    if (showQueue) {
        QueueSheet(
            queue = queue,
            currentIndex = uiState.queueIndex,
            onPlay = playerViewModel::playQueueItem,
            onRemove = playerViewModel::removeFromQueue,
            onMove = playerViewModel::moveQueueItem,
            onClearUpcoming = playerViewModel::clearUpcoming,
            onClearAll = { playerViewModel.clearQueue(); showQueue = false },
            onDismiss = { showQueue = false }
        )
    }
}

@Composable
private fun EmptyPlayer() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("آهنگی در حال پخش نیست", style = MaterialTheme.typography.titleMedium)
        Text(
            "یک آهنگ را از کتابخانه یا خانه انتخاب کنید.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun SleepTimerStatus(state: SleepTimerState, onCancel: () -> Unit) {
    if (state is SleepTimerState.Off) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is SleepTimerState.Countdown) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val label = when (state) {
        is SleepTimerState.Countdown -> {
            val remaining = ((state.endsAtMs - now).coerceAtLeast(0L) + 999L) / 1000L
            "تایمر خواب: ${"%d:%02d".format(remaining / 60, remaining % 60)} تا توقف"
        }
        SleepTimerState.EndOfTrack -> "تایمر خواب: توقف در پایان این آهنگ"
        SleepTimerState.Off -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onCancel) { Text("لغو") }
    }
}

@Composable
private fun SleepTimerDialog(
    state: SleepTimerState,
    onMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    var custom by rememberSaveable { mutableStateOf("") }
    val customMinutes = custom.toIntOrNull()?.takeIf { it in 1..SleepTimerController.MAX_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تایمر خواب") },
        text = {
            Column {
                Text("پس از پایان زمان انتخاب‌شده، پخش متوقف می‌شود.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { onMinutes(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes دقیقه")
                    }
                }
                TextButton(onClick = onEndOfTrack, modifier = Modifier.fillMaxWidth()) { Text("پایان آهنگ فعلی") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter(Char::isDigit).take(3) },
                        label = { Text("زمان دلخواه (دقیقه)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { customMinutes?.let(onMinutes) }, enabled = customMinutes != null) { Text("تنظیم") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
        dismissButton = {
            if (state !is SleepTimerState.Off) TextButton(onClick = onCancelTimer) { Text("لغو تایمر") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<TrackEntity>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("صف پخش (${queue.size})", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onClearUpcoming, enabled = currentIndex < queue.lastIndex) { Text("پاک‌کردن بعدی‌ها") }
                    TextButton(onClick = onClearAll, enabled = queue.isNotEmpty()) { Text("پاک‌کردن همه") }
                }
            }
            if (queue.isEmpty()) {
                Text("صف پخش خالی است.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(currentIndex) { listState.scrollToItem(currentIndex.coerceIn(0, queue.lastIndex)) }
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        val isCurrent = index == currentIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            },
                            supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                if (isCurrent) Icon(Icons.Filled.PlayArrow, contentDescription = "در حال پخش")
                                else Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "انتقال به بالا")
                                    }
                                    IconButton(onClick = { onMove(index, index + 1) }, enabled = index < queue.lastIndex) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "انتقال به پایین")
                                    }
                                    IconButton(onClick = { onRemove(index) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "حذف از صف")
                                    }
                                }
                            },
                            modifier = Modifier.clickableRow { onPlay(index) }
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Creates a PlayerViewModel scoped to the Activity so it survives navigation between screens. */
@UnstableApi
@Composable
fun rememberPlayerViewModel(repository: MusicRepository): PlayerViewModel {
    val context = LocalContext.current
    return viewModel(
        factory = viewModelFactory {
            initializer { PlayerViewModel(context.applicationContext as Application, repository) }
        }
    )
}
