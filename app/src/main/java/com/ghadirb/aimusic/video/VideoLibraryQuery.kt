package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import java.util.Locale

enum class VideoSort(val label: String) {
    NEWEST("جدیدترین"),
    OLDEST("قدیمی‌ترین"),
    NAME("نام"),
    DURATION("طول ویدئو"),
    SIZE("حجم")
}

enum class VideoFilter(val label: String) {
    ALL("همه"),
    RECENT("اخیراً پخش‌شده")
}

/** Pure search / sort / filter for the video library (JVM unit tested). */
object VideoLibraryQuery {

    fun apply(
        videos: List<VideoEntity>,
        query: String,
        sort: VideoSort,
        filter: VideoFilter
    ): List<VideoEntity> {
        val base = if (filter == VideoFilter.RECENT) videos.filter { it.lastPlayedAt > 0L } else videos
        val q = normalize(query)
        val searched = if (q.isEmpty()) base else base.filter {
            normalize(it.displayName).contains(q) || normalize(it.folderName).contains(q)
        }
        // "اخیراً پخش‌شده" is always ordered by last play time; the sort menu applies to "همه".
        if (filter == VideoFilter.RECENT) return searched.sortedByDescending { it.lastPlayedAt }
        return when (sort) {
            VideoSort.NEWEST -> searched.sortedByDescending { it.dateAdded }
            VideoSort.OLDEST -> searched.sortedBy { it.dateAdded }
            VideoSort.NAME -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            VideoSort.DURATION -> searched.sortedByDescending { it.durationMs }
            VideoSort.SIZE -> searched.sortedByDescending { it.sizeBytes }
        }
    }

    /** Case-insensitive; folds Arabic ي/ك and Persian/Arabic-Indic digits so Persian search is forgiving. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.trim()) {
            sb.append(
                when (ch) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    '‌' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** "1:05:09" or "5:09". */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Human readable size, e.g. "1.4 GB". */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
    }

    /** "1920×1080" or null when unknown. */
    fun formatResolution(width: Int, height: Int): String? =
        if (width > 0 && height > 0) "$width×$height" else null
}
