package com.ghadirb.aimusic.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import java.util.Locale

private const val PREFS = "video_player_prefs"
private const val KEY_SOFTWARE = "software_decoding"

/**
 * Owns the ExoPlayer used by the video player screen. Deliberately separate from the music
 * PlaybackService/PlayerController: music keeps its own session untouched. The two players never
 * play at once because both request audio focus (starting a video pauses the music).
 *
 * Responsibilities: build the queue from local content uris, resume position, throttled progress
 * saving, subtitle track selection / external SRT+VTT, and mapping player errors to Persian text.
 * Must be used from the main thread ([uiScope] = the Activity's lifecycleScope).
 */
@OptIn(UnstableApi::class)
class VideoPlaybackController(
    context: Context,
    private val repository: VideoRepository,
    private val uiScope: CoroutineScope
) {
    data class SubtitleOption(val index: Int, val label: String, val selected: Boolean)

    data class State(
        val queue: List<VideoEntity> = emptyList(),
        val index: Int = 0,
        val isPlaying: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
        val subtitles: List<SubtitleOption> = emptyList(),
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val loaded: Boolean = false,
        /** Software decoders are being preferred (user choice or automatic fallback). */
        val softwareDecoding: Boolean = false,
        /** The file has an audio track this device cannot decode (e.g. AC3/DTS): video plays silently. */
        val audioUnsupported: Boolean = false
    ) {
        val current: VideoEntity? get() = queue.getOrNull(index)
        val subtitlesOn: Boolean get() = subtitles.any { it.selected }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read by the codec selector every time a decoder is created, so it can change without rebuilding the player. */
    @Volatile private var preferSoftware: Boolean = prefs.getBoolean(KEY_SOFTWARE, false)
    private val softwareRetried = HashSet<Long>()

    private val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        VideoDecoderPolicy.order(
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling), preferSoftware
        ) { it.name }
    }

    val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        // Decoder fallback helps odd devices; the selector lets us prefer Android's software decoders.
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)
            // Uses an FFmpeg/other Media3 extension automatically if one is ever added to the build.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** DB writes outlive the Activity (a save triggered in onStop/onDestroy must not be cancelled). */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Latest position to resume from per video id, kept in memory so back/forward in the queue is accurate. */
    private val positions = HashMap<Long, Long>()
    private var ticker: Job? = null
    private var sinceSaveMs = 0L
    private var released = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            _state.value = _state.value.copy(error = null)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                // Next/previous/auto-advance: continue that video from where it was left.
                val v = _state.value.queue.firstOrNull { it.id == id }
                val start = VideoResume.startPosition(positions[id] ?: v?.lastPositionMs ?: 0L, v?.durationMs ?: 0L)
                if (start > 0L) player.seekTo(start)
            }
            saveScope.launch { repository.markPlayed(id) }
            publish()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
        ) {
            val oldId = oldPosition.mediaItem?.mediaId
            if (oldId != null && oldId != newPosition.mediaItem?.mediaId) {
                oldId.toLongOrNull()?.let { saveFor(it, oldPosition.positionMs, durationOf(it)) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null && VideoDecoderPolicy.shouldRetryWithSoftware(error.errorCode, preferSoftware, id in softwareRetried)) {
                // Hardware decoder failed: try once more, at the same position, with software decoders.
                softwareRetried += id
                setSoftwareDecoding(true, persist = true)
                return
            }
            val missing = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            if (missing) {
                // Spec §17: a file that no longer exists must not stay in the list.
                player.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    saveScope.launch { repository.remove(listOf(id)) }
                }
            }
            _state.value = _state.value.copy(error = describe(error), isPlaying = false)
        }

        override fun onTracksChanged(tracks: Tracks) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveCurrent()
            publish()
        }
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = publish()
    }

    init {
        player.addListener(listener)
        ticker = uiScope.launch {
            while (isActive) {
                publish()
                val step = if (player.isPlaying) 500L else 1000L
                delay(step)
                if (player.isPlaying) {
                    sinceSaveMs += step
                    // One Room write every few seconds - never per frame.
                    if (sinceSaveMs >= VideoResume.SAVE_INTERVAL_MS) { sinceSaveMs = 0L; saveCurrent() }
                }
            }
        }
    }

    /** Builds the queue from [ids] (in that order) and starts [startId], resuming where it was left. */
    suspend fun load(ids: List<Long>, startId: Long) {
        val byId = repository.getMany(ids).associateBy { it.id }
        val queue = ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(loaded = true, error = "ویدئو پیدا نشد. ممکن است حذف یا جابه‌جا شده باشد.")
            return
        }
        val startIndex = queue.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        val first = queue[startIndex]
        queue.forEach { positions[it.id] = it.lastPositionMs }
        _state.value = _state.value.copy(queue = queue, index = startIndex, loaded = true)
        val items = queue.map { v ->
            MediaItem.Builder().setUri(Uri.parse(v.contentUri)).setMediaId(v.id.toString()).build()
        }
        player.setMediaItems(items, startIndex, VideoResume.startPosition(first.lastPositionMs, first.durationMs))
        player.prepare()
        player.playWhenReady = true
        saveScope.launch { repository.markPlayed(first.id) }
        publish()
    }

    // ---- transport ----
    fun playPause() { if (player.isPlaying) player.pause() else resume() }
    fun resume() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    fun seekTo(positionMs: Long) { player.seekTo(positionMs); publish() }
    fun next() { if (player.hasNextMediaItem()) { ensurePrepared(); player.seekToNextMediaItem() } }
    fun previous() { if (player.hasPreviousMediaItem()) { ensurePrepared(); player.seekToPreviousMediaItem() } }
    fun playIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) { ensurePrepared(); player.seekToDefaultPosition(index) }
    }
    fun retry() { player.prepare(); player.play() }

    /** Switches between hardware-first and software-first decoding and restarts the current video in place. */
    fun setSoftwareDecoding(enabled: Boolean, persist: Boolean = true) {
        preferSoftware = enabled
        if (persist) prefs.edit().putBoolean(KEY_SOFTWARE, enabled).apply()
        val position = player.currentPosition.coerceAtLeast(0L)
        _state.value = _state.value.copy(error = null, softwareDecoding = enabled)
        player.prepare()
        player.seekTo(position)
        player.play()
    }
    private fun ensurePrepared() { if (player.playbackState == Player.STATE_IDLE) player.prepare() }

    // ---- subtitles ----
    private fun textGroups(): List<Tracks.Group> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

    fun selectSubtitle(index: Int) {
        val group = textGroups().getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        publish()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        publish()
    }

    /**
     * Adds an external .srt / .vtt the user picked through the Storage Access Framework. Only a
     * uri the system gave us is used (no raw file paths). Returns false for other formats.
     * Subtitles are read as UTF-8 (Persian UTF-8 files render correctly; legacy 8-bit encodings do not).
     */
    fun addExternalSubtitle(uri: Uri, displayName: String?): Boolean {
        val name = displayName.orEmpty().lowercase(Locale.ROOT)
        val mime = when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            else -> return false
        }
        val item = player.currentMediaItem ?: return false
        val index = player.currentMediaItemIndex
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(displayName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val existing = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val updated = item.buildUpon().setSubtitleConfigurations(existing + sub).build()
        val position = player.currentPosition
        player.replaceMediaItem(index, updated)
        player.seekTo(index, position)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        return true
    }

    // ---- persistence ----
    /** Saves the current position now (pause / stop / background / leaving the player). */
    fun saveCurrent() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (_state.value.error != null) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: durationOf(id)
        saveFor(id, player.currentPosition, duration)
    }

    private fun saveFor(id: Long, positionMs: Long, durationMs: Long) {
        positions[id] = VideoResume.positionToSave(positionMs, durationMs)
        saveScope.launch { repository.saveProgress(id, positionMs, durationMs) }
    }

    private fun durationOf(id: Long): Long = _state.value.queue.firstOrNull { it.id == id }?.durationMs ?: 0L

    /** Saves progress and frees the codec. Called when the player screen is really finished. */
    fun release() {
        if (released) return
        released = true
        saveCurrent()
        ticker?.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun publish() {
        if (released) return
        val prev = _state.value
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val index = prev.queue.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else prev.index }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: prev.current?.durationMs ?: 0L
        val subtitles = textGroups().mapIndexed { i, g ->
            SubtitleOption(i, subtitleLabel(g, i), g.isSelected)
        }
        val size = player.videoSize
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val audioUnsupported = audioGroups.isNotEmpty() && audioGroups.none { it.isSupported }
        _state.value = prev.copy(
            softwareDecoding = preferSoftware,
            audioUnsupported = audioUnsupported,
            index = index,
            isPlaying = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            subtitles = subtitles,
            videoWidth = size.width,
            videoHeight = size.height
        )
    }

    private fun subtitleLabel(group: Tracks.Group, i: Int): String {
        val format = group.getTrackFormat(0)
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val lang = format.language
        if (!lang.isNullOrBlank() && lang != C.LANGUAGE_UNDETERMINED) {
            val name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale("fa"))
            if (name.isNotBlank()) return name
        }
        return "زیرنویس ${i + 1}"
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "فایل ویدئو پیدا نشد یا دسترسی به آن ممکن نیست. ممکن است حذف یا جابه‌جا شده باشد."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "این دستگاه از قالب یا کدک این ویدئو پشتیبانی نمی‌کند."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "فایل ویدئو خراب است یا قالب آن پشتیبانی نمی‌شود."
        else -> "پخش این ویدئو ممکن نشد."
    }
}
