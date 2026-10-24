package com.ghadirb.aimusic.video

/** Pure validation for renaming a video file (JVM unit tested). */
object VideoFileNames {

    data class Check(val name: String?, val error: String?)

    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    const val MAX_LENGTH = 120

    /**
     * [input] is what the user typed, [original] the current file name. If the user leaves out the extension the
     * original one is kept, so "Holiday" on "a.mkv" becomes "Holiday.mkv".
     */
    fun check(input: String, original: String): Check {
        val typed = input.trim().trimStart('.')
        if (typed.isEmpty()) return Check(null, "نام فایل نمی‌تواند خالی باشد.")
        if (FORBIDDEN.containsMatchIn(typed)) return Check(null, "نام فایل نباید شامل  \\ / : * ? \" < > |  باشد.")
        val originalExt = VideoFileTypes.extensionOf(original)
        val typedExt = VideoFileTypes.extensionOf(typed)
        val full = when {
            typedExt in VideoFileTypes.EXTENSIONS -> typed
            originalExt.isNotEmpty() -> "$typed.$originalExt"
            else -> typed
        }
        if (full.length > MAX_LENGTH) return Check(null, "نام فایل خیلی طولانی است.")
        if (full == original) return Check(null, "نام جدید با نام فعلی یکی است.")
        return Check(full, null)
    }
}
