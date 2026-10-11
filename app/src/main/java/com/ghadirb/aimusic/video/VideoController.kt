package com.ghadirb.aimusic.video

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * What the single video player screen needs from a playback engine. Two engines implement it:
 * [VideoPlaybackController] (Media3/ExoPlayer, default) and [VlcPlaybackController] (libVLC, for
 * formats/codecs the phone cannot play). The screen, controls and gestures are shared, so both look
 * and behave the same.
 */
interface VideoController {
    val state: StateFlow<VideoPlaybackController.State>

    /** Builds the queue from [ids] and starts [startId]; [startMs] < 0 means "resume where it was left". */
    suspend fun load(ids: List<Long>, startId: Long, startMs: Long = -1L)

    fun playPause()
    fun resume()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun playIndex(index: Int)
    fun retry()
    fun setSoftwareDecoding(enabled: Boolean, persist: Boolean = true)
    fun setSpeed(speed: Float)

    fun selectSubtitle(index: Int)
    fun disableSubtitles()
    /** Adds an external subtitle chosen through the system picker; false when the format is unsupported. */
    fun addExternalSubtitle(uri: Uri, displayName: String?): Boolean
    fun selectAudio(index: Int)

    fun saveCurrent()
    fun release()
}

/** Playback speed steps shared by both engines. */
object PlaybackSpeeds {
    val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Next step after [current] (wraps to the slowest). */
    fun next(current: Float): Float = steps.firstOrNull { it > current + 0.001f } ?: steps.first()

    fun label(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed.toString().trimEnd('0')}×"
}
