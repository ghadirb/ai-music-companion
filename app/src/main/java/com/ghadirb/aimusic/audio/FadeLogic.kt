package com.ghadirb.aimusic.audio

/**
 * Fade between songs: the end of a song fades out and the next one fades in. This is NOT an overlapping crossfade
 * (that needs two players and would risk gapless playback); one player is kept, so gapless behaviour is unchanged.
 * Pure logic, JVM unit tested.
 */
object FadeLogic {
    const val MAX_SECONDS = 12

    /** Fade length actually used: never more than a third of the song, so short tracks are not mostly silence. */
    fun effectiveFadeMs(fadeSeconds: Int, durationMs: Long): Long {
        val wanted = fadeSeconds.coerceIn(0, MAX_SECONDS) * 1000L
        if (wanted <= 0L || durationMs <= 0L) return 0L
        return minOf(wanted, durationMs / 3)
    }

    /** Volume multiplier 0.0..1.0 at [positionMs]; 1.0 when fading is off or the duration is unknown. */
    fun factor(positionMs: Long, durationMs: Long, fadeSeconds: Int): Float {
        val fade = effectiveFadeMs(fadeSeconds, durationMs)
        if (fade <= 0L) return 1f
        val pos = positionMs.coerceIn(0L, durationMs)
        val fin = if (pos < fade) pos.toFloat() / fade else 1f
        val remaining = durationMs - pos
        val fout = if (remaining < fade) remaining.toFloat() / fade else 1f
        val t = minOf(fin, fout).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)          // smoothstep: no audible steps at either end
    }
}
