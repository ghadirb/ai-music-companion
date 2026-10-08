package com.ghadirb.aimusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ghadirb.aimusic.data.local.entity.BehaviorEventEntity
import com.ghadirb.aimusic.data.local.entity.RecommendationCacheEntity
import com.ghadirb.aimusic.data.local.entity.RecommendationEventEntity

@Dao
interface BehaviorEventDao {

    @Insert
    suspend fun insert(event: BehaviorEventEntity): Long

    @Query("SELECT * FROM behavior_event WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int = 500): List<BehaviorEventEntity>

    @Query("DELETE FROM behavior_event WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface RecommendationEventDao {

    @Insert
    suspend fun insertAll(events: List<RecommendationEventEntity>)

    /** Newest suggestion of [trackId] shown in [from]..[to] that has not been resolved yet. */
    @Query(
        "SELECT * FROM recommendation_event WHERE trackId = :trackId AND played = 0 " +
            "AND timestamp BETWEEN :from AND :to ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun latestOpen(trackId: Long, from: Long, to: Long): RecommendationEventEntity?

    @Query("UPDATE recommendation_event SET played = 1, completed = :completed, skipped = :skipped WHERE id = :id")
    suspend fun markOutcome(id: Long, completed: Boolean, skipped: Boolean)

    @Query("SELECT * FROM recommendation_event WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<RecommendationEventEntity>

    @Query("DELETE FROM recommendation_event WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface RecommendationCacheDao {

    @Query("SELECT * FROM recommendation_cache")
    suspend fun all(): List<RecommendationCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<RecommendationCacheEntity>)

    /** Drops every cache row written by a different algorithm version (rows are stored as "<version>:<config hash>"). */
    @Query("DELETE FROM recommendation_cache WHERE algorithmVersion NOT LIKE :versionPrefix || '%'")
    suspend fun deleteOtherVersions(versionPrefix: String): Int

    @Query("DELETE FROM recommendation_cache")
    suspend fun clear()
}
