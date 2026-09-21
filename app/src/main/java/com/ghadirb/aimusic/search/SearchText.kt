package com.ghadirb.aimusic.search

/**
 * Text normalisation for local search so Persian/Arabic/English queries match reliably:
 * Arabic ي/ك become Persian ی/ک, diacritics/tatweel are dropped, ZWNJ (نیم‌فاصله) acts like a
 * space, Persian/Arabic digits become ASCII, punctuation is ignored and case is folded.
 */
object SearchText {

    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val out = StringBuilder(input.length)
        var lastWasSpace = true
        for (raw in input) {
            val folded = fold(raw)
            if (folded == DROP) continue
            if (folded != SPACE && Character.isLetterOrDigit(folded)) {
                out.append(folded.lowercaseChar())
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    private val persianScript = Regex("[\\u0600-\\u06FF]")

    /** True when [text] contains Arabic-script (Persian/Arabic) letters. */
    fun hasPersianScript(text: String?): Boolean = !text.isNullOrEmpty() && persianScript.containsMatchIn(text)

    fun tokens(query: String?): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    fun matchesAll(normalizedText: String, tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all { normalizedText.contains(it) }

    private const val DROP = '\u0000'
    private const val SPACE = ' '

    private fun fold(ch: Char): Char = when (ch) {
        '\u064A', '\u0649' -> '\u06CC'                      // ي ى -> ی
        '\u0643' -> '\u06A9'                                // ك -> ک
        '\u0629', '\u06C0' -> '\u0647'                      // ة ۀ -> ه
        '\u0622', '\u0623', '\u0625', '\u0671' -> '\u0627'  // آ أ إ ٱ -> ا
        '\u0624' -> '\u0648'                                // ؤ -> و
        in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')      // Persian digits
        in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')      // Arabic-Indic digits
        in '\u064B'..'\u065F', '\u0670', '\u0640', '\u200D', '\u200E', '\u200F' -> DROP
        '\u200C' -> SPACE                                   // ZWNJ
        else -> ch
    }
}
