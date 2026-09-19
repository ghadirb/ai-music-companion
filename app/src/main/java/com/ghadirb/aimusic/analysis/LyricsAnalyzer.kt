package com.ghadirb.aimusic.analysis

import com.ghadirb.aimusic.lyrics.LrcParser

/**
 * Best-effort Persian lyric mood analysis over lyrics text the user already owns (a local .lrc
 * next to the track, an imported .lrc, or a file found in a folder the user granted). No network,
 * no bundled lyric database. Locating/reading the file lives in [com.ghadirb.aimusic.lyrics.LyricsSource];
 * this object only scores text and defines the shared line type.
 *
 * Sentiment scoring is a small hand-built keyword list, not a trained NLP model; ties -> null.
 */
object LyricsAnalyzer {

    /** One line of lyrics. For unsynced (plain) lyrics [timeMs] is just the line index. */
    data class LrcLine(val timeMs: Long, val text: String)

    object LyricMood {
        const val SAD = "sad"
        const val HAPPY = "happy"
    }

    private val sadWords = listOf(
        "غم", "غمگین", "اشک", "گریه", "دلتنگ", "تنها", "تنهایی", "درد", "رفتی", "جدایی",
        "حسرت", "داغ", "ماتم", "بغض", "شکست", "فراق", "سیاه", "دلشکسته"
    )
    private val happyWords = listOf(
        "شاد", "شادی", "خنده", "عشق", "جشن", "رقص", "خوشحال", "امید", "آرزو", "زیبا",
        "لبخند", "نور", "بهار", "جوانی", "شور", "پایکوبی"
    )

    /** Returns [LyricMood.SAD] / [LyricMood.HAPPY], or null if inconclusive or [rawLrcText] is null. */
    fun analyzeText(rawLrcText: String?): String? {
        if (rawLrcText.isNullOrBlank()) return null
        val plain = LrcParser.plainText(rawLrcText)
        var sadCount = 0
        var happyCount = 0
        for (w in sadWords) if (plain.contains(w)) sadCount++
        for (w in happyWords) if (plain.contains(w)) happyCount++
        return when {
            sadCount > happyCount -> LyricMood.SAD
            happyCount > sadCount -> LyricMood.HAPPY
            else -> null
        }
    }
}
