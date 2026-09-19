package com.ghadirb.aimusic.data.local.dao

import androidx.room.*
import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistTrackCrossRef
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Query(
        "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_track_cross_ref " +
        "WHERE playlistId = :playlistId"
    )
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    @Query(
        "DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackId = :trackId"
    )
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query(
        "SELECT tracks.* FROM tracks " +
        "INNER JOIN playlist_track_cross_ref ON tracks.id = playlist_track_cross_ref.trackId " +
        "WHERE playlist_track_cross_ref.playlistId = :playlistId " +
        "ORDER BY playlist_track_cross_ref.position ASC"
    )
    fun observeTracksInPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        "SELECT COUNT(*) FROM playlist_track_cross_ref WHERE playlistId = :playlistId"
    )
    fun observeTrackCount(playlistId: Long): Flow<Int>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: Long): PlaylistEntity?
}
