package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.lyrics.LyricsSource
import com.ghadirb.aimusic.lyrics.LyricsState
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.embedding.OnlineSimilarityRanker
import com.ghadirb.aimusic.playback.PlaybackStateStore
import com.ghadirb.aimusic.playback.PlayerController
import com.ghadirb.aimusic.playback.SleepTimerController
import com.ghadirb.aimusic.playback.SleepTimerState
import com.ghadirb.aimusic.recommendation.RecommendationEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueIndex: Int = 0,
    val isConnected: Boolean = false
)

/**
 * Bridges Compose UI <-> PlayerController (Media3). Listening history is recorded inside the
 * playback service (ListeningRecorder), not here, so it no longer depends on the UI being alive.
 */
@UnstableApi
class PlayerViewModel(
    application: Application,
    private val repository: MusicRepository
) : AndroidViewModel(application) {

    private val controller = PlayerController(application)
    private val stateStore = PlaybackStateStore(application)
    private val recommendationEngine = RecommendationEngine(repository)
    private val onlineSimilarityRanker = OnlineSimilarityRanker(application)
    private val trackCache = HashMap<Long, TrackEntity>()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _queue = MutableStateFlow<List<TrackEntity>>(emptyList())
    val queue: StateFlow<List<TrackEntity>> = _queue.asStateFlow()

    private val _similarTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val similarTracks: StateFlow<List<TrackEntity>> = _similarTracks.asStateFlow()

    private val lyricsSource = LyricsSource(application)
    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.NotFound)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()
    private val _lyricsHasFolder = MutableStateFlow(lyricsSource.hasFolder())
    val lyricsHasFolder: StateFlow<Boolean> = _lyricsHasFolder.asStateFlow()
    private var lyricsJob: Job? = null
    private val _allFilesAccess = MutableStateFlow(com.ghadirb.aimusic.lyrics.StorageAccess.hasAllFilesAccess(application))
    /** Whether plain .lrc files next to the songs can be read (Android 11+: "All files access"). */
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private val _onlineAiMessage = MutableStateFlow<String?>(null)
    val onlineAiMessage: StateFlow<String?> = _onlineAiMessage.asStateFlow()

    val sleepTimer: StateFlow<SleepTimerState> = SleepTimerController.state

    private var syncJob: Job? = null
    private var derivedJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onCurrentItemChanged(mediaItem?.mediaId?.toLongOrNull())
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncQueue()
        }
    }

    init {
        controller.connect { mediaController ->
            mediaController.addListener(playerListener)
            _uiState.update {
                it.copy(
                    isConnected = true,
                    isPlaying = mediaController.isPlaying,
                    shuffleEnabled = mediaController.shuffleModeEnabled,
                    repeatMode = mediaController.repeatMode
                )
            }
            if (mediaController.mediaItemCount > 0) {
                // The service is already playing (e.g. the Activity was recreated).
                syncQueue()
                onCurrentItemChanged(mediaController.currentMediaItem?.mediaId?.toLongOrNull())
            } else {
                restoreLastSession()
            }
        }
    }

    // ---- Playback ----

    fun playQueue(queue: List<TrackEntity>, startTrack: TrackEntity) {
        rememberTracks(queue)
        if (controller.currentMediaId() == startTrack.id.toString()) {
            if (!controller.isPlaying()) controller.togglePlayPause()
            return
        }
        _uiState.update { it.copy(currentTrack = startTrack, positionMs = 0L) }
        controller.playTrack(startTrack, queue)
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun skipNext() = controller.skipNext()
    fun skipPrevious() = controller.skipPrevious()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)
    fun cycleRepeatMode() = controller.cycleRepeatMode()

    // ---- Queue ----

    fun playNext(track: TrackEntity) {
        rememberTracks(listOf(track))
        if (controller.hasItems()) controller.addNext(track) else playQueue(listOf(track), track)
    }

    fun addToQueue(track: TrackEntity) {
        rememberTracks(listOf(track))
        if (controller.hasItems()) controller.addLast(track) else playQueue(listOf(track), track)
    }

    fun playQueueItem(index: Int) = controller.seekToQueueItem(index)
    fun removeFromQueue(index: Int) = controller.removeAt(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveItem(from, to)
    fun clearUpcoming() = controller.clearUpcoming()

    fun clearQueue() {
        controller.stopAndClear()
        _uiState.update { it.copy(currentTrack = null, isPlaying = false, positionMs = 0L, durationMs = 0L) }
        _queue.value = emptyList()
    }

    // ---- Sleep timer (runs inside the playback service process; survives this ViewModel) ----

    fun startSleepTimer(minutes: Int): Boolean = SleepTimerController.startCountdown(minutes)
    fun startSleepTimerEndOfTrack(): Boolean = SleepTimerController.startEndOfTrack()
    fun cancelSleepTimer() = SleepTimerController.cancel()

    // ---- Misc UI actions ----

    fun playSimilarTrack(track: TrackEntity) = playQueue(_similarTracks.value, track)

    fun improveSimilarWithAi() {
        val source = _uiState.value.currentTrack ?: return
        val candidates = _similarTracks.value.take(7)
        if (candidates.isEmpty()) return
        _onlineAiMessage.value = "در حال بهبود پیشنهادهای مشابه با AI…"
        viewModelScope.launch {
            when (val result = onlineSimilarityRanker.rank(source, candidates)) {
                is OnlineSimilarityRanker.Result.Success -> {
                    _similarTracks.value = result.tracks
                    _onlineAiMessage.value = result.remaining?.let { "پیشنهادها با AI بهبود یافت؛ $it درخواست امروز باقی مانده است." }
                        ?: "پیشنهادها با AI بهبود یافت."
                }
                OnlineSimilarityRanker.Result.ConsentRequired -> _onlineAiMessage.value = "ابتدا AI آنلاین را از تنظیمات و با رضایت خود فعال کنید."
                OnlineSimilarityRanker.Result.QuotaReached -> _onlineAiMessage.value = "سهمیهٔ روزانهٔ AI به پایان رسیده است."
                is OnlineSimilarityRanker.Result.Error -> _onlineAiMessage.value = result.message
            }
        }
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch {
            val newValue = !track.isFavorite
            repository.setFavorite(track.id, newValue)
            val updated = track.copy(isFavorite = newValue)
            trackCache[track.id] = updated
            _uiState.update { state ->
                if (state.currentTrack?.id == track.id) state.copy(currentTrack = updated) else state
            }
        }
    }

    /** Polled by the Player screen (once a second) to update the progress bar. */
    fun refreshProgress() {
        _uiState.update {
            it.copy(
                positionMs = controller.currentPosition(),
                durationMs = controller.duration(),
                shuffleEnabled = controller.shuffleEnabled(),
                repeatMode = controller.repeatMode(),
                isPlaying = controller.isPlaying(),
                queueIndex = controller.currentIndex()
            )
        }
    }

    // ---- Internals ----

    private fun rememberTracks(tracks: List<TrackEntity>) {
        tracks.forEach { trackCache[it.id] = it }
    }

    private suspend fun resolve(id: Long): TrackEntity? =
        trackCache[id] ?: repository.getTrack(id)?.also { trackCache[id] = it }

    private fun syncQueue() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            val resolved = controller.queueIds().mapNotNull { resolve(it) }
            _queue.value = resolved
            _uiState.update { it.copy(queueIndex = controller.currentIndex()) }
        }
    }

    private fun onCurrentItemChanged(id: Long?) {
        derivedJob?.cancel()
        derivedJob = viewModelScope.launch {
            val track = id?.let { resolve(it) }
            _uiState.update { it.copy(currentTrack = track, positionMs = 0L, queueIndex = controller.currentIndex()) }
            _similarTracks.value = track?.let { recommendationEngine.similarTracks(it, limit = 8) }.orEmpty()
            loadLyrics(track)
        }
    }

    private fun loadLyrics(track: TrackEntity?) {
        lyricsJob?.cancel()
        if (track == null) {
            _lyrics.value = LyricsState.NotFound
            return
        }
        _lyrics.value = LyricsState.Loading
        // File/SAF access happens off the main thread (the old code read the file on Main).
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            val parsed = lyricsSource.load(track)
            _lyrics.value = if (parsed != null) LyricsState.Found(parsed) else LyricsState.NotFound
        }
    }

    /** Called when the screen resumes (e.g. back from system settings): pick up a newly granted permission. */
    fun refreshStorageAccess() {
        val granted = com.ghadirb.aimusic.lyrics.StorageAccess.hasAllFilesAccess(getApplication())
        if (granted != _allFilesAccess.value) {
            _allFilesAccess.value = granted
            if (_lyrics.value !is LyricsState.Found) loadLyrics(_uiState.value.currentTrack)
        }
    }

    /** The user picked a .lrc file for the current track. */
    fun importLyrics(uri: Uri) {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val ok = lyricsSource.importFor(track, uri)
            if (ok) loadLyrics(track) else _onlineAiMessage.value = "این فایل متن معتبر LRC نبود."
        }
    }

    /** The user granted a music/lyrics folder so .lrc files can be found automatically. */
    fun addLyricsFolder(uri: Uri) {
        if (lyricsSource.addFolder(uri)) {
            _lyricsHasFolder.value = true
            loadLyrics(_uiState.value.currentTrack)
        }
    }

    private fun restoreLastSession() {
        viewModelScope.launch {
            val saved = stateStore.load() ?: return@launch
            val tracks = saved.trackIds.mapNotNull { resolve(it) }
            if (tracks.isEmpty() || controller.hasItems()) return@launch
            val savedCurrentId = saved.trackIds.getOrNull(saved.index)
            val index = tracks.indexOfFirst { it.id == savedCurrentId }.coerceAtLeast(0)
            controller.restoreQueue(tracks, index, saved.positionMs, saved.shuffle, saved.repeatMode)
            _uiState.update {
                it.copy(currentTrack = tracks[index], positionMs = saved.positionMs, queueIndex = index)
            }
        }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
