package com.ghadirb.aimusic.backup

import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private fun ref(id: Long, path: String = "content://old/$id") = TrackRef(path, "T$id", "A$id", "Al$id", 200_000)

    private val sample = BackupData(
        createdAt = 5L, appVersion = "1.0",
        favorites = listOf(ref(1), ref(2)),
        playlists = listOf(BackupPlaylist("مطالعه", listOf(ref(1), ref(3)))),
        history = listOf(BackupHistoryEntry(ref(1), 1000L, 90_000, 0.95f, false, 1)),
        tasteProfile = UserPreferenceEntity(favoriteArtists = "A1", preferredBpm = 100, updatedAt = 7L),
        settings = mapOf("dark_theme" to "false")
    )

    private fun failsWith(text: String): String = try { BackupCodec.decode(text); fail("expected failure"); "" } catch (e: BackupException) { e.message!! }

    // ---- export / import ----
    @Test fun roundTripsEverything() {
        val back = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample.favorites, back.favorites)
        assertEquals(sample.playlists, back.playlists)
        assertEquals(sample.history, back.history)
        assertEquals(100, back.tasteProfile?.preferredBpm)
        assertEquals(mapOf("dark_theme" to "false"), back.settings)
        assertEquals(BackupCodec.CURRENT_VERSION, back.version)
    }

    @Test fun readsVersion1Backups() {
        val v1 = """{"format":"ai-music-companion-backup","version":1,"favoritePaths":["content://a/1"],
            "playlists":[{"name":"Old (بازیابی‌شده)","trackPaths":["content://a/1","content://a/2"]}],
            "tasteProfile":{"favoriteArtists":"X","favoriteGenres":"Y"}}"""
        val d = BackupCodec.decode(v1)
        assertEquals(1, d.version)
        assertEquals(listOf(TrackRef("content://a/1")), d.favorites)
        assertEquals("Old", d.playlists.single().name)
        assertEquals(2, d.playlists.single().tracks.size)
        assertEquals("X", d.tasteProfile?.favoriteArtists)
    }

    // ---- invalid backups ----
    @Test fun rejectsInvalidFiles() {
        assertTrue(failsWith("not json").contains("معتبر"))
        assertTrue(failsWith("""{"format":"other","version":1}""").contains("بکاپ این برنامه"))
        assertTrue(failsWith("""{"format":"ai-music-companion-backup","version":99}""").contains("به‌روز"))
        assertTrue(failsWith("""{"format":"ai-music-companion-backup","version":0}""").contains("نامعتبر"))
        assertTrue(failsWith("x".repeat(BackupCodec.MAX_BYTES + 1)).contains("بزرگ"))
    }

    @Test fun dropsMalformedEntriesAndUnknownSettings() {
        val text = """{"format":"ai-music-companion-backup","version":2,
            "favorites":[{"path":"","title":""},{"path":"p","title":"ok"},"junk"],
            "playlists":[{"name":"  ","tracks":[]},{"name":"P","tracks":[{"path":"p"}]}],
            "history":[{"track":{"path":"p"},"start":-5},{"track":{"path":"p"},"start":10,"completed":7.5,"replays":99999}],
            "settings":{"dark_theme":"true","cloud_ai_consent":"true","installation_id":"x"}}"""
        val d = BackupCodec.decode(text)
        assertEquals(1, d.favorites.size)
        assertEquals(listOf("P"), d.playlists.map { it.name })
        assertEquals(1, d.history.size)
        assertEquals(1f, d.history.single().completedPercentage, 0f) // clamped
        assertEquals(1000, d.history.single().replayCount)           // clamped
        assertEquals(setOf("dark_theme"), d.settings.keys)           // consent/ids are never restored
    }

    // ---- restore planning ----
    private val local = listOf(
        track(10, title = "T1", artist = "A1", dur = 200_000).copy(path = "content://new/10"),
        track(11, title = "T2", artist = "A2", dur = 200_500).copy(path = "content://new/11"),
        track(12, title = "T3", artist = "A3", dur = 999_000).copy(path = "content://new/12")
    )

    @Test fun matchesByPathThenMetadataWithDurationTolerance() {
        val m = TrackMatcher(local)
        assertEquals(10L, m.match(TrackRef("content://new/10"))?.id)
        assertEquals(10L, m.match(ref(1))?.id)                       // new device: path differs, metadata matches
        assertEquals(11L, m.match(ref(2))?.id)                       // 500ms duration drift is fine
        assertNull(m.match(TrackRef("gone", "T3", "A3", "", 100_000))) // same title/artist but different length
        assertNull(m.match(TrackRef("gone", "Nope", "X", "", 1)))
        assertNull(m.match(TrackRef("gone")))
    }

    @Test fun matchesPersianMetadataAcrossArabicKeyboardVariants() {
        val persian = listOf(track(1, title = "دلتنگی", artist = "علی", dur = 1000).copy(path = "p"))
        assertEquals(1L, TrackMatcher(persian).match(TrackRef("x", "دلتنگي", "علي", "", 1000))?.id)
    }

    @Test fun planMergesPlaylistsAvoidsDuplicatesAndSkipsExistingHistory() {
        val data = sample.copy(
            playlists = listOf(BackupPlaylist("Study", listOf(ref(1), ref(2), ref(99))), BackupPlaylist("Brand new", listOf(ref(1)))),
            history = listOf(
                BackupHistoryEntry(ref(1), 1000L, 1, 1f, false, 0),
                BackupHistoryEntry(ref(1), 1000L, 1, 1f, false, 0),   // duplicate inside the file
                BackupHistoryEntry(ref(2), 2000L, 1, 1f, false, 0)    // already on the device
            )
        )
        val plan = BackupMerger.plan(
            data, local,
            existingPlaylists = mapOf("study" to (7L to setOf(10L))),
            existingHistoryKeys = setOf(11L to 2000L),
            hasLocalTasteProfile = true
        )
        assertEquals(listOf(10L, 11L), plan.favoriteIds)
        val study = plan.playlists.first { it.name == "Study" }
        assertEquals(7L, study.existingId)
        assertEquals(listOf(11L), study.trackIds)                     // 10 already in the playlist; 99 unmatched
        assertNull(plan.playlists.first { it.name == "Brand new" }.existingId)
        assertEquals(1, plan.history.size)
        assertFalse(plan.applyTasteProfile)                            // local profile wins
        assertEquals(2, plan.unmatchedTracks)                          // ref(99) counted for the playlist... and once more below
    }

    @Test fun tasteProfileAppliedOnlyWhenNoneExistsAndEmptyBackupIsSafe() {
        assertTrue(BackupMerger.plan(sample, local, emptyMap(), emptySet(), hasLocalTasteProfile = false).applyTasteProfile)
        val empty = BackupMerger.plan(BackupData(), emptyList(), emptyMap(), emptySet(), false)
        assertTrue(empty.favoriteIds.isEmpty() && empty.playlists.isEmpty() && empty.history.isEmpty())
    }
}
