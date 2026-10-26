package com.ghadirb.aimusic.playback

import android.content.Context
import android.net.Uri

/** The queue to rebuild after the process was killed, with tracks that no longer exist removed. */
data class RestorePlan(val keptIds: List<Long>, val index: Int, val positionMs: Long)

/**
 * Pure rules for "continue playback" (no Android types, unit tested on the JVM).
 *
 * A saved queue can contain tracks that were deleted, moved or became unreadable since it was saved.
 * Those are dropped. If the track that was playing is gone, the saved position belonged to it, so it
 * must NOT be applied to a different track: playback starts from the start of the next surviving track.
 */
object SessionRestore {

    /** Only the current track and this many following ones are checked on disk; the rest rely on the player's skip-on-error. */
    const val EAGER_CHECK_WINDOW = 3

    fun plan(saved: SavedPlayback, isAvailable: (Long) -> Boolean): RestorePlan? {
        val kept = saved.trackIds.filter(isAvailable)
        if (kept.isEmpty()) return null
        val currentId = saved.trackIds.getOrNull(saved.index)
        val sameIndex = kept.indexOf(currentId)
        if (sameIndex >= 0) return RestorePlan(kept, sameIndex, saved.positionMs.coerceAtLeast(0L))
        // The current track vanished: continue with the next saved track that still exists (else the first).
        val next = saved.trackIds.drop(saved.index + 1).firstOrNull { it in kept }
        return RestorePlan(kept, next?.let { kept.indexOf(it) } ?: 0, 0L)
    }

    /**
     * Keeps at most [max] ids, as a window that always contains the current track. (A plain "take the
     * first N" would make a queue longer than N resume on the wrong track once the index passed N.)
     */
    fun limit(state: SavedPlayback, max: Int): SavedPlayback {
        val size = state.trackIds.size
        if (max <= 0 || size <= max) return state
        val index = state.index.coerceIn(0, size - 1)
        val start = (index - max / 2).coerceIn(0, size - max)
        return state.copy(trackIds = state.trackIds.subList(start, start + max), index = index - start)
    }

    /** Positions of [saved] ids that deserve a real file check: the current track plus a short look-ahead. */
    fun needsFileCheck(saved: SavedPlayback, id: Long): Boolean {
        val pos = saved.trackIds.indexOf(id)
        return pos >= saved.index && pos <= saved.index + EAGER_CHECK_WINDOW
    }
}

/** Cheap "can this content uri still be opened?" check. Must be called off the main thread. */
object TrackAvailability {
    fun isReadable(context: Context, uriString: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use { true } ?: false
    }.getOrDefault(false)
}
