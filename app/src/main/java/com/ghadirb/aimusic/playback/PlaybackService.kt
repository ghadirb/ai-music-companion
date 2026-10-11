package com.ghadirb.aimusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Media3 service. Owns the single ExoPlayer + MediaSession so playback survives the
 * Activity being destroyed. It also owns: audio focus / becoming-noisy handling, listening-history
 * recording, the sleep timer hook and queue persistence ("continue playback").
 *
 * A [MediaLibraryService] rather than a plain MediaSessionService (as of spec item 9) so Android
 * Auto / Assistant can browse Favorites, Playlists, Smart Mixes etc. — see [BrowseTree]. This is
 * purely additive: every phone-side controller call (PlayerController) still goes through the
 * exact same [ExoPlayer] + [MediaSession], unchanged from before.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var stateStore: PlaybackStateStore
    private var recorder: ListeningRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Consecutive unplayable items; guards against an endless skip loop (e.g. repeat-all with every file missing). */
    private var consecutiveErrors = 0

    private val persistListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val failing = player.currentMediaItem
            if (failing?.mediaId?.startsWith("online:") == true) {
                // The app's online player first tries another source's copy of the SAME song; skip only if nobody did in time.
                scope.launch {
                    delay(ONLINE_FALLBACK_GRACE_MS)
                    if (player.playerError != null && player.currentMediaItem?.mediaId == failing.mediaId) skipBrokenItem()
                }
                return
            }
            skipBrokenItem()
        }

        // A missing/unreadable file must not leave the player stuck: move on to the next track.
        private fun skipBrokenItem() {
            consecutiveErrors++
            if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
            if (!isPlaying) persist()
            com.ghadirb.aimusic.widget.MusicWidgetProvider.refresh(this@PlaybackService, com.ghadirb.aimusic.widget.WidgetState.from(player))
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persist()
            com.ghadirb.aimusic.widget.MusicWidgetProvider.refresh(this@PlaybackService, com.ghadirb.aimusic.widget.WidgetState.from(player))
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persist()
        override fun onRepeatModeChanged(repeatMode: Int) = persist()
        // Queue edits (add next, reorder, remove) must reach the saved queue even without a track change,
        // because the periodic save only updates the position.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) persist()
        }
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
        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openAppIntent)
            .build()

        scope.launch {
            while (isActive) {
                delay(PERSIST_INTERVAL_MS)
                if (player.isPlaying) stateStore.savePosition(player.currentPosition)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

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

    private fun isEntitled(): Boolean =
        (application as AiMusicApp).entitlements.entitlement.value.isPremiumAt(System.currentTimeMillis())

    private inner class SessionCallback : MediaLibrarySession.Callback {

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
                    val byId = saved?.trackIds.orEmpty().mapNotNull { repository.getTrack(it) }.associateBy { it.id }
                    // Skip tracks deleted/moved since the queue was saved; a vanished current track must not inherit its old position.
                    val plan = if (saved == null) null else withContext(Dispatchers.IO) {
                        SessionRestore.plan(saved) { id ->
                            val track = byId[id] ?: return@plan false
                            !SessionRestore.needsFileCheck(saved, id) || TrackAvailability.isReadable(this@PlaybackService, track.path)
                        }
                    }
                    if (plan == null) {
                        future.setException(UnsupportedOperationException("Nothing to resume"))
                    } else {
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                plan.keptIds.mapNotNull { byId[it]?.toMediaItem() }, plan.index, plan.positionMs
                            )
                        )
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        // ---- Android Auto / Assistant browsing (spec item 9) — delegates to BrowseTree, which
        // reads the same repository the phone UI does, so the two can never disagree. ----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(BrowseTree.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                try {
                    val repository = (application as AiMusicApp).repository
                    val item = BrowseTree.item(repository, mediaId, isEntitled())
                    future.set(
                        if (item != null) LibraryResult.ofItem(item, null)
                        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    )
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                try {
                    val repository = (application as AiMusicApp).repository
                    val children = BrowseTree.children(repository, parentId, isEntitled())
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children.orEmpty()), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        /**
         * Tapping a track inside a browsed Auto folder (Favorites, a playlist, …) arrives here as a
         * single bare [MediaItem] (mediaId only, no URI yet). We expand it to the full sibling list
         * so next/previous keep working, starting at the tapped track — the standard Media3 pattern
         * for browsable queues. Anything the app itself already queued (PlayerController — always a
         * full list of real, URI-bearing items) is left completely untouched.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val tapped = mediaItems.singleOrNull()
            if (tapped == null || tapped.localConfiguration != null) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val repository = (application as AiMusicApp).repository
                    val tappedId = tapped.mediaId
                    val siblings = BrowseTree.siblingsOf(repository, tappedId)
                    when {
                        !siblings.isNullOrEmpty() -> {
                            val index = siblings.indexOfFirst { it.mediaId == tappedId }.coerceAtLeast(0)
                            future.set(MediaSession.MediaItemsWithStartPosition(siblings, index, startPositionMs))
                        }
                        else -> {
                            val single = tappedId.toLongOrNull()?.let { repository.getTrack(it) }?.toMediaItem()
                            if (single != null) {
                                future.set(MediaSession.MediaItemsWithStartPosition(listOf(single), 0, startPositionMs))
                            } else {
                                future.setException(UnsupportedOperationException("Unknown media id: $tappedId"))
                            }
                        }
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
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val ONLINE_FALLBACK_GRACE_MS = 4_000L
    }
}
