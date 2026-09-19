package com.ghadirb.aimusic.ui.screens.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
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

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var currentQueue: List<TrackEntity> = emptyList()
    private var sessionStartTime: Long = 0L
    private var sessionTrack: TrackEntity? = null
    private var maxPositionReachedMs: Long = 0L

    init {
        controller.connect { mediaController ->
            mediaController.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
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
            durationMs = controller.duration()
        )
    }

    private fun startNewSession(track: TrackEntity?) {
        sessionTrack = track
        sessionStartTime = System.currentTimeMillis()
        maxPositionReachedMs = 0L
        _uiState.value = _uiState.value.copy(currentTrack = track, positionMs = 0L)
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
        finalizePreviousSession(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        controller.release()
        super.onCleared()
    }
}
