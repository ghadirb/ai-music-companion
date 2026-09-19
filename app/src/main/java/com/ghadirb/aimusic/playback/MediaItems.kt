package com.ghadirb.aimusic.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ghadirb.aimusic.data.local.entity.TrackEntity

private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"

/**
 * Builds the Media3 item for a library track. The metadata is what the system
 * notification, lock screen and Bluetooth/Auto displays read, so it must be set
 * here (the previous version only had a URI and showed an empty notification).
 */
fun TrackEntity.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.takeUnless { it == UNKNOWN_ARTIST })
        .setAlbumTitle(album.takeUnless { it == UNKNOWN_ALBUM })
        .setArtworkUri(albumArtUri?.let { runCatching { Uri.parse(it) }.getOrNull() })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()
    return MediaItem.Builder()
        .setUri(path)
        .setMediaId(id.toString())
        .setMediaMetadata(metadata)
        .build()
}
