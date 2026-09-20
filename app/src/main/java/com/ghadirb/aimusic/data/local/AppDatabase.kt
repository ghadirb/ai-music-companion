package com.ghadirb.aimusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.ghadirb.aimusic.data.local.dao.ListeningHistoryDao
import com.ghadirb.aimusic.data.local.dao.PlaylistDao
import com.ghadirb.aimusic.data.local.dao.TrackDao
import com.ghadirb.aimusic.data.local.dao.UserPreferenceDao
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistTrackCrossRef
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity

/**
 * Single Room database for the whole app. Everything here is local-only —
 * no sync, no network backend. See README "Privacy & Security" section.
 *
 * v2 adds PlaylistEntity/PlaylistTrackCrossRef (manual playlists).
 * v3 adds on-device audio-analysis columns to `tracks` (energyLevel, bpm,
 * moodTag, analyzed) — see analysis/AudioAnalyzer.kt. Since this powers the
 * "night"/"driving" Home cards and real user libraries may already exist on
 * devices running v2, this is a real Migration (not destructive) so local
 * history/favorites/playlists are preserved across the upgrade.
 */
@Database(
    entities = [
        TrackEntity::class,
        ListeningHistoryEntity::class,
        UserPreferenceEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_preference ADD COLUMN favoriteMoods TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN preferredBpm INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN energyRange TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN topTrackIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN skipRate REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN favoriteRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN peakHours TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_preference ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN energyLevel REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN bpm INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN moodTag TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN analyzed INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN folderPath TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_music_companion.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // v1 predates any real install (never released), so the only
                    // gap we can't hand-migrate is v1->v2; destructive fallback
                    // only kicks in for that very old case.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build().also { INSTANCE = it }
            }
    }
}
