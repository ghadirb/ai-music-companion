package com.ghadirb.aimusic.data.repository

import android.content.Context
import com.ghadirb.aimusic.data.local.dao.ListeningHistoryDao
import com.ghadirb.aimusic.data.local.dao.TrackDao
import com.ghadirb.aimusic.data.local.dao.UserPreferenceDao
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.scanner.MediaLibraryScanner
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for tracks + listening history. UI/ViewModels only
 * talk to this class, never to the DAOs or the scanner directly, so the
 * storage layer (Room today) can be swapped without touching the UI.
 */
class MusicRepository(
    private val trackDao: TrackDao,
    private val historyDao: ListeningHistoryDao,
    private val preferenceDao: UserPreferenceDao,
    context: Context
) {
    private val scanner = MediaLibraryScanner(context)

    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun observeFavorites(): Flow<List<TrackEntity>> = trackDao.observeFavorites()
    fun observeArtists(): Flow<List<String>> = trackDao.observeArtists()
    fun observeAlbums(): Flow<List<String>> = trackDao.observeAlbums()
    fun observeByArtist(artist: String): Flow<List<TrackEntity>> = trackDao.observeByArtist(artist)
    fun observeByAlbum(album: String): Flow<List<TrackEntity>> = trackDao.observeByAlbum(album)
    fun observeUserPreference() = preferenceDao.observe()

    suspend fun trackCount(): Int = trackDao.count()

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
        val removedPaths = existingPaths.filter { it !in scannedPaths }

        if (newTracks.isNotEmpty()) trackDao.insertAll(newTracks)
        if (removedPaths.isNotEmpty()) trackDao.deleteByPaths(removedPaths)
    }

    suspend fun setFavorite(trackId: Long, isFavorite: Boolean) =
        trackDao.setFavorite(trackId, isFavorite)

    suspend fun getTrack(trackId: Long): TrackEntity? = trackDao.getById(trackId)

    /** Records one listening session. Called by PlayerViewModel on track change/stop. */
    suspend fun recordListening(entry: ListeningHistoryEntity): Long = historyDao.insert(entry)

    suspend fun recentHistory(limit: Int = 200) = historyDao.getRecent(limit)
    suspend fun mostPlayed(limit: Int = 20) = historyDao.mostPlayedTrackIds(limit)
}
