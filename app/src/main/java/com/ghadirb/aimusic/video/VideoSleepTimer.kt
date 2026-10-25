package com.ghadirb.aimusic.video

/** Sleep timer for the video player (pure logic, JVM unit tested). */
sealed interface SleepMode {
    data object Off : SleepMode
    data class Minutes(val endsAtMs: Long) : SleepMode
    /** Stop when the video that was playing at start ends (or the queue moves on to another one). */
    data class EndOfVideo(val startIndex: Int) : SleepMode
}

object VideoSleepTimer {
    val PRESETS_MINUTES = listOf(15, 30, 45, 60, 90)

    /** Pause this long before the very end so the next queue item never starts playing. */
    private const val END_MARGIN_MS = 1_000L

    fun start(minutes: Int, nowMs: Long): SleepMode =
        if (minutes <= 0) SleepMode.Off else SleepMode.Minutes(nowMs + minutes * 60_000L)

    fun remainingMs(mode: SleepMode, nowMs: Long): Long =
        if (mode is SleepMode.Minutes) (mode.endsAtMs - nowMs).coerceAtLeast(0L) else 0L

    fun shouldPause(mode: SleepMode, nowMs: Long, currentIndex: Int, positionMs: Long, durationMs: Long): Boolean = when (mode) {
        SleepMode.Off -> false
        is SleepMode.Minutes -> nowMs >= mode.endsAtMs
        is SleepMode.EndOfVideo ->
            currentIndex != mode.startIndex || (durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS)
    }

    /** 75_000 -> "01:15", 3_725_000 -> "1:02:05". */
    fun formatRemaining(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
