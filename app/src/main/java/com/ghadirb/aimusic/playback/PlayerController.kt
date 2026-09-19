package com.ghadirb.aimusic.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/**
 * Thin wrapper around a Media3 MediaController bound to PlaybackService.
 * ViewModels use this instead of talking to ExoPlayer/the service directly,
 * so the playback backend can change without touching the UI layer.
 */
@UnstableApi
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null
    private val listeners = mutableListOf<Player.Listener>()

    fun connect(onConnected: (MediaController) -> Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                controller = future.get()
                listeners.forEach { controller?.addListener(it) }
                controller?.let(onConnected)
            },
            MoreExecutors.directExecutor()
        )
    }

    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        controller?.addListener(listener)
    }

    fun playTrack(track: TrackEntity, queue: List<TrackEntity> = listOf(track)) {
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val mediaItems = queue.map { it.toMediaItem() }
        controller?.apply {
            setMediaItems(mediaItems, startIndex, 0L)
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        controller?.apply { if (isPlaying) pause() else play() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrevious() = controller?.seekToPreviousMediaItem()
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
    fun pause() = controller?.pause()

    fun currentPosition(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long = controller?.duration?.coerceAtLeast(0) ?: 0L
    fun isPlaying(): Boolean = controller?.isPlaying ?: false
    fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId
    fun shuffleEnabled(): Boolean = controller?.shuffleModeEnabled ?: false
    fun repeatMode(): Int = controller?.repeatMode ?: Player.REPEAT_MODE_OFF

    fun release() {
        controller?.release()
        controller = null
    }

    private fun TrackEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(path)
            .setMediaId(id.toString())
            .build()
}
