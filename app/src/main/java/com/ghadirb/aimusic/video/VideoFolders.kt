package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity

/** One folder (MediaStore bucket) in the video tab's "by folder" view. */
data class VideoFolder(
    val key: String,
    val name: String,
    val count: Int,
    val totalDurationMs: Long
)

/** Pure grouping helpers for the folder view (JVM unit-tested). */
object VideoFolders {
    const val UNKNOWN_NAME = "سایر"

    /** relativePath tells apart two folders with the same name; older Android only has the bucket name. */
    fun keyOf(v: VideoEntity): String = v.relativePath.ifBlank { v.folderName }

    fun nameOf(v: VideoEntity): String =
        v.folderName.ifBlank {
            v.relativePath.trim('/').substringAfterLast('/')
        }.ifBlank { UNKNOWN_NAME }

    fun group(videos: List<VideoEntity>): List<VideoFolder> =
        videos.groupBy { keyOf(it) }
            .map { (key, list) ->
                VideoFolder(key, nameOf(list.first()), list.size, list.sumOf { it.durationMs })
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun inFolder(videos: List<VideoEntity>, key: String): List<VideoEntity> =
        videos.filter { keyOf(it) == key }
}
