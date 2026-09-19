package com.ghadirb.aimusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Media3 service. Owns the single ExoPlayer + MediaSession so playback survives the
 * Activity being destroyed. It also owns: audio focus / becoming-noisy handling, listening-history
 * recording, the sleep timer hook and queue persistence ("continue playback").
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var stateStore: PlaybackStateStore
    private var recorder: ListeningRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val persistListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) persist() }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = persist()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persist()
        override fun onRepeatModeChanged(repeatMode: Int) = persist()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                SleepTimerController.onPausedAtEndOfItem()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        player = ExoPlayer.Builder(this)
            // Audio focus: pauses for calls/other media and resumes correctly afterwards.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pause when headphones are unplugged / Bluetooth disconnects.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(persistListener)
        SleepTimerController.attach(player)

        val app = application as AiMusicApp
        recorder = ListeningRecorder(player, app.repository, scope).also { it.start() }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_player", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent)
            .setCallback(SessionCallback())
            .build()

        scope.launch {
            while (isActive) {
                delay(PERSIST_INTERVAL_MS)
                if (player.isPlaying) persist()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persist()
        // Keep playing in the background if music is actually playing; otherwise free the service.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        persist()
        recorder?.stop()
        recorder = null
        SleepTimerController.detach()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(persistListener)
        player.release()
        super.onDestroy()
    }

    private fun persist() {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (ids.isEmpty()) return
        stateStore.save(
            SavedPlayback(
                trackIds = ids,
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode
            )
        )
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** Media button / Bluetooth "play" after the process died: rebuild the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val saved = stateStore.load()
                    val repository = (application as AiMusicApp).repository
                    val tracks = saved?.trackIds.orEmpty().mapNotNull { repository.getTrack(it) }
                    if (saved == null || tracks.isEmpty()) {
                        future.setException(UnsupportedOperationException("Nothing to resume"))
                    } else {
                        val currentId = saved.trackIds.getOrNull(saved.index)
                        val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                tracks.map { it.toMediaItem() }, index, saved.positionMs
                            )
                        )
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 15_000L
    }
}
