package com.ghadirb.aimusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ghadirb.aimusic.data.local.dao.ListeningHistoryDao
import com.ghadirb.aimusic.data.local.dao.TrackDao
import com.ghadirb.aimusic.data.local.dao.UserPreferenceDao
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity

/**
 * Single Room database for the whole app. Everything here is local-only —
 * no sync, no network backend. See README "Privacy & Security" section.
 */
@Database(
    entities = [TrackEntity::class, ListeningHistoryEntity::class, UserPreferenceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun userPreferenceDao(): UserPreferenceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_music_companion.db"
                ).build().also { INSTANCE = it }
            }
    }
}
