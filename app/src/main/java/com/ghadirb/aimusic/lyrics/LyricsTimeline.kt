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
}
