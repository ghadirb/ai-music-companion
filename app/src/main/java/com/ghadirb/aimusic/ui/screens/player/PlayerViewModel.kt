package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.analysis.LyricsAnalyzer
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.playback.PlayerController
import com.ghadirb.aimusic.recommendation.RecommendationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class PlayerUiState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepTimerEndsAtMs: Long? = null
)

/**
 * Bridges Compose UI <-> PlayerController (Media3) <-> MusicRepository.
 * Also owns the "did this play count as a completed listen or a skip"
 * bookkeeping that feeds ListeningHistoryEntity — this is the behavioral
 * data the whole taste-learning system in the doc depends on.
 */
@UnstableApi
class PlayerViewModel(
    application: Application,
    private val repository: MusicRepository
) : AndroidViewModel(application) {

    private val controller = PlayerController(application)
    private val recommendationEngine = RecommendationEngine(repository)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _similarTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val similarTracks: StateFlow<List<TrackEntity>> = _similarTracks.asStateFlow()
    private val _lyrics = MutableStateFlow<List<LyricsAnalyzer.LrcLine>>(emptyList())
    val lyrics: StateFlow<List<LyricsAnalyzer.LrcLine>> = _lyrics.asStateFlow()

    private var currentQueue: List<TrackEntity> = emptyList()
    private var sessionStartTime: Long = 0L
    private var sessionTrack: TrackEntity? = null
    private var maxPositionReachedMs: Long = 0L
    private var sleepTimerJob: Job? = null

    init {
        controller.connect { mediaController ->
            mediaController.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _uiState.value = _uiState.value.copy(shuffleEnabled = shuffleModeEnabled)
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _uiState.value = _uiState.value.copy(repeatMode = repeatMode)
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    finalizePreviousSession(reason)
                    val trackId = mediaItem?.mediaId?.toLongOrNull()
                    val track = currentQueue.firstOrNull { it.id == trackId }
                    startNewSession(track)
                }
            })
        }
    }

    fun playQueue(queue: List<TrackEntity>, startTrack: TrackEntity) {
        if (controller.currentMediaId() == startTrack.id.toString()) {
            currentQueue = queue
            _uiState.value = _uiState.value.copy(currentTrack = startTrack)
            if (!controller.isPlaying()) controller.togglePlayPause()
            return
        }
        currentQueue = queue
        controller.playTrack(startTrack, queue)
        startNewSession(startTrack)
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun skipNext() = controller.skipNext()
    fun skipPrevious() = controller.skipPrevious()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)
    fun cycleRepeatMode() = controller.cycleRepeatMode()

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null) {
            _uiState.value = _uiState.value.copy(sleepTimerEndsAtMs = null)
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _uiState.value = _uiState.value.copy(sleepTimerEndsAtMs = endsAt)
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller.pause()
            _uiState.value = _uiState.value.copy(sleepTimerEndsAtMs = null)
        }
    }

    fun playSimilarTrack(track: TrackEntity) = playQueue(_similarTracks.value, track)

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch {
            val newValue = !track.isFavorite
            repository.setFavorite(track.id, newValue)
            _uiState.value = _uiState.value.copy(currentTrack = track.copy(isFavorite = newValue))
        }
    }

    /** Polled by the Player screen (e.g. every second) to update the progress bar. */
    fun refreshProgress() {
        val position = controller.currentPosition()
        maxPositionReachedMs = maxOf(maxPositionReachedMs, position)
        _uiState.value = _uiState.value.copy(
            positionMs = position,
            durationMs = controller.duration(),
            shuffleEnabled = controller.shuffleEnabled(),
            repeatMode = controller.repeatMode()
        )
    }

    private fun startNewSession(track: TrackEntity?) {
        sessionTrack = track
        sessionStartTime = System.currentTimeMillis()
        maxPositionReachedMs = 0L
        _uiState.value = _uiState.value.copy(currentTrack = track, positionMs = 0L)
        viewModelScope.launch {
            _similarTracks.value = track?.let { recommendationEngine.similarTracks(it, limit = 8) }.orEmpty()
            _lyrics.value = track?.let {
                LyricsAnalyzer.loadLrc(getApplication<Application>(), Uri.parse(it.path))
            }.orEmpty()
        }
    }

    /**
     * MEDIA_ITEM_TRANSITION_REASON_AUTO (0) = the track finished naturally (completed).
     * Anything else (SEEK/REPEAT/PLAYLIST_CHANGED) while a track was loaded is treated
     * as a manual skip for the MVP's simple heuristic — good enough to start collecting
     * signal; refine once real listening patterns are observed.
     */
    private fun finalizePreviousSession(transitionReason: Int) {
        val track = sessionTrack ?: return
        val duration = track.durationMs.takeIf { it > 0 } ?: return
        val completedPct = (maxPositionReachedMs.toFloat() / duration).coerceIn(0f, 1f)
        val wasSkip = transitionReason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && completedPct < 0.8f

        viewModelScope.launch {
            repository.recordListening(
                ListeningHistoryEntity(
                    trackId = track.id,
                    startTime = sessionStartTime,
                    listenDurationMs = maxPositionReachedMs,
                    completedPercentage = completedPct,
                    skipped = wasSkip
                )
            )
        }
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        finalizePreviousSession(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        controller.release()
        super.onCleared()
    }
}
