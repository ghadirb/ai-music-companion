package com.ghadirb.aimusic.data.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device's MediaStore for audio files. Deliberately reads only
 * MediaStore.Audio metadata — no file content ever leaves the device, and
 * nothing here touches the network. Supported containers (MP3/FLAC/WAV/M4A/OGG)
 * are whatever MediaStore already indexed; we don't re-probe file headers in the MVP.
 */
class MediaLibraryScanner(private val context: Context) {

    suspend fun scan(): List<TrackEntity> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrackEntity>()

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val baseProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        // MediaStore.Audio.Media.GENRE only exists as a queryable column on API 30+, and even
        // then some OEM providers reject it — the query itself throws IllegalArgumentException
        // ("Invalid column genre") rather than just omitting the column, which used to crash the
        // whole scan. So: try WITH genre first (nice to have for future genre-based filtering),
        // and transparently fall back to the base projection (no genre) if the provider rejects it.
        var includesGenre = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        var cursor: Cursor? = null
        if (includesGenre) {
            cursor = try {
                context.contentResolver.query(
                    collection,
                    baseProjection + MediaStore.Audio.Media.GENRE,
                    selection, null, sortOrder
                )
            } catch (e: IllegalArgumentException) {
                Log.w("MediaLibraryScanner", "GENRE column not supported by this device's provider, scanning without it")
                includesGenre = false
                null
            }
        }
        if (cursor == null) {
            cursor = context.contentResolver.query(collection, baseProjection, selection, null, sortOrder)
        }

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val displayNameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val genreColIndex = if (includesGenre) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val albumId = c.getLong(albumIdCol)
                val albumArtUri = albumArtUriFor(albumId)

                val fileName = if (displayNameCol >= 0) c.getString(displayNameCol) else null
                val title = cleanMetadata(c.getString(titleCol)).ifBlank {
                    cleanMetadata(fileName?.substringBeforeLast('.'))
                }.ifBlank { "Unknown title" }
                tracks.add(
                    TrackEntity(
                        path = contentUri.toString(),
                        title = title,
                        artist = cleanMetadata(c.getString(artistCol)).ifBlank { "Unknown artist" },
                        album = cleanMetadata(c.getString(albumCol)).ifBlank { "Unknown album" },
                        genre = if (genreColIndex >= 0) c.getString(genreColIndex) else null,
                        durationMs = c.getLong(durationCol),
                        albumArtUri = albumArtUri,
                        folderPath = if (dataCol >= 0) c.getString(dataCol)?.substringBeforeLast('/', "") else null
                    )
                )
            }
        }

        tracks
    }

    /**
     * MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI/{albumId}/albumart is deprecated
     * since API 29 but still works on most OEM ROMs; on newer devices Coil will
     * simply fail to load it and the UI shows a placeholder — no crash.
     */
    private fun albumArtUriFor(albumId: Long): String =
        Uri.parse("content://media/external/audio/albumart/$albumId").toString()

    /** Removes download-site watermarks commonly embedded in Persian music tags. */
    private fun cleanMetadata(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace(Regex("(?i)https?://[^\\s)]+"), "")
            .replace(Regex("(?i)(?:www\\.)?[a-z0-9_-]+\\.(?:com|ir|net|org)(?:/[^\\s)]*)?"), "")
            .replace(Regex("(?i)\\b(?:download|music|song)\\b\\s*(?:by|from)?\\s*"), "")
            .replace(Regex("[()\\[\\]{}]"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', '-', '_', '.', '•')
    }
}
