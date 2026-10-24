package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntryEntity

/** A playlist with the videos that are currently available, in the user's order. */
data class ResolvedVideoPlaylist(val playlist: VideoPlaylistEntity, val videos: List<VideoEntity>) {
    val count: Int get() = videos.size
    val cover: VideoEntity? get() = videos.firstOrNull()
}

/** Pure playlist rules (JVM unit tested). */
object VideoPlaylistLogic {

    /** Joins entries to videos; entries whose video is not in the library right now are hidden, not deleted. */
    fun resolve(
        playlists: List<VideoPlaylistEntity>,
        entries: List<VideoPlaylistEntryEntity>,
        videos: List<VideoEntity>
    ): List<ResolvedVideoPlaylist> {
        val byId = videos.associateBy { it.id }
        val grouped = entries.groupBy { it.playlistId }
        return playlists.map { p ->
            val items = grouped[p.id].orEmpty().sortedBy { it.position }.mapNotNull { byId[it.videoId] }
            ResolvedVideoPlaylist(p, items)
        }
    }

    /** Moves the item at [from] to [to]; out-of-range requests return the list unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(60)

    /** What plays after the last video: stop (default) or loop the whole list. */
    fun nextIndex(current: Int, size: Int, loopAll: Boolean): Int? = when {
        size <= 0 -> null
        current + 1 < size -> current + 1
        loopAll -> 0
        else -> null
    }
}
