package com.ghadirb.aimusic.music

/** One playable copy of a song on one source. */
data class OnlineVersion(
    val url: String,
    val sourceId: String,
    val source: String,
    val quality: Int?,
    val size: Long?,
    /** false only when a server health check proved the link dead. */
    val ok: Boolean = true,
)

/** A song with every known source version (merged), newest first. [folderIso] is when the file was FILED, not a proven release date. */
data class OnlineTrack(
    val id: String,
    val artist: String?,
    val title: String,
    val folderIso: String,
    val versions: List<OnlineVersion>,
) {
    val key: String get() = Autoindex.trackKey(artist, title)
    val display: String get() = if (artist.isNullOrBlank()) title else "$artist - $title"
}

data class OnlineArtist(val key: String, val name: String, val collections: Int, val latestIso: String)

data class OnlineCollection(
    val url: String,
    val sourceId: String,
    val source: String,
    val fileName: String,
    val collection: String,
    val artist: String?,
    val artistKey: String,
    val size: Long?,
    val folderIso: String,
    val entryCount: Int?,
    /** true = discovered by the phone itself (sources the Worker cannot reach). */
    val onDevice: Boolean = false,
    /** Other copies of the SAME collection (e.g. the 128 kbps ZIP next to the full-quality one). Tried when this link fails. */
    val altUrls: List<String> = emptyList(),
) {
    val kind: Autoindex.CollectionKind get() = Autoindex.collectionKind(artist, collection)
    /** kbps from the file name; null = unmarked = the original (best) file. */
    val quality: Int? get() = Autoindex.parseTrackName(fileName).quality
    val key: String get() = Autoindex.collectionKey(artist, collection)
}

data class ArchiveTrack(val path: String, val name: String, val size: Long)

/** Source priority used when a song exists on several sites (lower = tried first). Overridden by the server config when available. */
val DEFAULT_SOURCE_PRIORITY = mapOf("ahaang" to 1, "giso" to 2, "mellody" to 3, "roz" to 4)
const val AHAANG_ID = "ahaang"
const val AHAANG_NAME = "آهنگ"
