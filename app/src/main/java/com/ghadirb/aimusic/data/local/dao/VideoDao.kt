package com.ghadirb.aimusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE videos SET lastPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, positionMs: Long, playedAt: Long)

    @Query("UPDATE videos SET lastPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE videos SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)
}
