package com.ghadirb.aimusic.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ghadirb.aimusic.BuildConfig
import com.ghadirb.aimusic.data.repository.MusicRepository

/**
 * Local-only backup/restore. Never includes audio files, tokens, purchases or the cloud-AI consent.
 * Restore validates the file, checks version compatibility, matches songs by path OR metadata (so it also
 * works on a new device), merges playlists with the same name instead of duplicating them, skips
 * history that already exists, and applies everything in one transaction.
 */
class LocalLibraryBackup(private val repository: MusicRepository, private val context: Context? = null) {

    suspend fun exportTo(resolver: ContentResolver, destination: Uri) {
        val tracks = repository.allTracksSnapshot()
        val byId = tracks.associateBy { it.id }
        fun ref(t: com.ghadirb.aimusic.data.local.entity.TrackEntity) = TrackRef(t.path, t.title, t.artist, t.album, t.durationMs)

        val playlists = repository.getAllPlaylists().map { playlist ->
            BackupPlaylist(playlist.name, repository.getTracksInPlaylist(playlist.id).map(::ref))
        }
        val history = repository.recentHistory(BackupCodec.MAX_HISTORY).mapNotNull { h ->
            byId[h.trackId]?.let { BackupHistoryEntry(ref(it), h.startTime, h.listenDurationMs, h.completedPercentage, h.skipped, h.replayCount) }
        }
        val darkTheme = context?.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)?.getBoolean("dark_theme", true)
        val data = BackupData(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            favorites = tracks.filter { it.isFavorite }.map(::ref),
            playlists = playlists,
            history = history,
            tasteProfile = repository.getUserPreference(),
            settings = darkTheme?.let { mapOf("dark_theme" to it.toString()) }.orEmpty()
        )
        val text = BackupCodec.encode(data)
        resolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: throw BackupException("فایل مقصد بکاپ باز نشد.")
    }

    suspend fun restoreFrom(resolver: ContentResolver, source: Uri): RestoreSummary {
        val text = resolver.openInputStream(source)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > BackupCodec.MAX_BYTES) throw BackupException("فایل بکاپ بیش از حد بزرگ است.")
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } ?: throw BackupException("فایل بکاپ خوانده نشد.")

        val data = BackupCodec.decode(text)
        val localTracks = repository.allTracksSnapshot()
        val existingPlaylists = repository.getAllPlaylists().associate { p ->
            p.name.lowercase() to (p.id to repository.getTracksInPlaylist(p.id).map { it.id }.toSet())
        }
        val existingHistory = repository.recentHistory(BackupCodec.MAX_HISTORY * 2).map { it.trackId to it.startTime }.toSet()
        val plan = BackupMerger.plan(data, localTracks, existingPlaylists, existingHistory, repository.getUserPreference() != null)

        repository.applyRestorePlan(plan, data.tasteProfile)
        return RestoreSummary(
            favorites = plan.favoriteIds.size,
            playlistsCreated = plan.playlists.count { it.existingId == null },
            playlistsMerged = plan.playlists.count { it.existingId != null },
            tracksLinked = plan.playlists.sumOf { it.trackIds.size },
            historyEntries = plan.history.size,
            unmatchedTracks = plan.unmatchedTracks,
            darkTheme = plan.settings["dark_theme"]?.toBooleanStrictOrNull()
        )
    }
}
