package com.ghadirb.aimusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntity
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VideoPlaylistDao {

    @Query("SELECT * FROM video_playlists ORDER BY updatedAt DESC")
    abstract fun observePlaylists(): Flow<List<VideoPlaylistEntity>>

    @Query("SELECT * FROM video_playlist_entries")
    abstract fun observeEntries(): Flow<List<VideoPlaylistEntryEntity>>

    @Insert
    abstract suspend fun insertPlaylist(playlist: VideoPlaylistEntity): Long

    @Query("UPDATE video_playlists SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE video_playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM video_playlists WHERE id = :id")
    protected abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM video_playlist_entries WHERE playlistId = :id")
    protected abstract suspend fun deleteEntriesOf(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEntry(entry: VideoPlaylistEntryEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) FROM video_playlist_entries WHERE playlistId = :id")
    protected abstract suspend fun maxPosition(id: Long): Int

    @Query("DELETE FROM video_playlist_entries WHERE playlistId = :id AND videoId IN (:videoIds)")
    protected abstract suspend fun deleteEntries(id: Long, videoIds: List<Long>)

    @Query("UPDATE video_playlist_entries SET position = :position WHERE playlistId = :id AND videoId = :videoId")
    protected abstract suspend fun setPosition(id: Long, videoId: Long, position: Int)

    /** Removes only the playlist and its entries - never any video file or video row. */
    @Transaction
    open suspend fun deletePlaylistWithEntries(id: Long) {
        deleteEntriesOf(id)
        deletePlaylistRow(id)
    }

    /** Appends [videoIds] in the given order; videos already in the playlist are skipped. Returns how many were added. */
    @Transaction
    open suspend fun addVideos(playlistId: Long, videoIds: List<Long>, now: Long): Int {
        var position = maxPosition(playlistId) + 1
        var added = 0
        for (videoId in videoIds) {
            if (insertEntry(VideoPlaylistEntryEntity(playlistId, videoId, position, now)) != -1L) {
                position++
                added++
            }
        }
        if (added > 0) touch(playlistId, now)
        return added
    }

    @Transaction
    open suspend fun removeVideos(playlistId: Long, videoIds: List<Long>, now: Long) {
        videoIds.chunked(500).forEach { deleteEntries(playlistId, it) }
        touch(playlistId, now)
    }

    /** Stores the manual order: [orderedVideoIds] get positions 0..n-1. */
    @Transaction
    open suspend fun reorder(playlistId: Long, orderedVideoIds: List<Long>, now: Long) {
        orderedVideoIds.forEachIndexed { index, videoId -> setPosition(playlistId, videoId, index) }
        touch(playlistId, now)
    }
}
