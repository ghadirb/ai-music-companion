package com.ghadirb.aimusic

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghadirb.aimusic.backup.PlaylistPlan
import com.ghadirb.aimusic.backup.RestorePlan
import com.ghadirb.aimusic.data.local.AppDatabase
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun createV4WithData() {
        helper.createDatabase(DB, 4).apply {
            execSQL("INSERT INTO tracks (id,path,title,artist,album,durationMs,dateAdded,isFavorite,analyzed) VALUES (1,'content://x/1','T','A','B',1000,5,1,0)")
            execSQL("INSERT INTO listening_history (trackId,startTime,listenDurationMs,completedPercentage,skipped,replayCount) VALUES (1,10,900,1.0,0,0)")
            execSQL("INSERT INTO user_preference (id,favoriteArtists,favoriteGenres,favoriteEnergyLevel,preferredDurationMs,preferredTimeOfDay) VALUES (0,'A','Pop','0.50',1000,'night')")
            execSQL("INSERT INTO playlists (id,name,createdAt) VALUES (1,'P',1)")
            execSQL("INSERT INTO playlist_track_cross_ref (playlistId,trackId,position) VALUES (1,1,0)")
            close()
        }
    }

    @Test fun migrate4To5KeepsDataAndAddsProfileColumns() {
        createV4WithData()
        val db = helper.runMigrationsAndValidate(DB, 5, true, AppDatabase.MIGRATION_4_5)
        db.query("SELECT favoriteArtists, favoriteMoods, preferredBpm, energyRange, skipRate, favoriteRatio, peakHours, updatedAt FROM user_preference").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("A", c.getString(0))
            assertEquals("", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0.0, c.getDouble(4), 0.0)
            assertEquals(0L, c.getLong(7))
        }
        db.query("SELECT COUNT(*) FROM tracks WHERE isFavorite = 1").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM listening_history").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM playlist_track_cross_ref").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test fun openingAnOldDatabaseWithTheRealBuilderPreservesUserData() = runBlocking {
        createV4WithData()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
        try {
            val tracks = db.trackDao().observeAll().first()
            assertEquals(1, tracks.size)
            assertTrue(tracks.single().isFavorite)
            assertNotNull(db.userPreferenceDao().get())
            assertEquals(1, db.playlistDao().getAllPlaylists().size)
        } finally {
            db.close()
            context.deleteDatabase(DB)
        }
    }

    private companion object { const val DB = "migration-test.db" }
}

@RunWith(AndroidJUnit4::class)
class RepositoryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = MusicRepository(db.trackDao(), db.listeningHistoryDao(), db.userPreferenceDao(), db.playlistDao(), context, db)
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(): List<TrackEntity> {
        db.trackDao().insertAll(
            listOf(1, 2, 3).map { TrackEntity(path = "content://t/$it", title = "T$it", artist = "A", album = "B", durationMs = 1000L * it) }
        )
        return repository.allTracksSnapshot()
    }

    @Test fun favoritesToggle() = runBlocking {
        val t = seed().first()
        repository.setFavorite(t.id, true)
        assertTrue(repository.allTracksSnapshot().first { it.id == t.id }.isFavorite)
        repository.setFavorite(t.id, false)
        assertEquals(0, repository.allTracksSnapshot().count { it.isFavorite })
    }

    @Test fun playlistsKeepOrderAndAllowRemoval() = runBlocking {
        val tracks = seed()
        val id = repository.createPlaylist("Mix")
        tracks.forEach { repository.addTrackToPlaylist(id, it.id) }
        assertEquals(tracks.map { it.id }, repository.getTracksInPlaylist(id).map { it.id })
        repository.removeTrackFromPlaylist(id, tracks[1].id)
        assertEquals(listOf(tracks[0].id, tracks[2].id), repository.getTracksInPlaylist(id).map { it.id })
    }

    @Test fun createPlaylistWithTracksMakesNamesUnique() = runBlocking {
        val tracks = seed()
        val a = repository.createPlaylistWithTracks("Smart", tracks.map { it.id })
        val b = repository.createPlaylistWithTracks("smart", tracks.take(1).map { it.id })
        assertTrue(a != b)
        assertEquals(setOf("Smart", "smart (2)"), repository.getAllPlaylists().map { it.name }.toSet())
    }

    @Test fun historyIsStoredAndAggregated() = runBlocking {
        val t = seed().first()
        repository.recordListening(ListeningHistoryEntity(trackId = t.id, startTime = 100, listenDurationMs = 900, completedPercentage = 1f, skipped = false))
        repository.recordListening(ListeningHistoryEntity(trackId = t.id, startTime = 200, listenDurationMs = 100, completedPercentage = 0.1f, skipped = true))
        assertEquals(2, repository.recentHistory().size)
        val stat = repository.observeTrackStats().first().single()
        assertEquals(1, stat.playCount) // skipped plays don't count
        assertEquals(200L, stat.lastPlayedAt)
    }

    @Test fun restorePlanIsAppliedAtomicallyAndMergesPlaylists() = runBlocking {
        val tracks = seed()
        val existing = repository.createPlaylistWithTracks("Study", listOf(tracks[0].id))
        repository.applyRestorePlan(
            RestorePlan(
                favoriteIds = listOf(tracks[1].id),
                playlists = listOf(PlaylistPlan("Study", existing, listOf(tracks[1].id)), PlaylistPlan("New", null, listOf(tracks[2].id))),
                history = listOf(ListeningHistoryEntity(trackId = tracks[1].id, startTime = 5, listenDurationMs = 1, completedPercentage = 1f, skipped = false)),
                settings = emptyMap(), applyTasteProfile = false, unmatchedTracks = 0
            ),
            tasteProfile = null
        )
        assertEquals(listOf(tracks[0].id, tracks[1].id), repository.getTracksInPlaylist(existing).map { it.id })
        assertEquals(2, repository.getAllPlaylists().size)
        assertTrue(repository.allTracksSnapshot().first { it.id == tracks[1].id }.isFavorite)
        assertEquals(1, repository.recentHistory().size)
    }
}
