package com.ghadirb.aimusic.video

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

/**
 * Second playback engine (libVLC, LGPL-2.1) behind the same [VideoController] contract as the
 * ExoPlayer engine, so the app shows ONE player screen for every file. libVLC (with FFmpeg inside)
 * plays what the phone's own decoders cannot: AVI / WMV / RMVB / VOB containers, DTS / AC3 audio,
 * odd codecs. Must be used from the main thread ([uiScope] = the Activity's lifecycleScope).
 */
class VlcPlaybackController(
    context: Context,
    private val repository: VideoRepository,
    private val uiScope: CoroutineScope
) : VideoController {

    private val appContext = context.applicationContext
    private val libVlc = LibVLC(appContext, arrayListOf("--audio-time-stretch"))
    private val mp = MediaPlayer(libVlc)

    private var fd: ParcelFileDescriptor? = null
    private var layout: VLCVideoLayout? = null
    private var wantPlay = false
    private var pendingStartMs = 0L
    @Volatile private var preferSoftware = false

    private val _state = MutableStateFlow(VideoPlaybackController.State())
    override val state: StateFlow<VideoPlaybackController.State> = _state.asStateFlow()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = HashMap<Long, Long>()
    private var ticker: Job? = null
    private var sinceSaveMs = 0L
    private var released = false
    private var ended = false

    init {
        mp.setEventListener { e ->
            uiScope.launch {
                when (e.type) {
                    MediaPlayer.Event.Playing -> {
                        ended = false
                        if (pendingStartMs > 0L) { mp.setTime(pendingStartMs); pendingStartMs = 0L }
                        _state.value = _state.value.copy(error = null)
                    }
                    MediaPlayer.Event.Buffering ->
                        _state.value = _state.value.copy(buffering = e.buffering < 100f)
                    MediaPlayer.Event.EndReached -> {
                        ended = true
                        saveCurrent(finished = true)
                        if (_state.value.index < _state.value.queue.lastIndex) next()
                    }
                    MediaPlayer.Event.EncounteredError ->
                        _state.value = _state.value.copy(
                            error = "این ویدئو با هیچ‌کدام از موتورهای پخش باز نشد.", isPlaying = false
                        )
                }
                publish()
            }
        }
        ticker = uiScope.launch {
            while (isActive) {
                publish()
                val step = if (mp.isPlaying) 500L else 1000L
                delay(step)
                if (mp.isPlaying) {
                    sinceSaveMs += step
                    if (sinceSaveMs >= VideoResume.SAVE_INTERVAL_MS) { sinceSaveMs = 0L; saveCurrent() }
                }
            }
        }
    }

    // ---- surface ----
    /** Called by the screen when the video view exists; playback starts as soon as a view is attached. */
    fun attachViews(view: VLCVideoLayout) {
        layout = view
        mp.attachViews(view, null, true, false)
        if (wantPlay) { wantPlay = false; mp.play() }
    }

    fun detachViews() {
        runCatching { mp.detachViews() }
        layout = null
    }

    // ---- queue ----
    override suspend fun load(ids: List<Long>, startId: Long, startMs: Long) {
        val byId = repository.getMany(ids).associateBy { it.id }
        val queue = ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(loaded = true, error = "ویدئو پیدا نشد. ممکن است حذف یا جابه‌جا شده باشد.")
            return
        }
        val startIndex = queue.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        queue.forEach { positions[it.id] = it.lastPositionMs }
        _state.value = _state.value.copy(queue = queue, index = startIndex, loaded = true)
        val first = queue[startIndex]
        open(first, if (startMs >= 0L) startMs else VideoResume.startPosition(first.lastPositionMs, first.durationMs))
    }

    private fun open(video: VideoEntity, startMs: Long) {
        try {
            mp.stop()
            runCatching { fd?.close() }
            val pfd = appContext.contentResolver.openFileDescriptor(Uri.parse(video.contentUri), "r")
            if (pfd == null) {
                _state.value = _state.value.copy(error = "فایل ویدئو قابل باز کردن نیست.")
                return
            }
            fd = pfd
            val media = Media(libVlc, pfd.fileDescriptor)
            // Hardware decoding when allowed, with automatic software fallback inside libVLC.
            media.setHWDecoderEnabled(!preferSoftware, false)
            mp.media = media
            media.release()
            pendingStartMs = startMs
            ended = false
            _state.value = _state.value.copy(error = null, buffering = true, positionMs = startMs, durationMs = video.durationMs)
            saveScope.launch { repository.markPlayed(video.id) }
            if (layout != null) mp.play() else wantPlay = true
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = "پخش این ویدئو ممکن نشد.")
        }
    }

    private fun openAt(index: Int) {
        val queue = _state.value.queue
        val v = queue.getOrNull(index) ?: return
        saveCurrent()
        _state.value = _state.value.copy(index = index, subtitles = emptyList(), audioTracks = emptyList())
        open(v, VideoResume.startPosition(positions[v.id] ?: v.lastPositionMs, v.durationMs))
    }

    // ---- transport ----
    override fun playPause() { if (mp.isPlaying) mp.pause() else resume() }
    override fun resume() {
        if (ended) { mp.setTime(0L); ended = false }
        mp.play()
    }
    override fun pause() { if (mp.isPlaying) mp.pause() }
    override fun seekTo(positionMs: Long) { mp.setTime(positionMs); publish() }
    override fun next() { if (_state.value.index < _state.value.queue.lastIndex) openAt(_state.value.index + 1) }
    override fun previous() { if (_state.value.index > 0) openAt(_state.value.index - 1) }
    override fun playIndex(index: Int) { if (index in _state.value.queue.indices) openAt(index) }
    override fun retry() {
        val s = _state.value
        s.current?.let { open(it, s.positionMs) }
    }

    /** "Software decoding" for libVLC = turn its hardware decoder off and restart in place. */
    override fun setSoftwareDecoding(enabled: Boolean, persist: Boolean) {
        preferSoftware = enabled
        val s = _state.value
        _state.value = s.copy(softwareDecoding = enabled, error = null)
        s.current?.let { open(it, mp.getTime().coerceAtLeast(0L)) }
    }

    override fun setSpeed(speed: Float) { mp.setRate(speed); publish() }

    override fun setAspect(aspect: VideoAspect) {
        // Best effort: scaling only applies once a video output exists, so a failure here must never affect playback.
        runCatching {
            mp.videoScale = when (aspect) {
                VideoAspect.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                VideoAspect.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
                VideoAspect.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            }
        }
    }

    // ---- tracks ----
    override fun selectSubtitle(index: Int) {
        subtitleTracks().getOrNull(index)?.let { mp.setSpuTrack(it.id) }
        publish()
    }

    override fun disableSubtitles() { mp.setSpuTrack(-1); publish() }

    override fun selectAudio(index: Int) {
        audioTrackList().getOrNull(index)?.let { mp.setAudioTrack(it.id) }
        publish()
    }

    /** libVLC reads plain files best, so the picked subtitle is copied into the app cache first. */
    override fun addExternalSubtitle(uri: Uri, displayName: String?): Boolean {
        val ext = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in SUBTITLE_EXTENSIONS) return false
        return runCatching {
            val out = File(appContext.cacheDir, "sub_${System.currentTimeMillis()}.$ext")
            appContext.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: return false
            mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(out), true)
        }.getOrDefault(false)
    }

    private fun subtitleTracks() = mp.getSpuTracks()?.filter { it.id >= 0 }.orEmpty()
    private fun audioTrackList() = mp.getAudioTracks()?.filter { it.id >= 0 }.orEmpty()

    // ---- persistence ----
    override fun saveCurrent() = saveCurrent(finished = false)

    private fun saveCurrent(finished: Boolean) {
        val s = _state.value
        val v = s.current ?: return
        if (s.error != null) return
        val position = if (finished) 0L else mp.getTime().coerceAtLeast(0L)
        val duration = mp.getLength().takeIf { it > 0L } ?: v.durationMs
        positions[v.id] = VideoResume.positionToSave(position, duration)
        saveScope.launch { repository.saveProgress(v.id, position, duration) }
    }

    override fun release() {
        if (released) return
        saveCurrent()
        released = true
        ticker?.cancel()
        runCatching { mp.setEventListener(null) }
        runCatching { mp.stop() }
        runCatching { mp.detachViews() }
        runCatching { mp.release() }
        runCatching { libVlc.release() }
        runCatching { fd?.close() }
        fd = null
    }

    private fun publish() {
        if (released) return
        val prev = _state.value
        val subs = subtitleTracks()
        val currentSub = mp.getSpuTrack()
        val audios = audioTrackList()
        val currentAudio = mp.getAudioTrack()
        val length = mp.getLength().takeIf { it > 0L } ?: prev.durationMs
        val track = runCatching { mp.currentVideoTrack }.getOrNull()
        _state.value = prev.copy(
            isPlaying = mp.isPlaying,
            positionMs = mp.getTime().coerceAtLeast(0L),
            durationMs = length,
            subtitles = subs.mapIndexed { i, t -> VideoPlaybackController.SubtitleOption(i, t.name ?: "زیرنویس ${i + 1}", t.id == currentSub) },
            audioTracks = audios.mapIndexed { i, t -> VideoPlaybackController.SubtitleOption(i, t.name ?: "صدا ${i + 1}", t.id == currentAudio) },
            speed = mp.getRate(),
            videoWidth = track?.width ?: prev.videoWidth,
            videoHeight = track?.height ?: prev.videoHeight,
            audioUnsupported = false,
            needsFallbackEngine = false
        )
    }

    private companion object {
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
    }
}
