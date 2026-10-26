package com.ghadirb.aimusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.MainActivity
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.playback.PlaybackService
import com.ghadirb.aimusic.playback.PlaybackStateStore
import com.ghadirb.aimusic.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget (spec v1.1 item 5): track title/artist + play-pause/prev/next.
 * No extra dependency (Glance etc.) — plain RemoteViews, matching the "no heavy
 * dependencies" constraint. Transport buttons briefly connect a [MediaController] to the
 * existing [PlaybackService] session (the same mechanism PlayerController already uses for
 * the in-app UI) and release it once the command is sent; the widget itself is refreshed via
 * [refresh] from inside the service whenever playback state actually changes, so it never has
 * to keep a connection open.
 */
@UnstableApi
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                // BroadcastReceiver.onReceive gets a *restricted* Context — calling
                // MediaController.Builder(...).buildAsync() on it throws
                // ReceiverCallNotAllowedException (bindService isn't allowed from a receiver),
                // crashing the whole app process since the receiver runs in-process. Use
                // applicationContext instead. goAsync() then keeps this receiver (and, if the
                // app wasn't already running, its process) alive long enough for the async
                // Binder connection + command to actually complete — without it, Android is
                // free to kill the process the instant onReceive() returns, before the
                // (inherently async) controller connection resolves.
                val pendingResult = goAsync()
                withController(context.applicationContext, intent.action) {
                    pendingResult.finish()
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Paint immediately with what we know, so the widget is never left blank.
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, WidgetState.last))
        }
        // Cold widget (app process just started / nothing played yet): show the last track that was
        // playing instead of the idle text, read from the same saved state "continue playback" uses.
        if (WidgetState.last.title == null) {
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val saved = PlaybackStateStore(appContext).load()
                    val id = saved?.trackIds?.getOrNull(saved.index)
                    val track = if (id != null) (appContext as? AiMusicApp)?.repository?.getTrack(id) else null
                    if (track != null && WidgetState.last.title == null) {
                        val state = WidgetState(track.title, track.artist, false)
                        WidgetState.last = state
                        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, buildViews(appContext, state)) }
                    }
                } catch (e: Exception) {
                    // Best effort only; the idle layout is already shown.
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun withController(appContext: Context, action: String?, onDone: () -> Unit) {
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener(
                {
                    val controller = runCatching { future.get() }.getOrNull()
                    if (controller == null) {
                        onDone()
                    } else {
                        // Cold start: when the app was closed (process killed, or the service was
                        // stopped after the task was swiped away while paused) the freshly created
                        // service has an EMPTY player, so play()/next()/previous() would silently do
                        // nothing. Media3's onPlaybackResumption() is only used for media-button
                        // events, not for plain controller commands, so rebuild the last saved queue
                        // here first, exactly like the in-app "continue playback" does.
                        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                            try {
                                if (controller.mediaItemCount == 0) restoreLastQueue(appContext, controller)
                                if (controller.mediaItemCount > 0) {
                                    when (action) {
                                        ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                                        ACTION_NEXT -> controller.seekToNextMediaItem()
                                        ACTION_PREV -> controller.seekToPreviousMediaItem()
                                    }
                                }
                            } catch (e: Exception) {
                                // Never let a widget tap take the app down.
                            } finally {
                                runCatching { controller.release() }
                                onDone()
                            }
                        }
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        } catch (e: Exception) {
            // Never let a widget tap take the app down — worst case the tap is a no-op.
            onDone()
        }
    }

    /** Re-creates the last saved queue (same data PlaybackService.onPlaybackResumption uses). */
    private suspend fun restoreLastQueue(appContext: Context, controller: MediaController) {
        val saved = PlaybackStateStore(appContext).load() ?: return
        val repository = (appContext as AiMusicApp).repository
        val tracks = saved.trackIds.mapNotNull { repository.getTrack(it) }
        if (tracks.isEmpty()) return
        val currentId = saved.trackIds.getOrNull(saved.index)
        val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        controller.shuffleModeEnabled = saved.shuffle
        controller.repeatMode = saved.repeatMode
        controller.setMediaItems(tracks.map { it.toMediaItem() }, index, saved.positionMs)
        controller.prepare()
    }

    companion object {
        private const val ACTION_TOGGLE = "com.ghadirb.aimusic.widget.ACTION_TOGGLE"
        private const val ACTION_NEXT = "com.ghadirb.aimusic.widget.ACTION_NEXT"
        private const val ACTION_PREV = "com.ghadirb.aimusic.widget.ACTION_PREV"

        /** Called from PlaybackService's player listener whenever playback state actually changes. */
        fun refresh(context: Context, state: WidgetState) {
            WidgetState.last = state
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, state)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        internal fun buildViews(context: Context, state: WidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)
            views.setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, state.artist ?: context.getString(R.string.widget_subtitle_idle))
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            fun pending(action: String, requestCode: Int) = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, MusicWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, pending(ACTION_TOGGLE, 1))
            views.setOnClickPendingIntent(R.id.widget_next, pending(ACTION_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_prev, pending(ACTION_PREV, 3))
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 4,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("open_player", true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }
    }
}

/** Plain snapshot of what the widget shows; kept in memory only (rebuilt from the live Player on demand). */
data class WidgetState(val title: String?, val artist: String?, val isPlaying: Boolean) {
    companion object {
        @Volatile var last: WidgetState = WidgetState(null, null, false)

        fun from(player: Player): WidgetState {
            val meta = player.currentMediaItem?.mediaMetadata
            return WidgetState(
                title = meta?.title?.toString(),
                artist = meta?.artist?.toString(),
                isPlaying = player.isPlaying
            )
        }
    }
}
