package com.ghadirb.aimusic.ui.video

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ghadirb.aimusic.video.VideoPlaybackController
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_MS = 3_500L

/**
 * Full video player UI: the Media3 video surface (with its standard subtitle renderer) plus our own
 * Persian controls. In Picture-in-Picture only the bare video is shown.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    controller: VideoPlaybackController,
    inPip: Boolean,
    isFullscreen: Boolean,
    canPip: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onPickSubtitleFile: () -> Unit
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Auto-hide while playing; any interaction restarts the timer.
    LaunchedEffect(controlsVisible, state.isPlaying, interactions, showSubtitles, showQueue) {
        if (controlsVisible && state.isPlaying && !showSubtitles && !showQueue) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    // Refresh the shown volume whenever the controls reappear (hardware keys may have changed it).
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    player = controller.player
                }
            },
            update = { it.player = controller.player },
            onRelease = { it.player = null }
        )

        if (state.audioUnsupported && state.error == null && !inPip) {
            Text(
                "صدای این فایل روی این گوشی پشتیبانی نمی‌شود؛ ویدئو بدون صدا پخش می‌شود.",
                color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        AnimatedVisibility(
            visible = controlsVisible && !inPip && state.error == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            VideoPlayerControls(
                state = state,
                isFullscreen = isFullscreen,
                canPip = canPip,
                volume = volume,
                maxVolume = maxVolume,
                onVolume = { v ->
                    volume = v
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                },
                onBack = onBack,
                onPlayPause = controller::playPause,
                onPrevious = controller::previous,
                onNext = controller::next,
                onSeek = controller::seekTo,
                onToggleFullscreen = onToggleFullscreen,
                onEnterPip = onEnterPip,
                onSubtitles = { showSubtitles = true },
                onQueue = { showQueue = true },
                onInteract = { interactions++ }
            )
        }

        state.error?.let { message ->
            if (!inPip) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.queue.isNotEmpty()) Button(onClick = controller::retry) { Text("تلاش دوباره") }
                        if (!state.softwareDecoding && state.queue.isNotEmpty()) {
                            OutlinedButton(onClick = { controller.setSoftwareDecoding(true) }) { Text("رمزگشای نرم‌افزاری") }
                        }
                        if (state.index < state.queue.lastIndex) OutlinedButton(onClick = controller::next) { Text("ویدئوی بعدی") }
                        OutlinedButton(onClick = onBack) { Text("بستن") }
                    }
                }
            }
        }
    }

    if (showSubtitles) {
        AlertDialog(
            onDismissRequest = { showSubtitles = false },
            title = { Text("زیرنویس") },
            text = {
                Column {
                    SubtitleRow("خاموش", selected = !state.subtitlesOn) { controller.disableSubtitles(); showSubtitles = false }
                    state.subtitles.forEach { option ->
                        SubtitleRow(option.label, selected = option.selected) { controller.selectSubtitle(option.index); showSubtitles = false }
                    }
                    if (state.subtitles.isEmpty()) {
                        Text(
                            "این ویدئو زیرنویس داخلی ندارد. می‌توانید فایل SRT یا VTT انتخاب کنید.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    SubtitleRow("افزودن زیرنویس از فایل…", selected = false) { showSubtitles = false; onPickSubtitleFile() }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitles = false }) { Text("بستن") } }
        )
    }

    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("انتخاب ویدئو") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(state.queue, key = { _, v -> v.id }) { i, v ->
                        SubtitleRow(v.displayName, selected = i == state.index) { controller.playIndex(i); showQueue = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("بستن") } }
        )
    }
}

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "✓  $label" else label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}
