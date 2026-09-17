package com.ghadirb.aimusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferenceDao {

    @Query("SELECT * FROM user_preference WHERE id = 0 LIMIT 1")
    fun observe(): Flow<UserPreferenceEntity?>

    @Query("SELECT * FROM user_preference WHERE id = 0 LIMIT 1")
    suspend fun get(): UserPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: UserPreferenceEntity)
}
