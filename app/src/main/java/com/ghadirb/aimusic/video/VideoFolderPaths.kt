package com.ghadirb.aimusic.video

/** Pure helper for "move to a folder under Movies" (JVM unit tested). */
object VideoFolderPaths {
    private val FORBIDDEN = Regex("[:*?\"<>|\\u0000-\\u001F]")
    const val ROOT = "Movies"

    /**
     * Turns what the user typed ("Family/2025", "  Trips ") into a MediaStore RELATIVE_PATH like
     * "Movies/Family/2025/". Empty input means the Movies folder itself. Returns null for unsafe input
     * (".." segments, forbidden characters).
     */
    fun relativePath(input: String): String? {
        val parts = input.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." || FORBIDDEN.containsMatchIn(it) || it.length > 80 }) return null
        // Users may type the full "Movies/..." themselves.
        val cleaned = if (parts.firstOrNull().equals(ROOT, ignoreCase = true)) parts.drop(1) else parts
        return (listOf(ROOT) + cleaned).joinToString("/") + "/"
    }

    /** Folder of a MediaStore relative path as shown to the user, e.g. "Movies/Family/" -> "Family". */
    fun displayName(relativePath: String): String =
        relativePath.trim('/').substringAfterLast('/').ifEmpty { ROOT }
}
