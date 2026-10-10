package com.ghadirb.aimusic.ui.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.ui.theme.AiMusicCompanionTheme
import com.ghadirb.aimusic.video.VideoPlaybackController
import com.ghadirb.aimusic.video.VlcFallbackPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Standalone video player. A separate Activity (not a destination inside MainActivity) so that:
 *  - Picture-in-Picture shrinks only the video, not the whole music app;
 *  - rotation is handled in-place (configChanges in the manifest) and never restarts playback;
 *  - the music player/service/notification code is not touched at all.
 */
class VideoPlayerActivity : ComponentActivity() {

    private lateinit var controller: VideoPlaybackController
    private var inPip by mutableStateOf(false)
    private var fullscreenRequested by mutableStateOf(false)
    private var landscape by mutableStateOf(false)

    private val supportsPip: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        if (!controller.addExternalSubtitle(uri, name)) {
            Toast.makeText(this, "فقط فایل‌های زیرنویس SRT و VTT پشتیبانی می‌شوند.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val app = application as AiMusicApp
        controller = VideoPlaybackController(this, app.videoRepository, lifecycleScope)

        val startId = intent.getLongExtra(EXTRA_VIDEO_ID, -1L)
        val ids = intent.getLongArrayExtra(EXTRA_QUEUE)?.toList().orEmpty().ifEmpty { listOf(startId) }
        lifecycleScope.launch { controller.load(ids, startId) }

        // Hand the file to the libVLC engine when ExoPlayer cannot play it (AVI/WMV..., or decoder failures).
        lifecycleScope.launch {
            controller.state
                .map { st -> Triple(st.current, st.needsFallbackEngine, st.positionMs) }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second == b.second }
                .collect { (video, needsFallback, pos) ->
                    if (video != null && (needsFallback || VlcFallbackPolicy.prefersVlc(video.displayName))) {
                        openFallbackEngine(video.id, if (needsFallback) pos else -1L)
                    }
                }
        }

        // Keep the PiP window's aspect ratio / auto-enter flag in sync with what is playing.
        if (supportsPip) {
            lifecycleScope.launch {
                controller.state
                    .map { Triple(it.isPlaying, it.videoWidth, it.videoHeight) }
                    .distinctUntilChanged()
                    .collect { runCatching { setPictureInPictureParams(pipParams()) } }
            }
        }

        setContent {
            AiMusicCompanionTheme(darkTheme = true) {
                VideoPlayerScreen(
                    controller = controller,
                    inPip = inPip,
                    isFullscreen = fullscreenRequested || landscape,
                    canPip = supportsPip,
                    onBack = { finish() },
                    onToggleFullscreen = ::toggleFullscreen,
                    onEnterPip = ::enterPip,
                    onPickSubtitleFile = { pickSubtitle.launch(arrayOf("*/*")) },
                    onOpenFallbackEngine = {
                        controller.state.value.current?.let { openFallbackEngine(it.id, controller.state.value.positionMs) }
                    }
                )
            }
        }
        applySystemBars()
    }

    private var fallbackLaunched = false
    private fun openFallbackEngine(videoId: Long, startMs: Long) {
        if (fallbackLaunched) return
        fallbackLaunched = true
        VlcPlayerActivity.start(this, videoId, startMs, controller.state.value.queue.map { it.id })
        finish()
    }

    // ---- orientation / fullscreen ----
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applySystemBars()
    }

    private fun toggleFullscreen() {
        val currentlyFull = fullscreenRequested || landscape
        if (currentlyFull) {
            fullscreenRequested = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Hand rotation back to the sensor shortly after, so auto-rotate keeps working.
            lifecycleScope.launch {
                delay(1500)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            fullscreenRequested = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        applySystemBars()
    }

    /** Hides the system bars in landscape / fullscreen / PiP so the app adds no extra UI around the video. */
    private fun applySystemBars() {
        val controls = WindowInsetsControllerCompat(window, window.decorView)
        controls.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape || fullscreenRequested || inPip) controls.hide(WindowInsetsCompat.Type.systemBars())
        else controls.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    // ---- Picture-in-Picture ----
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val s = controller.state.value
        if (s.videoWidth > 0 && s.videoHeight > 0) {
            // The system only accepts ratios between roughly 1:2.39 and 2.39:1.
            val ratio = (s.videoWidth.toFloat() / s.videoHeight).coerceIn(0.42f, 2.38f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Home / gesture navigation enters PiP automatically, but only while playing.
            builder.setAutoEnterEnabled(s.isPlaying)
        }
        return builder.build()
    }

    private fun enterPip() {
        if (!supportsPip) return
        try {
            enterPictureInPictureMode(pipParams())
        } catch (e: IllegalStateException) {
            // Not allowed right now (e.g. disabled by the user in system settings): stay in the normal player.
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8-11 have no auto-enter: Home button while playing -> small window.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ::controller.isInitialized && controller.player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        applySystemBars()
    }

    // ---- lifecycle: save position on pause / stop, release on exit ----
    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.saveCurrent()
    }

    override fun onStop() {
        super.onStop()
        if (!::controller.isInitialized) return
        // While the PiP window is visible the activity is NOT stopped, so reaching onStop means the
        // user left the player (or closed the PiP window): pause, keep the position.
        controller.saveCurrent()
        controller.player.pause()
        if (inPip) finish()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_QUEUE = "video_queue"
        /** Next/previous only needs the neighbours; this also keeps the Intent small for huge libraries. */
        private const val QUEUE_RADIUS = 150

        fun start(context: Context, videoId: Long, queueIds: List<Long>) {
            val index = queueIds.indexOf(videoId).coerceAtLeast(0)
            val from = (index - QUEUE_RADIUS).coerceAtLeast(0)
            val to = (index + QUEUE_RADIUS + 1).coerceAtMost(queueIds.size)
            val window = if (queueIds.isEmpty()) listOf(videoId) else queueIds.subList(from, to)
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO_ID, videoId)
                    .putExtra(EXTRA_QUEUE, window.toLongArray())
            )
        }
    }
}
