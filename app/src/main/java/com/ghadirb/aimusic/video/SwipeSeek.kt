package com.ghadirb.aimusic.video

import java.util.Locale
import kotlin.math.abs

/**
 * Horizontal-swipe seeking for the video player: dragging right goes forward, left goes back.
 * A swipe across the whole screen width moves [FULL_WIDTH_MS]. Pure Kotlin (JVM unit tested).
 */
object SwipeSeek {
    const val FULL_WIDTH_MS = 90_000L

    /** Position after dragging [dragPx] pixels on a screen [widthPx] wide, starting at [startMs]. Unknown duration = no seeking. */
    fun target(startMs: Long, durationMs: Long, dragPx: Float, widthPx: Float): Long {
        if (durationMs <= 0L || widthPx <= 0f) return startMs
        val delta = (dragPx / widthPx * FULL_WIDTH_MS).toLong()
        // Never land exactly on the end: that would immediately trigger "next video".
        val upper = (durationMs - 1_000L).coerceAtLeast(0L)
        return (startMs + delta).coerceIn(0L, upper)
    }

    /** mm:ss, or h:mm:ss for videos of an hour or more. */
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** "+00:12" / "-00:05" */
    fun formatDelta(deltaMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        return sign + format(abs(deltaMs))
    }
}
