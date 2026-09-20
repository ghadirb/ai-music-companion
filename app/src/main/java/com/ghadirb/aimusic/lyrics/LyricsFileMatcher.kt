package com.ghadirb.aimusic.lyrics

import com.ghadirb.aimusic.search.SearchText

/**
 * Finds the .lrc that belongs to a song by NAME, tolerant to case, Persian/Arabic letter variants (ي/ی, ك/ک),
 * ZWNJ, punctuation and leading track numbers ("01 - Song.mp3" ↔ "Song.lrc").
 */
object LyricsFileMatcher {
    private val leadingNumber = Regex("""^\d{1,3}\s*[-._)]*\s*""")

    /** Normalised lookup keys for a song, best first. */
    fun keysFor(audioBaseName: String?, title: String, artist: String?): List<String> {
        val keys = LinkedHashSet<String>()
        fun add(raw: String?) {
            val n = SearchText.normalize(raw)
            if (n.isNotEmpty()) keys.add(n)
        }
        add(audioBaseName)
        add(audioBaseName?.replace(leadingNumber, ""))
        if (!artist.isNullOrBlank() && artist != "Unknown artist") add("$artist - $title")
        add(title)
        return keys.toList()
    }

    /** Normalised base name of a lyrics file name ("Song.LRC" -> "song"). */
    fun keyOfFile(fileName: String): String? =
        if (fileName.endsWith(".lrc", ignoreCase = true)) SearchText.normalize(fileName.dropLast(4)).takeIf { it.isNotEmpty() } else null

    /** Picks the best file name for [keys] from [fileNames]; null if none matches. */
    fun pick(fileNames: Collection<String>, keys: List<String>): String? {
        val byKey = HashMap<String, String>()
        for (name in fileNames) keyOfFile(name)?.let { byKey.putIfAbsent(it, name) }
        return keys.firstNotNullOfOrNull { byKey[it] }
    }
}
