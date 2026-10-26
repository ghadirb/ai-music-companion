package com.ghadirb.aimusic.music

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Puts downloaded songs where the phone's own Music library lives (Music/Navasa), through MediaStore.
 *
 * Why: a file left in the app's private folder (Android/data/...) is invisible to the file manager and often
 * never gets indexed, so "downloaded" songs did not show up in the Library and could not be deleted cleanly.
 * A MediaStore entry is indexed at once, shows in every music app / file manager and is deletable normally.
 */
object PublicMusicStore {
    const val FOLDER = "Navasa"

    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr")

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in audioExt

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        else -> "audio/mpeg"
    }

    /** "Music/Navasa" or "Music/Navasa/<sub>" for files that sit in a sub-folder of [root] (an extracted collection). */
    fun relativePath(file: File, root: File): String {
        val sub = file.parentFile?.takeIf { it.absolutePath != root.absolutePath }?.name
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.takeIf { it.isNotEmpty() }
        return if (sub == null) "Music/$FOLDER" else "Music/$FOLDER/$sub"
    }

    /**
     * Copies [file] into the public Music folder and removes the private copy. Returns the new content:// uri
     * (Android 10+) or file uri (older), or null when it could not be published - the caller then keeps the file
     * where it is. Blocking: call from a background thread.
     */
    fun publish(context: Context, file: File, root: File, title: String? = null, artist: String? = null): Uri? {
        if (!file.isFile) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishQ(context, file, root, title, artist)
            else publishLegacy(context, file, root)
        } catch (_: Exception) {
            null
        }
    }

    private fun publishQ(context: Context, file: File, root: File, title: String?, artist: String?): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(file, root))
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!title.isNullOrBlank()) put(MediaStore.Audio.Media.TITLE, title)
            if (!artist.isNullOrBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: error("no output stream")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        removePrivateCopy(file, root)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, file: File, root: File): Uri? {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val rel = relativePath(file, root).removePrefix("Music/")
        val dir = File(music, rel)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var dest = File(dir, file.name)
        var n = 2
        while (dest.exists()) { dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}"); n++ }
        file.copyTo(dest)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        removePrivateCopy(file, root)
        return Uri.fromFile(dest)
    }

    private fun removePrivateCopy(file: File, root: File) {
        runCatching { file.delete() }
        // an extracted collection's now-empty folder goes too (delete() only succeeds on an empty one)
        file.parentFile?.takeIf { it.absolutePath != root.absolutePath }?.let { runCatching { it.delete() } }
    }

    /** Audio files still sitting in the private download folder (from earlier versions), including collection folders. */
    fun legacyFiles(root: File): List<File> =
        root.walkTopDown().maxDepth(2).filter { it.isFile && isAudio(it.name) }.toList()
}
