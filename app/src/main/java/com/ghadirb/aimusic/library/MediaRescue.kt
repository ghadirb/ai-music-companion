package com.ghadirb.aimusic.library

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Finds songs/videos that are on the phone but missing from Android's media database (MediaStore) and asks the
 * system to index them again. The app lists media from MediaStore, so such files play from a file manager but are
 * invisible here - even after reinstalling. This happens e.g. when a delete removed the database row but not the file.
 *
 * Needs file access by path: works on Android 10 and below (legacy storage); from Android 11 the system hides
 * non-indexed files from apps, so nothing is found there (harmless).
 */
object MediaRescue {
    private val EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mka",
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg"
    )
    private const val MAX_DEPTH = 8
    private const val MAX_FILES = 5000

    data class Result(val readable: Boolean, val found: Int)

    /** Walks shared storage, indexes every media file MediaStore does not know yet. */
    suspend fun scanAll(context: Context): Result = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        if (root?.listFiles() == null) return@withContext Result(readable = false, found = 0)
        val indexed = indexedPaths(context)
        val missing = ArrayList<String>()
        walk(root, 0, indexed, missing)
        if (missing.isNotEmpty()) scanPaths(context, missing)
        Result(readable = true, found = missing.size)
    }

    /** Asks the system to (re)index exactly these files and waits until it is done. */
    suspend fun scanPaths(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val left = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun indexedPaths(context: Context): Set<String> {
        val set = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                ),
                null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { set.add(it) } }
        }
        return set
    }

    private fun walk(dir: File, depth: Int, indexed: Set<String>, out: MutableList<String>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = dir.listFiles() ?: return
        // A .nomedia file means "do not index this folder" (WhatsApp stickers, app caches...) - respect it.
        if (children.any { it.name == ".nomedia" }) return
        for (f in children) {
            if (out.size >= MAX_FILES) return
            val name = f.name
            if (f.isDirectory) {
                if (name.startsWith(".") || name == "Android") continue
                walk(f, depth + 1, indexed, out)
            } else if (f.length() > 0 && name.substringAfterLast('.', "").lowercase() in EXTENSIONS && f.absolutePath !in indexed) {
                out.add(f.absolutePath)
            }
        }
    }
}
