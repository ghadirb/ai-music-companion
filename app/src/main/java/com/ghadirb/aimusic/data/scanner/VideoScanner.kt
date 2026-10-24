package com.ghadirb.aimusic.data.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as F
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.video.VideoFileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Finds the videos stored on the device through MediaStore.Video.Media. Local only: nothing here
 * touches the network and no file is opened — only the MediaStore index is read.
 *
 * Independent of [MediaLibraryScanner] (music). The returned entities carry only what MediaStore
 * knows; user state (resume position, last played, favourite) is merged in by the repository.
 */
object VideoScanner {

    /**
     * Returns every playable video MediaStore currently reports, or null when the query could not
     * be run at all (no permission / provider error). Null is deliberately different from an empty
     * list so a revoked permission can never be mistaken for "the user deleted all videos".
     * Runs on [Dispatchers.IO] and checks for cancellation while iterating large libraries.
     */
    suspend fun scan(context: Context): List<VideoEntity>? = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.MIME_TYPE)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            if (hasRelativePath) add(MediaStore.Video.Media.RELATIVE_PATH)
        }.toTypedArray()

        // Files still being written by another app (IS_PENDING = 1, Android 10+) are not playable yet.
        // Short clips are intentionally NOT filtered out: a video is whatever MediaStore says it is.
        val selection = if (hasRelativePath) {
            "${MediaStore.Video.Media.SIZE} > 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else {
            "${MediaStore.Video.Media.SIZE} > 0"
        }

        try {
            val cursor = context.contentResolver.query(
                collection, projection, selection, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
            ) ?: return@withContext null

            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val pathCol = if (hasRelativePath) c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH) else -1

                val result = ArrayList<VideoEntity>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    if (result.size % 200 == 0) coroutineContext.ensureActive()
                    val id = c.getLong(idCol)
                    result += VideoEntity(
                        id = id,
                        contentUri = ContentUris.withAppendedId(collection, id).toString(),
                        displayName = c.getString(nameCol).orEmpty().ifBlank { "video_$id" },
                        durationMs = c.getLong(durationCol).coerceAtLeast(0L),
                        sizeBytes = c.getLong(sizeCol).coerceAtLeast(0L),
                        width = c.getInt(widthCol).coerceAtLeast(0),
                        height = c.getInt(heightCol).coerceAtLeast(0),
                        mimeType = c.getString(mimeCol).orEmpty(),
                        folderName = c.getString(bucketCol).orEmpty(),
                        relativePath = if (pathCol >= 0) c.getString(pathCol).orEmpty() else "",
                        dateAdded = c.getLong(addedCol),
                        dateModified = c.getLong(modifiedCol)
                    )
                }
                // Videos Android did not classify as video (often .mkv/.ts/.avi copied from other apps) are
                // only visible through the generic Files table: add them, without duplicating known ids.
                val known = result.mapTo(HashSet()) { it.id }
                result += scanUnclassified(context, collection, known)
                result
            }
        } catch (e: SecurityException) {
            // Video permission missing or revoked while the app was running.
            null
        } catch (e: IllegalArgumentException) {
            // A vendor MediaStore without one of the optional columns: treat as "could not scan".
            null
        }
    }

    /**
     * Files MediaStore holds with media type NONE but whose name/mime says "video". Best effort: failures
     * here never break the normal scan (returns an empty list). MediaStore ids are shared across the Files,
     * Video and Audio tables, so the id also works with the Video content uri.
     */
    private fun scanUnclassified(context: Context, videoCollection: android.net.Uri, known: Set<Long>): List<VideoEntity> {
        val out = ArrayList<VideoEntity>()
        try {
            val files = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(F._ID, F.DISPLAY_NAME, F.SIZE, F.MIME_TYPE, F.DATE_ADDED, F.DATE_MODIFIED)
            val cursor = context.contentResolver.query(
                files, projection,
                "${F.MEDIA_TYPE} = ${F.MEDIA_TYPE_NONE} AND ${F.SIZE} > 0", null, "${F.DATE_ADDED} DESC"
            ) ?: return out
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(F._ID)
                val nameCol = c.getColumnIndexOrThrow(F.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(F.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(F.MIME_TYPE)
                val addedCol = c.getColumnIndexOrThrow(F.DATE_ADDED)
                val modifiedCol = c.getColumnIndexOrThrow(F.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    if (id in known) continue
                    val name = c.getString(nameCol).orEmpty()
                    val mime = c.getString(mimeCol)
                    if (!VideoFileTypes.isUnclassifiedVideo(name, mime)) continue
                    out += VideoEntity(
                        id = id,
                        contentUri = ContentUris.withAppendedId(videoCollection, id).toString(),
                        displayName = name.ifBlank { "video_$id" },
                        durationMs = 0L, // unknown until played; the player probes the real file
                        sizeBytes = c.getLong(sizeCol).coerceAtLeast(0L),
                        width = 0, height = 0,
                        mimeType = mime.orEmpty(),
                        folderName = "", relativePath = "",
                        dateAdded = c.getLong(addedCol),
                        dateModified = c.getLong(modifiedCol)
                    )
                }
            }
        } catch (e: Exception) {
            // Optional extra source: ignore (SecurityException, vendor provider quirks...).
        }
        return out
    }
}
