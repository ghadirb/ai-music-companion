package com.ghadirb.aimusic.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.mix.SmartMixGenerator
import com.ghadirb.aimusic.radio.RadioEngine
import kotlinx.coroutines.flow.first

/**
 * Android Auto / Automotive browse tree (spec item 9). Deliberately minimal and stateless:
 * every node is (re)built from the repository on demand, so it can never drift from what the
 * phone UI shows, and there is no separate cache to keep in sync. Track leaf nodes reuse
 * [toMediaItem] as-is — same mediaId scheme (the numeric track id) the rest of the app already
 * uses — so they carry a real content URI and are playable the moment Auto receives them.
 *
 * Kept intentionally shallow (flat lists, no pagination, no live "continuous radio" node) per the
 * spec's own guidance to ship a minimal, stable Auto implementation first rather than one that
 * needs bigger architecture changes.
 */
object BrowseTree {
    const val ROOT_ID = "auto_root"
    const val FAVORITES_ID = "auto_favorites"
    const val RECENT_ID = "auto_recent"
    const val PLAYLISTS_ROOT_ID = "auto_playlists"
    const val MIXES_ROOT_ID = "auto_mixes"
    const val RADIO_ID = "auto_radio"
    private const val PLAYLIST_PREFIX = "auto_playlist_"
    private const val MIX_PREFIX = "auto_mix_"
    private const val MAX_FLAT_LIST = 50
    private const val RADIO_BATCH = 25

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    fun root(): MediaItem = folder(ROOT_ID, "نواسا")

    /**
     * Children of [parentId], or null if [parentId] isn't one of ours (unknown browse node).
     * [entitled] hides the Premium-only Smart Radio node for Free users — everything else here
     * (Favorites, Recently played, Playlists, Basic Smart Mix) is free per spec item 10.
     */
    suspend fun children(repository: MusicRepository, parentId: String, entitled: Boolean): List<MediaItem>? = when {
        parentId == ROOT_ID -> buildList {
            add(folder(FAVORITES_ID, "علاقه‌مندی‌ها"))
            add(folder(RECENT_ID, "اخیراً پخش‌شده"))
            add(folder(PLAYLISTS_ROOT_ID, "پلی‌لیست‌ها"))
            add(folder(MIXES_ROOT_ID, "میکس‌های هوشمند"))
            if (entitled) add(folder(RADIO_ID, "رادیوی هوشمند"))
        }
        parentId == FAVORITES_ID ->
            repository.observeFavorites().first().take(MAX_FLAT_LIST).map { it.toMediaItem() }
        parentId == RECENT_ID -> {
            val recentTrackIds = repository.recentHistory(MAX_FLAT_LIST).map { it.trackId }.distinct()
            recentTrackIds.mapNotNull { repository.getTrack(it) }.map { it.toMediaItem() }
        }
        parentId == PLAYLISTS_ROOT_ID ->
            repository.getAllPlaylists().map { folder(PLAYLIST_PREFIX + it.id, it.name) }
        parentId.startsWith(PLAYLIST_PREFIX) -> {
            val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            if (playlistId == null) emptyList() else repository.getTracksInPlaylist(playlistId).map { it.toMediaItem() }
        }
        parentId == MIXES_ROOT_ID ->
            smartMixes(repository).map { folder(MIX_PREFIX + it.type.name, "${it.type.emoji} ${it.type.titleFa}") }
        parentId.startsWith(MIX_PREFIX) -> {
            val typeName = parentId.removePrefix(MIX_PREFIX)
            smartMixes(repository).firstOrNull { it.type.name == typeName }
                ?.trackList?.take(MAX_FLAT_LIST)?.map { it.toMediaItem() } ?: emptyList()
        }
        parentId == RADIO_ID && entitled -> radioBatch(repository).map { it.toMediaItem() }
        else -> null
    }

    /** Folder metadata for a single browse id, or the track itself for a numeric (track) id. Used for onGetItem. */
    suspend fun item(repository: MusicRepository, mediaId: String, entitled: Boolean): MediaItem? {
        mediaId.toLongOrNull()?.let { trackId -> return repository.getTrack(trackId)?.toMediaItem() }
        return when {
            mediaId == ROOT_ID -> root()
            mediaId == FAVORITES_ID -> folder(FAVORITES_ID, "علاقه‌مندی‌ها")
            mediaId == RECENT_ID -> folder(RECENT_ID, "اخیراً پخش‌شده")
            mediaId == PLAYLISTS_ROOT_ID -> folder(PLAYLISTS_ROOT_ID, "پلی‌لیست‌ها")
            mediaId == MIXES_ROOT_ID -> folder(MIXES_ROOT_ID, "میکس‌های هوشمند")
            mediaId == RADIO_ID && entitled -> folder(RADIO_ID, "رادیوی هوشمند")
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: return null
                repository.getPlaylist(playlistId)?.let { folder(mediaId, it.name) }
            }
            mediaId.startsWith(MIX_PREFIX) -> {
                val typeName = mediaId.removePrefix(MIX_PREFIX)
                smartMixes(repository).firstOrNull { it.type.name == typeName }
                    ?.let { folder(mediaId, "${it.type.emoji} ${it.type.titleFa}") }
            }
            else -> null
        }
    }

    /**
     * Full sibling list a leaf [mediaId] belongs to (Favorites or a playlist), used to expand a
     * single Auto tap into a real, continuable queue. Null when [mediaId] isn't a track id or
     * isn't found in either — the caller then falls back to playing just that one track.
     */
    suspend fun siblingsOf(repository: MusicRepository, mediaId: String): List<MediaItem>? {
        val trackId = mediaId.toLongOrNull() ?: return null
        val favorites = repository.observeFavorites().first()
        if (favorites.any { it.id == trackId }) return favorites.map { it.toMediaItem() }
        for (playlist in repository.getAllPlaylists()) {
            val tracks = repository.getTracksInPlaylist(playlist.id)
            if (tracks.any { it.id == trackId }) return tracks.map { it.toMediaItem() }
        }
        return null
    }

    private suspend fun smartMixes(repository: MusicRepository) =
        SmartMixGenerator.generateAll(
            tracks = repository.observeTracks().first(),
            history = repository.recentHistory(500),
            nowMs = System.currentTimeMillis()
        )

    private suspend fun radioBatch(repository: MusicRepository): List<TrackEntity> {
        val library = repository.observeTracks().first()
        val favorites = repository.observeFavorites().first()
        val seeds = favorites.ifEmpty { library.take(5) }
        if (seeds.isEmpty() || library.isEmpty()) return emptyList()
        val history = repository.recentHistory(500)
        return RadioEngine.nextBatch(
            seeds = seeds,
            alreadyQueued = emptyList(),
            library = library,
            history = history,
            nowMs = System.currentTimeMillis(),
            count = RADIO_BATCH
        ).map { it.track }
    }
}
