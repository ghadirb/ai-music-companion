package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity

/**
 * When a video is moved by copy + delete (older Android, or another storage), the new file gets a new
 * MediaStore id. This remembers the old state so favourites, resume position and playlists can be carried
 * over once the new file shows up in the library. Pure logic, JVM unit tested.
 */
object VideoMoveCarryOver {

    const val EXPIRE_MS = 14L * 24 * 3600 * 1000

    data class Pending(
        val oldId: Long,
        val name: String,
        val sizeBytes: Long,
        val positionMs: Long,
        val lastPlayedAt: Long,
        val favorite: Boolean,
        val createdAt: Long
    )

    fun pendingOf(v: VideoEntity, now: Long) =
        Pending(v.id, v.displayName, v.sizeBytes, v.lastPositionMs, v.lastPlayedAt, v.isFavorite, now)

    /** "Movie (2).mkv" -> "movie.mkv": the copy may have been renamed to avoid a clash. */
    private fun canonical(name: String): String {
        val ext = VideoFileTypes.extensionOf(name)
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        val stripped = base.replace(Regex(" \\(\\d+\\)$"), "")
        return (if (ext.isEmpty()) stripped else "$stripped.$ext").lowercase()
    }

    /** The new copy of [p] among [candidates] (same size and name, different id), or null while it is not indexed yet. */
    fun match(p: Pending, candidates: List<VideoEntity>): VideoEntity? =
        candidates
            .filter { it.id != p.oldId && it.sizeBytes == p.sizeBytes && canonical(it.displayName) == canonical(p.name) }
            .maxByOrNull { it.dateAdded }

    fun isExpired(p: Pending, now: Long) = now - p.createdAt > EXPIRE_MS

    // ---- tiny text format (no org.json, so it works in plain JVM tests) ----
    fun encode(list: List<Pending>): String = list.joinToString("\n") {
        listOf(it.oldId, it.name.replace(Regex("[\\t\\n\\r]"), " "), it.sizeBytes, it.positionMs, it.lastPlayedAt, if (it.favorite) 1 else 0, it.createdAt)
            .joinToString("\t")
    }

    fun decode(text: String): List<Pending> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 7) return@mapNotNull null
        runCatching {
            Pending(f[0].toLong(), f[1], f[2].toLong(), f[3].toLong(), f[4].toLong(), f[5] == "1", f[6].toLong())
        }.getOrNull()
    }
}
