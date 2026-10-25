package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.data.local.entity.VideoEntity
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** "Play from link": validation (pure, JVM unit tested) and the in-memory entries the player loads by id. */
object StreamUrl {

    sealed interface Result {
        data class Ok(val url: String, val title: String) : Result
        data class Bad(val reason: String) : Result
    }

    /** Only https: plain http is blocked by Android's default network security policy (and is unsafe). */
    fun parse(input: String): Result {
        val text = input.trim()
        if (text.isEmpty()) return Result.Bad("لینک را وارد کنید.")
        if (text.any { it.isWhitespace() }) return Result.Bad("لینک نباید فاصله داشته باشد.")
        val uri = runCatching { URI(text) }.getOrNull() ?: return Result.Bad("لینک معتبر نیست.")
        return when (uri.scheme?.lowercase()) {
            "https" -> if (uri.host.isNullOrBlank()) Result.Bad("لینک معتبر نیست.") else Result.Ok(text, titleOf(uri))
            "http" -> Result.Bad("فقط لینک‌های امن (https) پشتیبانی می‌شود.")
            else -> Result.Bad("لینک باید با https:// شروع شود.")
        }
    }

    private fun titleOf(uri: URI): String {
        val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return decoded.ifBlank { uri.host.orEmpty() }
    }

    /** Stable negative id: never collides with MediaStore ids (always positive). */
    fun idFor(url: String): Long = -((url.hashCode().toLong() and 0x7fffffffL) + 1L)

    private val entries = ConcurrentHashMap<Long, VideoEntity>()

    /** Makes the link playable by the normal player (it loads videos by id). Not stored in the library database. */
    fun register(url: String, title: String, nowSec: Long = System.currentTimeMillis() / 1000): VideoEntity {
        val v = VideoEntity(
            id = idFor(url), contentUri = url, displayName = title, durationMs = 0L, sizeBytes = 0L,
            width = 0, height = 0, mimeType = "", folderName = "لینک", relativePath = "",
            dateAdded = nowSec, dateModified = nowSec
        )
        entries[v.id] = v
        return v
    }

    fun find(ids: List<Long>): List<VideoEntity> = ids.mapNotNull { if (it < 0) entries[it] else null }

    fun isStream(video: VideoEntity): Boolean = video.id < 0
}
