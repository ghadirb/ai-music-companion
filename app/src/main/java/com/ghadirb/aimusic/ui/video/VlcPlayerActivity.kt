package com.ghadirb.aimusic.ui.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.ui.theme.AiMusicCompanionTheme
import com.ghadirb.aimusic.video.VideoResume
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Fallback video engine (libVLC, LGPL-2.1). Used for formats ExoPlayer cannot play on this device:
 * AVI / WMV / RMVB / VOB containers, DTS / AC3 audio, codecs the phone has no decoder for.
 * Single-video playback with the essentials: seek, audio/subtitle track choice, resume position.
 */
class VlcPlayerActivity : ComponentActivity() {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var fd: ParcelFileDescriptor? = null
    private var video: VideoEntity? = null
    private var lastSavedAt = 0L

    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var error by mutableStateOf<String?>(null)
    private var resumeApplied = false
    private var startAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        val repo = (application as AiMusicApp).videoRepository
        val id = intent.getLongExtra(EXTRA_VIDEO_ID, -1L)
        startAtMs = intent.getLongExtra(EXTRA_START_MS, -1L)

        setContent {
            AiMusicCompanionTheme(darkTheme = true) {
                VlcScreen()
            }
        }

        lifecycleScope.launch {
            val v = repo.get(id)
            if (v == null) { error = "ویدئو پیدا نشد."; return@launch }
            video = v
            if (startAtMs < 0L) startAtMs = VideoResume.startPosition(v.lastPositionMs, v.durationMs)
            repo.markPlayed(v.id)
            startPlayback(v)
        }
    }

    private fun startPlayback(v: VideoEntity) {
        try {
            val vlc = LibVLC(this, arrayListOf("--audio-time-stretch"))
            libVlc = vlc
            val mp = MediaPlayer(vlc)
            player = mp
            // A file descriptor works for any content:// uri from MediaStore / SAF.
            val pfd = contentResolver.openFileDescriptor(Uri.parse(v.contentUri), "r")
                ?: run { error = "فایل ویدئو قابل باز کردن نیست."; return }
            fd = pfd
            val media = Media(vlc, pfd.fileDescriptor)
            // Hardware decoding when the device allows it, automatic software fallback otherwise.
            media.setHWDecoderEnabled(true, false)
            mp.media = media
            media.release()
            mp.setEventListener { e ->
                when (e.type) {
                    MediaPlayer.Event.Playing -> {
                        playing = true; buffering = false
                        if (!resumeApplied && startAtMs > 0L) { resumeApplied = true; mp.time = startAtMs }
                        resumeApplied = true
                    }
                    MediaPlayer.Event.Paused -> playing = false
                    MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                    MediaPlayer.Event.LengthChanged -> durationMs = e.lengthChanged
                    MediaPlayer.Event.TimeChanged -> { positionMs = e.timeChanged; maybeSave() }
                    MediaPlayer.Event.EndReached -> { playing = false; saveNow(finished = true) }
                    MediaPlayer.Event.EncounteredError -> error = "این ویدئو با هیچ‌کدام از موتورهای پخش باز نشد."
                }
            }
            mp.play()
        } catch (t: Throwable) {
            error = "موتور پخش جایگزین اجرا نشد."
        }
    }

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSavedAt >= VideoResume.SAVE_INTERVAL_MS) { lastSavedAt = now; saveNow() }
    }

    private fun saveNow(finished: Boolean = false) {
        val v = video ?: return
        val pos = if (finished) 0L else positionMs
        val dur = durationMs.takeIf { it > 0 } ?: v.durationMs
        val repo = (application as AiMusicApp).videoRepository
        // Not tied to the activity scope: a save in onStop must finish even if the screen closes.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repo.saveProgress(v.id, VideoResume.positionToSave(pos, dur), dur)
        }
    }

    @Composable
    private fun VlcScreen() {
        var controls by remember { mutableStateOf(true) }
        var dialog by remember { mutableStateOf<String?>(null) } // "audio" | "sub"
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        // The player may not exist yet on the first frame; attach as soon as it does.
                        layout.post { player?.attachViews(layout, null, true, false) }
                    }
                },
                update = { layout -> player?.let { if (!it.vlcVout.areViewsAttached()) it.attachViews(layout, null, true, false) } }
            )
            if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

            error?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(msg, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { finish() }) { Text("بستن") }
                }
            }

            if (controls && error == null) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0x99000000)).padding(12.dp)
                ) {
                    Slider(
                        value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> if (durationMs > 0) { positionMs = (f * durationMs).toLong(); player?.time = positionMs } }
                    )
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${fmt(positionMs)} / ${fmt(durationMs)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { player?.let { it.time = (it.time - 10_000).coerceAtLeast(0) } }) { Text("۱۰- ثانیه") }
                            Button(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                Text(if (playing) "توقف" else "پخش")
                            }
                            TextButton(onClick = { player?.let { it.time = it.time + 10_000 } }) { Text("۱۰+ ثانیه") }
                        }
                        Row {
                            TextButton(onClick = { dialog = "audio" }) { Text("صدا") }
                            TextButton(onClick = { dialog = "sub" }) { Text("زیرنویس") }
                            TextButton(onClick = { finish() }) { Text("بستن") }
                        }
                    }
                }
            }
        }

        dialog?.let { which ->
            val mp = player
            val tracks = if (which == "audio") mp?.audioTracks else mp?.spuTracks
            val current = if (which == "audio") mp?.audioTrack else mp?.spuTrack
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (which == "audio") "انتخاب صدا" else "زیرنویس") },
                text = {
                    Column {
                        if (tracks.isNullOrEmpty()) Text("موردی موجود نیست.")
                        tracks?.forEach { t ->
                            Text(
                                (if (t.id == current) "✓  " else "") + t.name,
                                Modifier.fillMaxWidth().clickable {
                                    if (which == "audio") mp?.setAudioTrack(t.id) else mp?.setSpuTrack(t.id)
                                    dialog = null
                                }.padding(vertical = 10.dp),
                                color = if (t.id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("بستن") } }
            )
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        saveNow()
        player?.pause()
    }

    override fun onDestroy() {
        runCatching {
            player?.stop()
            player?.detachViews()
            player?.release()
            libVlc?.release()
            fd?.close()
        }
        player = null; libVlc = null; fd = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_START_MS = "start_ms"

        /** [startMs] < 0 means "use the saved resume position". */
        fun start(context: Context, videoId: Long, startMs: Long = -1L) {
            context.startActivity(
                Intent(context, VlcPlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO_ID, videoId)
                    .putExtra(EXTRA_START_MS, startMs)
            )
        }
    }
}
