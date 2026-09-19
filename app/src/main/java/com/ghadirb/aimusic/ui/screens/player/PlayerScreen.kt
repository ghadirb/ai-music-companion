package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.data.repository.MusicRepository
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repository: MusicRepository,
    playerViewModel: PlayerViewModel
) {
    val uiState by playerViewModel.uiState.collectAsState()
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
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentTrack?.albumArtUri != null) {
                AsyncImage(
                    model = currentTrack.albumArtUri,
                    contentDescription = currentTrack.album,
                    modifier = Modifier.fillMaxSize()
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
