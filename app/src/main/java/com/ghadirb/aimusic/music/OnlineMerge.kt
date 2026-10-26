package com.ghadirb.aimusic.music

/** Pure merge/dedupe/sort logic for the "new songs" list (server catalog + songs the phone found itself). */
object OnlineMerge {

    /** Every typed word must occur in the normalized "artist title" (so Arabic/Persian typing variants still match). */
    fun matches(track: OnlineTrack, query: String): Boolean {
        val words = Autoindex.normalizeText(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = Autoindex.normalizeText("${track.artist ?: ""} ${track.title}")
        return words.all { hay.contains(it) }
    }

    fun versionOrder(priority: Map<String, Int>): Comparator<OnlineVersion> =
        compareBy<OnlineVersion> { if (it.ok) 0 else 1 } // known-dead links last
            .thenBy { priority[it.sourceId] ?: 99 }
            .thenByDescending { it.quality ?: 999 }       // no stated bitrate usually means the original/highest quality
            .thenBy { it.url }

    /**
     * One entry per song: versions of all sources are kept (best source first), the date is the EARLIEST day the song
     * was filed anywhere, and the list is sorted by that date (newest first) independent of source priority.
     */
    fun merge(
        server: List<OnlineTrack>,
        device: List<OnlineTrack>,
        priority: Map<String, Int> = DEFAULT_SOURCE_PRIORITY,
        query: String = "",
        sinceIso: String = "",
    ): List<OnlineTrack> {
        val byKey = LinkedHashMap<String, OnlineTrack>()
        for (t in server + device) {
            if (t.folderIso < sinceIso && sinceIso.isNotEmpty()) continue
            if (!matches(t, query)) continue
            val k = t.key
            val existing = byKey[k]
            byKey[k] = if (existing == null) t else {
                val urls = HashSet<String>()
                val versions = (existing.versions + t.versions).filter { urls.add(it.url) }
                existing.copy(
                    // The displayed spelling comes from the best-priority version's track.
                    artist = if (bestPriority(t, priority) < bestPriority(existing, priority)) t.artist else existing.artist,
                    title = if (bestPriority(t, priority) < bestPriority(existing, priority)) t.title else existing.title,
                    id = if (existing.id.startsWith("dev:") && !t.id.startsWith("dev:")) t.id else existing.id,
                    folderIso = minOf(existing.folderIso, t.folderIso),
                    versions = versions,
                )
            }
        }
        val cmp = versionOrder(priority)
        return byKey.values
            .map { it.copy(versions = it.versions.sortedWith(cmp)) }
            .sortedWith(compareByDescending<OnlineTrack> { it.folderIso }.thenBy { it.display })
    }

    private fun bestPriority(t: OnlineTrack, priority: Map<String, Int>) = t.versions.minOfOrNull { priority[it.sourceId] ?: 99 } ?: 99

    /** Next version to try after [failed] links (same song only - never a lookalike). Null when every link failed. */
    fun nextVersion(track: OnlineTrack, failed: Set<String>): OnlineVersion? = track.versions.firstOrNull { it.url !in failed }

    /** Stable id for songs the phone found itself. */
    fun deviceId(url: String): String = "dev:" + Integer.toHexString(url.hashCode())
}
