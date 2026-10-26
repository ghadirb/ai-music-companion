package com.ghadirb.aimusic.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ghadirb.aimusic.music.OnlineTrack
import com.ghadirb.aimusic.music.OnlineVersion

/** Prefix of the media id of every streamed song; library rows use plain numbers, so the two never collide. */
const val ONLINE_MEDIA_PREFIX = "online:"

fun onlineMediaId(trackId: String): String = ONLINE_MEDIA_PREFIX + trackId

/** The track id behind a streamed item's media id, or null for a library item. */
fun onlineTrackIdOf(mediaId: String?): String? =
    mediaId?.takeIf { it.startsWith(ONLINE_MEDIA_PREFIX) }?.removePrefix(ONLINE_MEDIA_PREFIX)

/**
 * Media3 item that streams [version] of this song directly from the source site (no proxy through the Worker).
 * The metadata feeds the notification / lock screen exactly like a library item.
 */
fun OnlineTrack.toMediaItem(version: OnlineVersion): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(version.source)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()
    return MediaItem.Builder()
        .setUri(Uri.parse(version.url))
        .setMediaId(onlineMediaId(id))
        .setMediaMetadata(metadata)
        .build()
}
