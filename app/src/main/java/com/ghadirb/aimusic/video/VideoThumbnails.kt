package com.ghadirb.aimusic.video

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Small, bounded thumbnail loader for the video grid/list. Uses the system's own thumbnail
 * (ContentResolver.loadThumbnail on Android 10+, MediaStore thumbnails before that), so it needs no
 * extra dependency and never decodes the video itself.
 *
 *  - lazy: a thumbnail is requested only when its item is composed (visible in the Lazy list/grid);
 *  - asynchronous: always on Dispatchers.IO, never on the main thread;
 *  - bounded: at most [MAX_PARALLEL] loads at once, thumbnails are requested at ~[TARGET_PX] px,
 *    and the in-memory cache is capped in bytes (not item count) so weak devices stay safe.
 */
object VideoThumbnails {

    private const val TARGET_PX = 360
    private const val MAX_PARALLEL = 3
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024

    private val gate = Semaphore(MAX_PARALLEL)

    private val cache = object : LruCache<Long, Bitmap>(
        minOf(MAX_CACHE_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 16).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** Returns a cached bitmap immediately if present (no I/O). */
    fun peek(id: Long): Bitmap? = cache.get(id)

    /** Loads (or returns the cached) thumbnail, or null if the system has none / the file is gone. */
    suspend fun load(context: Context, id: Long, contentUri: String): Bitmap? {
        cache.get(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cache.get(id)?.let { return@withPermit it }
                val bitmap = try {
                    read(context.contentResolver, id, Uri.parse(contentUri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // missing file, revoked permission, unsupported container: just show the placeholder
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    null
                }
                if (bitmap != null) cache.put(id, bitmap)
                bitmap
            }
        }
    }

    /** Drops a deleted video's thumbnail from memory. */
    fun evict(id: Long) {
        cache.remove(id)
    }

    @Suppress("DEPRECATION")
    private fun read(resolver: ContentResolver, id: Long, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(TARGET_PX, TARGET_PX), null)
        } else {
            MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        }
}
