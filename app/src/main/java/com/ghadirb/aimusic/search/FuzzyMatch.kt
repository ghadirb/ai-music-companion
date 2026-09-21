package com.ghadirb.aimusic.search

/** Typo tolerance for search (adjacent swaps, one missing/extra/wrong letter). */
object FuzzyMatch {
    /** Allowed edit distance for a query word of this length (short words must be nearly exact). */
    fun maxDistanceFor(length: Int): Int = when {
        length < 3 -> 0
        length < 8 -> 1
        else -> 2
    }

    /** Optimal-string-alignment (Damerau-Levenshtein) distance, or max+1 when it exceeds [max]. */
    fun distanceAtMost(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        var older = prev2
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, older[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val tmp = older; older = prev; prev = cur; cur = tmp
        }
        return prev[b.length].coerceAtMost(max + 1)
    }

    /** True when every query token is within tolerance of some word of the (already normalised) text. */
    fun matchesAll(words: List<String>, tokens: List<String>): Boolean = tokens.isNotEmpty() && tokens.all { token ->
        val max = maxDistanceFor(token.length)
        words.any { w -> w.contains(token) || distanceAtMost(token, w, max) <= max }
    }
}
