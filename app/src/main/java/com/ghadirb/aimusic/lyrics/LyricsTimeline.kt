package com.ghadirb.aimusic.lyrics

import com.ghadirb.aimusic.analysis.LyricsAnalyzer.LrcLine

/** Which lyric line is being sung right now (pure, unit-tested). */
object LyricsTimeline {
    /**
     * Index of the last line whose start time is <= [positionMs] + [leadMs] (a small lead so the highlight
     * changes just as the singer starts the line), or -1 before the first line. [lines] must be sorted by time.
     */
    fun activeIndex(lines: List<LrcLine>, positionMs: Long, leadMs: Long = 200L): Int {
        if (lines.isEmpty()) return -1
        val target = positionMs + leadMs
        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= target) { answer = mid; low = mid + 1 } else high = mid - 1
        }
        return answer
    }

    /**
     * Approximate line for lyrics WITHOUT timestamps: the sung part of the song is assumed to span most of the track
     * (short intro/outro skipped) and time is shared between lines in proportion to their length. It is an estimate,
     * so the UI labels it as such; real .lrc timestamps always win.
     */
    fun estimatedIndex(lines: List<LrcLine>, positionMs: Long, durationMs: Long): Int {
        if (lines.isEmpty() || durationMs <= 0) return -1
        val intro = minOf(12_000L, (durationMs * 0.08).toLong())
        val outro = minOf(10_000L, (durationMs * 0.06).toLong())
        val span = (durationMs - intro - outro).coerceAtLeast(1L)
        if (positionMs < intro) return -1
        val weights = lines.map { it.text.length + 10 }
        val total = weights.sum().toDouble()
        val target = ((positionMs - intro).toDouble() / span).coerceIn(0.0, 1.0) * total
        var cumulative = 0.0
        for (i in lines.indices) {
            cumulative += weights[i]
            if (target < cumulative) return i
        }
        return lines.lastIndex
    }
}
