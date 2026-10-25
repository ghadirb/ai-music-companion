package com.ghadirb.aimusic.ui.video

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ghadirb.aimusic.video.PlaybackSpeeds
import com.ghadirb.aimusic.video.VideoAspect
import com.ghadirb.aimusic.video.VideoController
import com.ghadirb.aimusic.video.VideoPlaybackController
import com.ghadirb.aimusic.video.VlcPlaybackController
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_MS = 3_500L

/**
 * Full video player UI: the Media3 video surface (with its standard subtitle renderer) plus our own
 * Persian controls. In Picture-in-Picture only the bare video is shown.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    controller: VideoController,
    inPip: Boolean,
    isFullscreen: Boolean,
    canPip: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onOpenFallbackEngine: () -> Unit = {},
    /** Why the file cannot play (real codecs vs this phone's decoders), shown under the error. */
    diagnosis: String? = null
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // Sleep timer + repeat-all live with the screen (video stops when the screen closes anyway).
    var sleep by remember { mutableStateOf<com.ghadirb.aimusic.video.SleepMode>(com.ghadirb.aimusic.video.SleepMode.Off) }
    val ctxForPrefs = androidx.compose.ui.platform.LocalContext.current
    var loopAll by remember { mutableStateOf(com.ghadirb.aimusic.video.SubtitleStyleStore.loadLoopAll(ctxForPrefs)) }
    var subStyle by remember { mutableStateOf(com.ghadirb.aimusic.video.SubtitleStyleStore.load(ctxForPrefs)) }
    var showSubStyle by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(loopAll, controller) { controller.setLoopAll(loopAll) }
    LaunchedEffect(sleep) {
        val m = sleep
        if (m is com.ghadirb.aimusic.video.SleepMode.Minutes) {
            delay((m.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            controller.pause()
            sleep = com.ghadirb.aimusic.video.SleepMode.Off
        }
    }
    LaunchedEffect(sleep, state.index, state.positionMs) {
        if (com.ghadirb.aimusic.video.VideoSleepTimer.shouldPause(sleep, System.currentTimeMillis(), state.index, state.positionMs, state.durationMs)
            && sleep is com.ghadirb.aimusic.video.SleepMode.EndOfVideo) {
            controller.pause()
            sleep = com.ghadirb.aimusic.video.SleepMode.Off
        }
    }
    LaunchedEffect(showTimer) { while (showTimer) { nowMs = System.currentTimeMillis(); delay(1000) } }
    // Screen lock: controls and drag gestures are off (pocket / child proof); a tap only reveals the unlock button.
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(3000); showUnlock = false } }
    // Picture fitting (fit / fill / zoom). Kept for the whole viewing session, across queue items.
    var aspect by remember { mutableStateOf(VideoAspect.FIT) }
    // libVLC only: re-apply when the video changes, because scaling needs an existing video output.
    LaunchedEffect(aspect, state.index) { controller.setAspect(aspect) }

    // Auto-hide while playing; any interaction restarts the timer.
    LaunchedEffect(controlsVisible, state.isPlaying, interactions, showSubtitles, showQueue, showAudio) {
        if (controlsVisible && state.isPlaying && !showSubtitles && !showQueue && !showAudio) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    // Refresh the shown volume whenever the controls reappear (hardware keys may have changed it).
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    PlayerGestureLayer(
        onTap = { if (locked) showUnlock = true else controlsVisible = !controlsVisible },
        gesturesEnabled = !locked,
        positionMs = { state.positionMs },
        durationMs = { state.durationMs },
        onSeekTo = { ms -> controller.seekTo(ms); interactions++ },
        modifier = Modifier.background(Color.Black)
    ) {
        when (controller) {
            is VideoPlaybackController -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        player = controller.player
                    }
                },
                update = {
                    it.player = controller.player
                    it.subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setFractionalTextSize(subStyle.fractionalTextSize())
                        setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                subStyle.textColor(), subStyle.backgroundColor(), android.graphics.Color.TRANSPARENT,
                                if (subStyle.outline) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                                android.graphics.Color.BLACK, null
                            )
                        )
                    }
                    it.resizeMode = when (aspect) {
                        VideoAspect.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        VideoAspect.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        VideoAspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                onRelease = { it.player = null }
            )
            is VlcPlaybackController -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> VLCVideoLayout(ctx).also { controller.attachViews(it) } },
                onRelease = { controller.detachViews() }
            )
            else -> Unit
        }

        if (state.audioUnsupported && state.error == null && !inPip) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "صدای این فایل روی این گوشی پشتیبانی نمی‌شود.",
                    color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
                TextButton(onClick = onOpenFallbackEngine) { Text("پخش با موتور جایگزین") }
            }
        }

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !inPip && state.error == null,
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
                onAudio = { showAudio = true },
                onSpeed = { controller.setSpeed(PlaybackSpeeds.next(state.speed)) },
                onQueue = { showQueue = true },
                aspectLabel = aspect.label,
                onAspect = { aspect = aspect.next() },
                onInteract = { interactions++ },
                onLock = { locked = true; controlsVisible = false; showUnlock = true },
                onTimer = { showTimer = true },
                timerActive = sleep !is com.ghadirb.aimusic.video.SleepMode.Off || loopAll
            )
        }

        if (locked && showUnlock && !inPip) {
            androidx.compose.material3.IconButton(
                onClick = { locked = false; showUnlock = false; controlsVisible = true; interactions++ },
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp).background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape)
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.LockOpen, contentDescription = "باز کردن قفل", tint = Color.White)
            }
        }

        state.error?.let { message ->
            if (!inPip) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    if (diagnosis != null) {
                        Text(diagnosis, color = Color(0xFFCCCCCC), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
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
                    SubtitleRow("ظاهر زیرنویس (اندازه، رنگ، پس‌زمینه)…", selected = false) { showSubtitles = false; showSubStyle = true }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitles = false }) { Text("بستن") } }
        )
    }

    if (showSubStyle) {
        fun update(new: com.ghadirb.aimusic.video.SubtitleStyle) {
            subStyle = new.sanitized()
            com.ghadirb.aimusic.video.SubtitleStyleStore.save(ctxForPrefs, subStyle)
        }
        AlertDialog(
            onDismissRequest = { showSubStyle = false },
            title = { Text("ظاهر زیرنویس") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "نمونهٔ زیرنویس فارسی",
                            color = Color(subStyle.textColor()),
                            fontSize = (13 + 4 * subStyle.size).sp,
                            modifier = Modifier.background(Color(subStyle.backgroundColor())).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    @Composable
                    fun group(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
                        Text(title, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            labels.forEachIndexed { i, l ->
                                androidx.compose.material3.FilterChip(selected = i == selected, onClick = { onPick(i) }, label = { Text(l) })
                            }
                        }
                    }
                    group("اندازه", com.ghadirb.aimusic.video.SubtitleStyle.SIZE_LABELS, subStyle.size) { update(subStyle.copy(size = it)) }
                    group("رنگ", com.ghadirb.aimusic.video.SubtitleStyle.COLOR_LABELS, subStyle.color) { update(subStyle.copy(color = it)) }
                    group("پس‌زمینه", com.ghadirb.aimusic.video.SubtitleStyle.BG_LABELS, subStyle.background) { update(subStyle.copy(background = it)) }
                    group("حاشیهٔ متن", listOf("بدون حاشیه", "با حاشیه"), if (subStyle.outline) 1 else 0) { update(subStyle.copy(outline = it == 1)) }
                    if (controller is com.ghadirb.aimusic.video.VlcPlaybackController) {
                        Text("در این موتور پخش، تغییر بعد از بستن و باز کردن دوبارهٔ پلیر اعمال می‌شود.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSubStyle = false }) { Text("تمام") } },
            dismissButton = { TextButton(onClick = { update(com.ghadirb.aimusic.video.SubtitleStyle()) }) { Text("پیش‌فرض") } }
        )
    }

    if (showAudio) {
        AlertDialog(
            onDismissRequest = { showAudio = false },
            title = { Text("انتخاب صدا") },
            text = {
                Column {
                    if (state.audioTracks.isEmpty()) {
                        Text("صدای دیگری برای انتخاب وجود ندارد.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    state.audioTracks.forEach { option ->
                        SubtitleRow(option.label, selected = option.selected) { controller.selectAudio(option.index); showAudio = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAudio = false }) { Text("بستن") } }
        )
    }

    if (showTimer) {
        val vst = com.ghadirb.aimusic.video.VideoSleepTimer
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("تایمر خواب و تکرار") },
            text = {
                Column {
                    val status = when (val m = sleep) {
                        is com.ghadirb.aimusic.video.SleepMode.Off -> "تایمر خاموش است"
                        is com.ghadirb.aimusic.video.SleepMode.Minutes -> "توقف پخش تا ${vst.formatRemaining(vst.remainingMs(m, nowMs))} دیگر"
                        is com.ghadirb.aimusic.video.SleepMode.EndOfVideo -> "توقف در پایان این ویدئو"
                    }
                    Text(status, style = MaterialTheme.typography.labelLarge)
                    vst.PRESETS_MINUTES.forEach { min ->
                        SubtitleRow("$min دقیقه", selected = false) {
                            sleep = vst.start(min, System.currentTimeMillis()); showTimer = false
                        }
                    }
                    SubtitleRow("پایان این ویدئو", selected = sleep is com.ghadirb.aimusic.video.SleepMode.EndOfVideo) {
                        sleep = com.ghadirb.aimusic.video.SleepMode.EndOfVideo(state.index); showTimer = false
                    }
                    SubtitleRow("خاموش کردن تایمر", selected = sleep is com.ghadirb.aimusic.video.SleepMode.Off) { sleep = com.ghadirb.aimusic.video.SleepMode.Off }
                    androidx.compose.material3.HorizontalDivider()
                    SubtitleRow(if (loopAll) "تکرار همهٔ صف: روشن" else "تکرار همهٔ صف: خاموش", selected = loopAll) {
                        loopAll = !loopAll
                        com.ghadirb.aimusic.video.SubtitleStyleStore.saveLoopAll(ctxForPrefs, loopAll)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("بستن") } }
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
