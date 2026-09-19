package com.ghadirb.aimusic.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Countdown(val endsAtMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/**
 * Sleep timer that lives with the playback service (same process as the UI), so it keeps
 * working when the Activity/ViewModel is destroyed while music continues in the background.
 * All calls must happen on the main thread.
 */
@UnstableApi
object SleepTimerController {
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private val check = Runnable { onTick() }

    fun attach(player: ExoPlayer) { this.player = player }

    fun detach() {
        cancel()
        player = null
    }

    fun startCountdown(minutes: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (player == null || minutes !in 1..MAX_MINUTES) return false
        cancel()
        _state.value = SleepTimerState.Countdown(nowMs + minutes * 60_000L)
        scheduleTick()
        return true
    }

    fun startEndOfTrack(): Boolean {
        val exo = player ?: return false
        cancel()
        exo.pauseAtEndOfMediaItems = true
        _state.value = SleepTimerState.EndOfTrack
        return true
    }

    fun cancel() {
        handler.removeCallbacks(check)
        player?.pauseAtEndOfMediaItems = false
        _state.value = SleepTimerState.Off
    }

    /** Called by the service when playback paused itself at the end of an item. */
    fun onPausedAtEndOfItem() {
        if (_state.value is SleepTimerState.EndOfTrack) cancel()
    }

    private fun scheduleTick() {
        val current = _state.value as? SleepTimerState.Countdown ?: return
        val remaining = current.endsAtMs - System.currentTimeMillis()
        handler.postDelayed(check, remaining.coerceIn(250L, 15_000L))
    }

    private fun onTick() {
        val current = _state.value as? SleepTimerState.Countdown ?: return
        if (System.currentTimeMillis() >= current.endsAtMs) {
            player?.pause()
            cancel()
        } else {
            scheduleTick()
        }
    }

    const val MAX_MINUTES = 720
}
