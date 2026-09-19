package com.ghadirb.aimusic.playback

import androidx.media3.common.Player
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records listening sessions from the player itself (inside the playback service), so the
 * behavioural signal is correct even when the screen is off or the UI is destroyed. The old
 * UI-side recorder only tracked progress while the Player screen was visible.
 */
class ListeningRecorder(
    private val player: Player,
    private val repository: MusicRepository,
    private val scope: CoroutineScope
) : Player.Listener {

    private var currentId: Long? = null
    private var startedAt = 0L
    private var maxPositionMs = 0L
    private var played = false
    private var tickJob: Job? = null

    fun start() {
        player.addListener(this)
        beginSession(player.currentMediaItem?.mediaId?.toLongOrNull(), player.currentPosition)
        if (player.isPlaying) onIsPlayingChanged(true)
    }

    fun stop() {
        finishSession(player.currentPosition, auto = false, repeat = false)
        tickJob?.cancel()
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        // First item of a fresh queue: no discontinuity is reported, so start the session here.
        if (currentId == null) beginSession(mediaItem?.mediaId?.toLongOrNull(), 0L)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        val oldId = oldPosition.mediaItem?.mediaId?.toLongOrNull()
        val newId = newPosition.mediaItem?.mediaId?.toLongOrNull()
        val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        val itemChanged = oldId != newId || (auto && oldPosition.mediaItemIndex != newPosition.mediaItemIndex)
        if (auto && oldId != null && oldId == newId) {
            // Repeat-one: same item started over after finishing.
            finishSession(oldPosition.positionMs, auto = true, repeat = true)
            beginSession(newId, newPosition.positionMs)
            played = true
        } else if (itemChanged) {
            finishSession(oldPosition.positionMs, auto = auto, repeat = false)
            beginSession(newId, newPosition.positionMs)
            if (player.isPlaying) played = true
        } else {
            maxPositionMs = maxOf(maxPositionMs, newPosition.positionMs)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            played = true
            tickJob?.cancel()
            tickJob = scope.launch {
                while (isActive) {
                    maxPositionMs = maxOf(maxPositionMs, player.currentPosition)
                    delay(TICK_MS)
                }
            }
        } else {
            maxPositionMs = maxOf(maxPositionMs, player.currentPosition)
            tickJob?.cancel()
        }
    }

    private fun beginSession(id: Long?, positionMs: Long) {
        currentId = id
        startedAt = System.currentTimeMillis()
        maxPositionMs = positionMs.coerceAtLeast(0L)
        played = false
    }

    private fun finishSession(endPositionMs: Long, auto: Boolean, repeat: Boolean) {
        val trackId = currentId ?: return
        if (!played) return
        val listened = maxOf(maxPositionMs, endPositionMs).coerceAtLeast(0L)
        val started = startedAt
        scope.launch {
            val track = repository.getTrack(trackId) ?: return@launch
            val duration = track.durationMs.takeIf { it > 0 } ?: return@launch
            val completed = if (auto) 1f else (listened.toFloat() / duration).coerceIn(0f, 1f)
            runCatching {
                repository.recordListening(
                    ListeningHistoryEntity(
                        trackId = trackId,
                        startTime = started,
                        listenDurationMs = if (auto) duration else listened,
                        completedPercentage = completed,
                        skipped = !auto && completed < COMPLETED_THRESHOLD,
                        replayCount = if (repeat) 1 else 0
                    )
                )
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val COMPLETED_THRESHOLD = 0.8f
    }
}
