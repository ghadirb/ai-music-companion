package com.ghadirb.aimusic.video

/**
 * Pure rules for "ادامه پخش" (resume playback). No Android types, so they are unit tested on the JVM.
 */
object VideoResume {

    /** Past this fraction of the duration the video counts as finished and restarts from 0. */
    const val FINISHED_FRACTION = 0.95

    /** Positions before this are not worth resuming from (user barely started). */
    const val MIN_RESUME_MS = 3_000L

    /** How often the player persists the position while playing. Not per frame: one Room write every few seconds. */
    const val SAVE_INTERVAL_MS = 5_000L

    /** What to store for the current position: 0 when finished (or unknown duration / not started). */
    fun positionToSave(positionMs: Long, durationMs: Long): Long {
        if (positionMs < MIN_RESUME_MS) return 0L
        if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
        if (positionMs >= (durationMs * FINISHED_FRACTION).toLong()) return 0L
        return positionMs
    }

    /** Where playback should start for a stored position. A stale/invalid value falls back to 0. */
    fun startPosition(savedMs: Long, durationMs: Long): Long {
        if (savedMs < MIN_RESUME_MS) return 0L
        if (durationMs > 0L && savedMs >= (durationMs * FINISHED_FRACTION).toLong()) return 0L
        return savedMs
    }

    /** True if [VideoEntity.lastPositionMs]-style data means the item can be shown as "continue". */
    fun hasResume(savedMs: Long, durationMs: Long): Boolean = startPosition(savedMs, durationMs) > 0L
}
