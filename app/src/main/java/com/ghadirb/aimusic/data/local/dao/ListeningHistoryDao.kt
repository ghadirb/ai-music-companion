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


    @Query("SELECT * FROM listening_history ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<ListeningHistoryEntity>



    /** Per-track aggregate (non-skipped plays + last start time) for library sorting. */
    @Query(
        "SELECT trackId, SUM(CASE WHEN skipped = 0 THEN 1 ELSE 0 END) AS playCount, " +
        "MAX(startTime) AS lastPlayedAt FROM listening_history GROUP BY trackId"
    )
    fun observeTrackStats(): Flow<List<TrackStatRow>>

}

data class TrackStatRow(val trackId: Long, val playCount: Int, val lastPlayedAt: Long)
