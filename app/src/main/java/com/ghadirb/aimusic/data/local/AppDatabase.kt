package com.ghadirb.aimusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.ghadirb.aimusic.data.local.dao.BehaviorEventDao
import com.ghadirb.aimusic.data.local.dao.ListeningHistoryDao
import com.ghadirb.aimusic.data.local.dao.PlaylistDao
import com.ghadirb.aimusic.data.local.dao.RecommendationCacheDao
import com.ghadirb.aimusic.data.local.dao.RecommendationEventDao
import com.ghadirb.aimusic.data.local.dao.TrackDao
import com.ghadirb.aimusic.data.local.dao.UserPreferenceDao
import com.ghadirb.aimusic.data.local.dao.VideoDao
import com.ghadirb.aimusic.data.local.entity.BehaviorEventEntity
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistTrackCrossRef
import com.ghadirb.aimusic.data.local.entity.RecommendationCacheEntity
import com.ghadirb.aimusic.data.local.entity.RecommendationEventEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.local.entity.VideoEntity

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
 * v6 adds `tracks.notInterested` (explicit negative feedback, v1.1).
 * v7 adds the on-device recommendation engine storage: `behavior_event`, `recommendation_event`,
 * `recommendation_cache` and `user_preference.profileJson`. Purely additive (no data is touched).
 * v8 adds the independent `videos` table for the local "ویدئو" tab (resume position, last played).
 * Purely additive: music, playlists and favourites are not touched.
 */
@Database(
    entities = [
        TrackEntity::class,
        ListeningHistoryEntity::class,
        UserPreferenceEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        BehaviorEventEntity::class,
        RecommendationEventEntity::class,
        RecommendationCacheEntity::class,
        VideoEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun behaviorEventDao(): BehaviorEventDao
    abstract fun recommendationEventDao(): RecommendationEventDao
    abstract fun recommendationCacheDao(): RecommendationCacheDao
    abstract fun videoDao(): VideoDao

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN notInterested INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 -> v7: recommendation engine v2 storage. Only creates new tables/indices and adds one
         * column with a default, so existing history, favourites and playlists are untouched.
         * The SQL must match what Room generates for the entities exactly (validated on open).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `behavior_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, `behavior` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_event_trackId` ON `behavior_event` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_event_timestamp` ON `behavior_event` (`timestamp`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recommendation_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, `section` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `score` REAL NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`algorithmVersion` TEXT NOT NULL, `discovery` INTEGER NOT NULL, `shown` INTEGER NOT NULL, " +
                        "`played` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `skipped` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_event_trackId` ON `recommendation_event` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_event_timestamp` ON `recommendation_event` (`timestamp`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recommendation_cache` (`section` TEXT NOT NULL, `trackIds` TEXT NOT NULL, " +
                        "`itemsJson` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL, `algorithmVersion` TEXT NOT NULL, " +
                        "PRIMARY KEY(`section`))"
                )

                db.execSQL("ALTER TABLE user_preference ADD COLUMN profileJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v7 -> v8: the local video library. Only creates a new table + indices; nothing that
         * exists today (tracks, history, playlists, favourites) is read or modified.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `videos` (`id` INTEGER NOT NULL, `contentUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, " +
                        "`folderName` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL, " +
                        "`dateModified` INTEGER NOT NULL, `lastPositionMs` INTEGER NOT NULL, " +
                        "`lastPlayedAt` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_lastPlayedAt` ON `videos` (`lastPlayedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_dateAdded` ON `videos` (`dateAdded`)")
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
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    // v1 predates any real install (never released), so the only
                    // gap we can't hand-migrate is v1->v2; destructive fallback
                    // only kicks in for that very old case.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build().also { INSTANCE = it }
            }
    }
}
