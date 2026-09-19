package com.ghadirb.aimusic.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Thin wrapper around a Media3 MediaController bound to PlaybackService. ViewModels use this
 * instead of talking to ExoPlayer/the service directly, so the playback backend can change
 * without touching the UI layer. Every call is a safe no-op until the controller is connected.
 */
@UnstableApi
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private var pending: ListenableFuture<MediaController>? = null
    private val listeners = mutableListOf<Player.Listener>()

    fun connect(onConnected: (MediaController) -> Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        pending = future
        future.addListener(
            {
                try {
                    val connected = future.get()
                    controller = connected
                    listeners.forEach { connected.addListener(it) }
                    onConnected(connected)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not connect to playback service: ${e.message}")
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        controller?.addListener(listener)
    }

    fun playTrack(track: TrackEntity, queue: List<TrackEntity> = listOf(track)) {
        val window = windowAround(queue, track)
        val startIndex = window.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        controller?.apply {
            setMediaItems(window.map { it.toMediaItem() }, startIndex, 0L)
            prepare()
            play()
        }
    }

    /** Restores a saved queue without starting playback (used on app start). */
    fun restoreQueue(tracks: List<TrackEntity>, index: Int, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        controller?.apply {
            setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(0, tracks.lastIndex), positionMs)
            shuffleModeEnabled = shuffle
            this.repeatMode = repeatMode
            prepare()
        }
    }

    // ---- Queue editing ----
    fun hasItems(): Boolean = (controller?.mediaItemCount ?: 0) > 0
    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: 0
    fun queueIds(): List<Long> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
    }

    fun addNext(track: TrackEntity) {
        val c = controller ?: return
        val index = if (c.mediaItemCount == 0) 0 else (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(index, track.toMediaItem())
    }

    fun addLast(track: TrackEntity) {
        controller?.addMediaItem(track.toMediaItem())
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    /** Removes everything after the current item. */
    fun clearUpcoming() {
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    fun stopAndClear() {
        controller?.apply {
            stop()
            clearMediaItems()
        }
    }

    fun seekToQueueItem(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    // ---- Transport ----
    fun togglePlayPause() {
        controller?.apply { if (isPlaying) pause() else play() }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun skipNext() { controller?.seekToNextMediaItem() }
    fun skipPrevious() { controller?.seekToPreviousMediaItem() }
    fun setShuffle(enabled: Boolean) { controller?.shuffleModeEnabled = enabled }
    fun cycleRepeatMode() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
    fun pause() { controller?.pause() }

    fun currentPosition(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long = controller?.duration?.coerceAtLeast(0) ?: 0L
    fun isPlaying(): Boolean = controller?.isPlaying ?: false
    fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId
    fun shuffleEnabled(): Boolean = controller?.shuffleModeEnabled ?: false
    fun repeatMode(): Int = controller?.repeatMode ?: Player.REPEAT_MODE_OFF

    fun release() {
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
        controller = null
    }

    /** Very large libraries are trimmed to a window around the selected track to keep IPC small. */
    private fun windowAround(queue: List<TrackEntity>, start: TrackEntity): List<TrackEntity> {
        if (queue.size <= MAX_QUEUE) return queue
        val index = queue.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
        val from = (index - KEEP_BEFORE).coerceAtLeast(0)
        return queue.subList(from, (from + MAX_QUEUE).coerceAtMost(queue.size))
    }

    private companion object {
        const val TAG = "PlayerController"
        const val MAX_QUEUE = 1500
        const val KEEP_BEFORE = 300
    }
}
