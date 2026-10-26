package com.ghadirb.aimusic.data.repository

import com.ghadirb.aimusic.data.local.dao.VideoPlaylistDao
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntryEntity
import com.ghadirb.aimusic.video.VideoPlaylistLogic
import kotlinx.coroutines.flow.Flow

/** Video playlists. No relation to music playlists; nothing here ever deletes a video file. */
class VideoPlaylistRepository(private val dao: VideoPlaylistDao) {

    fun observePlaylists(): Flow<List<VideoPlaylistEntity>> = dao.observePlaylists()
    fun observeEntries(): Flow<List<VideoPlaylistEntryEntity>> = dao.observeEntries()

    /** Returns the new id, or -1 when the name is empty. */
    suspend fun create(rawName: String, now: Long = System.currentTimeMillis()): Long {
        val name = VideoPlaylistLogic.cleanName(rawName)
        if (name.isEmpty()) return -1L
        return dao.insertPlaylist(VideoPlaylistEntity(name = name, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, rawName: String, now: Long = System.currentTimeMillis()): Boolean {
        val name = VideoPlaylistLogic.cleanName(rawName)
        if (name.isEmpty()) return false
        dao.rename(id, name, now)
        return true
    }

    /** Deletes the playlist only. The videos stay on the phone and in the library. */
    suspend fun delete(id: Long) = dao.deletePlaylistWithEntries(id)

    suspend fun add(playlistId: Long, videoIds: List<Long>, now: Long = System.currentTimeMillis()): Int =
        dao.addVideos(playlistId, videoIds.distinct(), now)

    suspend fun remove(playlistId: Long, videoIds: List<Long>, now: Long = System.currentTimeMillis()) =
        dao.removeVideos(playlistId, videoIds, now)

    suspend fun reorder(playlistId: Long, orderedVideoIds: List<Long>, now: Long = System.currentTimeMillis()) =
        dao.reorder(playlistId, orderedVideoIds, now)
}
