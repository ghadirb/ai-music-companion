package com.ghadirb.aimusic.data.repository

import android.content.Context
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
import androidx.room.withTransaction
import com.ghadirb.aimusic.data.local.AppDatabase
import com.ghadirb.aimusic.data.local.dao.ListeningHistoryDao
import com.ghadirb.aimusic.data.local.dao.PlaylistDao
import com.ghadirb.aimusic.data.local.dao.TrackDao
import com.ghadirb.aimusic.data.local.dao.UserPreferenceDao
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistEntity
import com.ghadirb.aimusic.data.local.entity.PlaylistTrackCrossRef
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.scanner.MediaLibraryScanner
import kotlinx.coroutines.flow.Flow
import com.ghadirb.aimusic.library.TrackStat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for tracks + listening history. UI/ViewModels only
 * talk to this class, never to the DAOs or the scanner directly, so the
 * storage layer (Room today) can be swapped without touching the UI.
 */
class MusicRepository(
    private val trackDao: TrackDao,
    private val historyDao: ListeningHistoryDao,
    private val preferenceDao: UserPreferenceDao,
    private val playlistDao: PlaylistDao,
    context: Context,
    private val database: AppDatabase? = null
) {
    private val appContext = context.applicationContext
    private val scanner = MediaLibraryScanner(appContext)

    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun observeFavorites(): Flow<List<TrackEntity>> = trackDao.observeFavorites()
    fun observeArtists(): Flow<List<String>> = trackDao.observeArtists()
    fun observeAlbums(): Flow<List<String>> = trackDao.observeAlbums()
    fun observeByArtist(artist: String): Flow<List<TrackEntity>> = trackDao.observeByArtist(artist)
    fun observeByAlbum(album: String): Flow<List<TrackEntity>> = trackDao.observeByAlbum(album)
    fun observeUserPreference() = preferenceDao.observe()

    suspend fun trackCount(): Int = trackDao.count()

    fun observeTrackStats(): Flow<List<TrackStat>> = historyDao.observeTrackStats()
        .map { rows -> rows.map { TrackStat(it.trackId, it.playCount, it.lastPlayedAt) } }

    /** (analyzed, total) so the UI can show real analysis progress. */
    fun observeAnalysisProgress(): Flow<Pair<Int, Int>> =
        combine(trackDao.observeAnalyzedCount(), trackDao.observeCount()) { done, total -> done to total }

    /**
     * Re-scans MediaStore and reconciles with what's already in Room:
     * inserts new tracks, removes ones that no longer exist on disk.
     * Existing favorite flags / ids for unchanged tracks are preserved
     * because we only insert paths we don't already have.
     */
    suspend fun rescanLibrary() {
        val scanned = scanner.scan()
        val existingPaths = trackDao.getAllPaths().toSet()
        val scannedPaths = scanned.map { it.path }.toSet()

        val newTracks = scanned.filter { it.path !in existingPaths }
        val existingTracks = scanned.filter { it.path in existingPaths }
        // An empty scan is treated as a provider glitch: never wipe the library (and favourites) because of it.
        val removedPaths = if (scanned.isEmpty()) emptyList() else existingPaths.filter { it !in scannedPaths }

        val applyChanges: suspend () -> Unit = {
            if (newTracks.isNotEmpty()) trackDao.insertAll(newTracks)
            // A re-scan also repairs legacy labels already saved in the database.
            existingTracks.forEach { track ->
                trackDao.updateMetadata(
                    path = track.path, title = track.title, artist = track.artist,
                    album = track.album, genre = track.genre, durationMs = track.durationMs,
                    albumArtUri = track.albumArtUri, folderPath = track.folderPath,
                    dateAdded = track.dateAdded
                )
            }
            // SQLite has a variable limit, so delete in chunks.
            removedPaths.chunked(500).forEach { trackDao.deleteByPaths(it) }
        }
        // One transaction: faster on big libraries and never leaves a half-applied scan.
        if (database != null) database.withTransaction { applyChanges() } else applyChanges()
    }

    suspend fun setFavorite(trackId: Long, isFavorite: Boolean) =
        trackDao.setFavorite(trackId, isFavorite)


    suspend fun getTrack(trackId: Long): TrackEntity? = trackDao.getById(trackId)

    /** Records one listening session. Called by PlayerViewModel on track change/stop. */
    suspend fun recordListening(entry: ListeningHistoryEntity): Long = historyDao.insert(entry)

    suspend fun recentHistory(limit: Int = 200) = historyDao.getRecent(limit)

    fun observeUserPreferenceFlow() = preferenceDao.observe()
    suspend fun getUserPreference(): UserPreferenceEntity? = preferenceDao.get()
    suspend fun saveUserPreference(preference: UserPreferenceEntity) = preferenceDao.upsert(preference)

    // ---- Playlists (manual, MVP item 5 in the spec) ----

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name))

    /** Creates a playlist (unique name) and fills it in one transaction. Used by smart mixes / generated playlists. */
    suspend fun createPlaylistWithTracks(name: String, trackIds: List<Long>): Long {
        val taken = playlistDao.getAllPlaylists().map { it.name.lowercase() }.toSet()
        val base = name.trim().ifEmpty { "پلی‌لیست هوشمند" }
        var finalName = base
        var n = 2
        while (finalName.lowercase() in taken) { finalName = "$base ($n)"; n++ }
        val create: suspend () -> Long = {
            val id = playlistDao.insertPlaylist(PlaylistEntity(name = finalName))
            trackIds.distinct().forEachIndexed { index, trackId ->
                playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(id, trackId, index))
            }
            id
        }
        return if (database != null) database.withTransaction { create() } else create()
    }

    /** Applies a validated backup restore plan atomically (favourites, playlists merge, history, taste profile). */
    suspend fun applyRestorePlan(plan: com.ghadirb.aimusic.backup.RestorePlan, tasteProfile: UserPreferenceEntity?) {
        val apply: suspend () -> Unit = {
            plan.favoriteIds.forEach { trackDao.setFavorite(it, true) }
            plan.playlists.forEach { p ->
                val id = p.existingId ?: playlistDao.insertPlaylist(PlaylistEntity(name = p.name))
                var position = if (p.existingId != null) playlistDao.nextPosition(id) else 0
                p.trackIds.forEach { trackId -> playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(id, trackId, position++)) }
            }
            plan.history.forEach { historyDao.insert(it) }
            if (plan.applyTasteProfile && tasteProfile != null) preferenceDao.upsert(tasteProfile.copy(id = 0))
        }
        if (database != null) database.withTransaction { apply() } else apply()
    }

    suspend fun deletePlaylist(playlistId: Long) = playlistDao.deletePlaylist(playlistId)

    suspend fun renamePlaylist(playlistId: Long, name: String) = playlistDao.renamePlaylist(playlistId, name)

    fun observeTracksInPlaylist(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.observeTracksInPlaylist(playlistId)

    fun observePlaylistTrackCount(playlistId: Long): Flow<Int> =
        playlistDao.observeTrackCount(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val position = playlistDao.nextPosition(playlistId)
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, position))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)

    suspend fun getPlaylist(playlistId: Long): PlaylistEntity? = playlistDao.getPlaylist(playlistId)

    suspend fun getAllPlaylists(): List<PlaylistEntity> = playlistDao.getAllPlaylists()

    suspend fun getTracksInPlaylist(playlistId: Long): List<TrackEntity> =
        playlistDao.observeTracksInPlaylist(playlistId).first()

    suspend fun allTracksSnapshot(): List<TrackEntity> = observeTracks().first()

    // ---- On-device audio analysis (see analysis/AudioAnalyzer.kt) ----

    suspend fun getUnanalyzedTracks(limit: Int = 25): List<TrackEntity> = trackDao.getUnanalyzed(limit)

    suspend fun saveTrackAnalysis(trackId: Long, energyLevel: Float?, bpm: Int?, moodTag: String?) =
        trackDao.saveAnalysis(trackId, energyLevel, bpm, moodTag)

    suspend fun markTrackAnalyzedNoResult(trackId: Long) = trackDao.markAnalyzedNoResult(trackId)

    /** Rebuilds only derived energy/BPM/mood labels; user library data is untouched. */
    suspend fun reanalyzeLibrary() {
        trackDao.resetAudioAnalysis()
        AudioAnalysisWorker.enqueueNow(appContext)
    }
}
