package com.ghadirb.aimusic.data.repository

import android.content.Context
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
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
import kotlinx.coroutines.flow.first

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
    context: Context
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
        val removedPaths = existingPaths.filter { it !in scannedPaths }

        if (newTracks.isNotEmpty()) trackDao.insertAll(newTracks)
        // A re-scan also repairs legacy labels already saved in the database.
        existingTracks.forEach { track ->
            trackDao.updateMetadata(
                path = track.path, title = track.title, artist = track.artist,
                album = track.album, genre = track.genre, durationMs = track.durationMs,
                albumArtUri = track.albumArtUri, folderPath = track.folderPath
            )
        }
        if (removedPaths.isNotEmpty()) trackDao.deleteByPaths(removedPaths)
    }

    suspend fun setFavorite(trackId: Long, isFavorite: Boolean) =
        trackDao.setFavorite(trackId, isFavorite)

    suspend fun getTrack(trackId: Long): TrackEntity? = trackDao.getById(trackId)

    /** Records one listening session. Called by PlayerViewModel on track change/stop. */
    suspend fun recordListening(entry: ListeningHistoryEntity): Long = historyDao.insert(entry)

    suspend fun recentHistory(limit: Int = 200) = historyDao.getRecent(limit)
    suspend fun mostPlayed(limit: Int = 20) = historyDao.mostPlayedTrackIds(limit)

    fun observeUserPreferenceFlow() = preferenceDao.observe()
    suspend fun getUserPreference(): UserPreferenceEntity? = preferenceDao.get()
    suspend fun saveUserPreference(preference: UserPreferenceEntity) = preferenceDao.upsert(preference)

    // ---- Playlists (manual, MVP item 5 in the spec) ----

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name))

    suspend fun deletePlaylist(playlistId: Long) = playlistDao.deletePlaylist(playlistId)

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

    /**
     * "آهنگ‌های فراموش‌شده" (doc, smart-playlist list): tracks that were
     * favorited or previously listened to completion, but have no listening
     * history entry in the last [staleDays] days. Uses only local data
     * already collected — no new signal required.
     */
    suspend fun rediscoverTracks(limit: Int = 10, staleDays: Int = 14): List<TrackEntity> {
        val cutoff = System.currentTimeMillis() - staleDays * 24L * 60 * 60 * 1000
        val allTracks = observeTracks().first()
        val recentHistory = historyDao.getRecent(1000)
        val recentlyPlayedIds = recentHistory.filter { it.startTime >= cutoff }.map { it.trackId }.toSet()
        val everPlayedIds = recentHistory.map { it.trackId }.toSet()

        return allTracks
            .filter { it.id !in recentlyPlayedIds && (it.isFavorite || it.id in everPlayedIds) }
            .shuffled()
            .take(limit)
    }

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

    /**
     * Backs the "مناسب شب" Home card. Real filter now that AudioAnalyzer exists:
     * calm-mood tracks first (favorites prioritized), and if not enough tracks
     * have been analyzed yet, tops up with low-energy tracks so the card isn't
     * empty during the first day of background analysis.
     */
    suspend fun nightSuitableTracks(limit: Int = 6): List<TrackEntity> {
        val byMood = trackDao.getByMoodTags(listOf(com.ghadirb.aimusic.analysis.AudioAnalyzer.MoodTag.CALM), limit)
        if (byMood.size >= limit) return byMood
        val topUp = trackDao.getLowEnergyTracks(0.45f, limit)
        return (byMood + topUp).distinctBy { it.id }.take(limit)
    }

    /** Backs the "مناسب رانندگی" Home card — same idea as [nightSuitableTracks], inverted. */
    suspend fun drivingSuitableTracks(limit: Int = 6): List<TrackEntity> {
        val byMood = trackDao.getByMoodTags(listOf(com.ghadirb.aimusic.analysis.AudioAnalyzer.MoodTag.ENERGETIC), limit)
        if (byMood.size >= limit) return byMood
        val topUp = trackDao.getHighEnergyTracks(0.55f, limit)
        return (byMood + topUp).distinctBy { it.id }.take(limit)
    }

    /** Calm, low-energy mix for reading or focused work. */
    suspend fun focusSuitableTracks(limit: Int = 6): List<TrackEntity> =
        trackDao.getLowEnergyTracks(0.55f, limit)

    /** Higher-energy mix for exercise. */
    suspend fun workoutSuitableTracks(limit: Int = 6): List<TrackEntity> =
        trackDao.getHighEnergyTracks(0.65f, limit)

    /** Local LRC sentiment (when available) plus audio energy for a happy/dance rail. */
    suspend fun happyDanceTracks(limit: Int = 6): List<TrackEntity> {
        val byMood = trackDao.getByMoodTags(
            listOf(com.ghadirb.aimusic.analysis.AudioAnalyzer.MoodTag.HAPPY, com.ghadirb.aimusic.analysis.AudioAnalyzer.MoodTag.ENERGETIC),
            limit
        )
        if (byMood.size >= limit) return byMood
        return (byMood + trackDao.getHighEnergyTracks(0.65f, limit)).distinctBy { it.id }.take(limit)
    }

    /** Only appears when a local LRC file supplied a clear sad signal. */
    suspend fun sadTracks(limit: Int = 6): List<TrackEntity> =
        trackDao.getByMoodTags(listOf(com.ghadirb.aimusic.analysis.AudioAnalyzer.MoodTag.SAD), limit)
}
