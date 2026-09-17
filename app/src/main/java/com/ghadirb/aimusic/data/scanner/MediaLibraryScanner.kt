package com.ghadirb.aimusic.data.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"

        val cursor = context.contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val genreColIndex = c.getColumnIndex(MediaStore.Audio.Media.GENRE)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val albumId = c.getLong(albumIdCol)
                val albumArtUri = albumArtUriFor(albumId)

                tracks.add(
                    TrackEntity(
                        path = contentUri.toString(),
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol) ?: "Unknown Artist",
                        album = c.getString(albumCol) ?: "Unknown Album",
                        genre = if (genreColIndex >= 0) c.getString(genreColIndex) else null,
                        durationMs = c.getLong(durationCol),
                        albumArtUri = albumArtUri
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
}
