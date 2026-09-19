package com.ghadirb.aimusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningHistoryDao {

    @Insert
    suspend fun insert(entry: ListeningHistoryEntity): Long

    @Update
    suspend fun update(entry: ListeningHistoryEntity)

    @Query("SELECT * FROM listening_history WHERE trackId = :trackId ORDER BY startTime DESC")
    fun observeForTrack(trackId: Long): Flow<List<ListeningHistoryEntity>>

    @Query("SELECT * FROM listening_history ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<ListeningHistoryEntity>

    @Query(
        "SELECT COUNT(*) FROM listening_history " +
        "WHERE trackId = :trackId AND completedPercentage >= 0.8"
    )
    suspend fun completedPlayCount(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM listening_history WHERE trackId = :trackId AND skipped = 1")
    suspend fun skipCount(trackId: Long): Int

    @Query(
        "SELECT trackId, COUNT(*) as playCount FROM listening_history " +
        "GROUP BY trackId ORDER BY playCount DESC LIMIT :limit"
    )
    suspend fun mostPlayedTrackIds(limit: Int): List<TrackPlayCount>
}

data class TrackPlayCount(val trackId: Long, val playCount: Int)
