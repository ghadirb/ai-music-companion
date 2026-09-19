package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.analysis.LyricsAnalyzer
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repository: MusicRepository,
    playerViewModel: PlayerViewModel
) {
    val uiState by playerViewModel.uiState.collectAsState()
    val similarTracks by playerViewModel.similarTracks.collectAsState()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val onlineAiMessage by playerViewModel.onlineAiMessage.collectAsState()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    // Keep a stable local reference. `uiState.currentTrack` is read from a
    // StateFlow-backed object and Kotlin cannot smart-cast that expression.
    val currentTrack = uiState.currentTrack

    // Poll playback position once a second while this screen is visible.
    LaunchedEffect(Unit) {
        while (true) {
            playerViewModel.refreshProgress()
            delay(1000)
        }
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
            if (currentTrack?.albumArtUri != null) {
                AsyncImage(
                    model = currentTrack.albumArtUri,
                    contentDescription = currentTrack.album,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            uiState.currentTrack?.title ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            uiState.currentTrack?.artist ?: "",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(20.dp))
        val duration = uiState.durationMs.coerceAtLeast(1L)
        Slider(
            value = uiState.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { playerViewModel.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(uiState.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(uiState.durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playerViewModel.skipPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(16.dp))
            FilledIconButton(onClick = { playerViewModel.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = { playerViewModel.skipNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        uiState.currentTrack?.let { track ->
            OutlinedButton(onClick = { playerViewModel.toggleFavorite(track) }) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (track.isFavorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها")
            }
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
                    contentDescription = "پخش تصادفی",
                    tint = if (uiState.shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = { playerViewModel.cycleRepeatMode() }) {
                Icon(
                    if (uiState.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "تکرار پخش",
                    tint = if (uiState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onClick = { showLyrics = true }) {
                Icon(Icons.Filled.Lyrics, contentDescription = "متن همگام آهنگ")
            }
            IconButton(onClick = { showSleepTimer = true }) {
                Icon(Icons.Filled.Timer, contentDescription = "تایمر خواب")
            }
        }
        uiState.sleepTimerEndsAtMs?.let { endsAt ->
            val remainingMinutes = ((endsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
            Text("تایمر خواب: حدود $remainingMinutes دقیقه تا توقف", style = MaterialTheme.typography.labelMedium)
        }

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
            onlineAiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }

    }

    if (showLyrics) {
        SyncedLyricsDialog(
            lyrics = lyrics,
            positionMs = uiState.positionMs,
            onDismiss = { showLyrics = false },
            onSeek = playerViewModel::seekTo
        )
    }
    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            title = { Text("تایمر خواب") },
            text = { Text("پس از پایان زمان انتخاب‌شده، پخش متوقف می‌شود.") },
            confirmButton = {
                Row {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = { playerViewModel.setSleepTimer(minutes); showSleepTimer = false }) {
                            Text("$minutes")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { playerViewModel.setSleepTimer(null); showSleepTimer = false }) {
                    Text("لغو تایمر")
                }
            }
        )
    }
}

@Composable
private fun SyncedLyricsDialog(
    lyrics: List<LyricsAnalyzer.LrcLine>,
    positionMs: Long,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
        ) {
            if (lyrics.isEmpty()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("متن همگام در دسترس نیست", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "برای همگام‌سازی، فایل .lrc هم‌نام آهنگ را در همان پوشه قرار دهید. متن محلی اولویت دارد.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text("بستن") }
                }
            } else {
                val activeIndex = lyrics.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
                val listState = rememberLazyListState()
                LaunchedEffect(activeIndex) {
                    listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                }
                Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("متن همگام", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss) { Text("بستن") }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lyrics, key = { _, line -> "${line.timeMs}-${line.text}" }) { index, line ->
                            val active = index == activeIndex && line.timeMs <= positionMs
                            TextButton(
                                onClick = { onSeek(line.timeMs) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    line.text,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
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
