package com.ghadirb.aimusic.music

/** Several ZIP links that are really the SAME collection (the 128 kbps copy next to the original, or two sites) become one row. */
object CollectionCopies {
    private fun rank(quality: Int?): Int = quality ?: Int.MAX_VALUE // unmarked = the original = best

    /**
     * One entry per collection ([OnlineCollection.key]); the best copy is the main link, the others are kept in
     * [OnlineCollection.altUrls] and tried when it fails. Newest first.
     */
    fun group(list: List<OnlineCollection>, priority: Map<String, Int> = DEFAULT_SOURCE_PRIORITY): List<OnlineCollection> =
        list.groupBy { it.key }.values.map { copies ->
            val sorted = copies.distinctBy { it.url }.sortedWith(
                compareByDescending<OnlineCollection> { rank(it.quality) }
                    .thenBy { priority[it.sourceId] ?: 99 }
                    .thenByDescending { it.folderIso },
            )
            val best = sorted.first()
            best.copy(
                altUrls = (best.altUrls + sorted.drop(1).flatMap { listOf(it.url) + it.altUrls }).filter { it != best.url }.distinct(),
                folderIso = sorted.maxOf { it.folderIso },
            )
        }.sortedByDescending { it.folderIso }
}
