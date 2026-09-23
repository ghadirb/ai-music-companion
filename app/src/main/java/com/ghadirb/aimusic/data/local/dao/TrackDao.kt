package com.ghadirb.aimusic.data.local.dao

import androidx.room.*
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT artist FROM tracks ORDER BY artist COLLATE NOCASE ASC")
    fun observeArtists(): Flow<List<String>>

    @Query("SELECT DISTINCT album FROM tracks ORDER BY album COLLATE NOCASE ASC")
    fun observeAlbums(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album, title")
    fun observeByArtist(artist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE album = :album ORDER BY title")
    fun observeByAlbum(album: String): Flow<List<TrackEntity>>

    @Query("SELECT path FROM tracks")
    suspend fun getAllPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    /** Refreshes scanner-provided metadata without changing favorites, ids or analysis. */
    @Query("UPDATE tracks SET title = :title, artist = :artist, album = :album, genre = :genre, durationMs = :durationMs, albumArtUri = :albumArtUri, folderPath = :folderPath, dateAdded = :dateAdded WHERE path = :path")
    suspend fun updateMetadata(
        path: String,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        durationMs: Long,
        albumArtUri: String?,
        folderPath: String?,
        dateAdded: Long
    )

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /** Liking and "not interested" are mutually exclusive; favoriting a track clears "not interested". */
    @Query("UPDATE tracks SET isFavorite = :isFavorite, notInterested = CASE WHEN :isFavorite THEN 0 ELSE notInterested END WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, isFavorite: Boolean)

    // --- v6: explicit negative feedback ---

    /** Liking and "not interested" are mutually exclusive; setting one clears the other. */
    @Query("UPDATE tracks SET notInterested = :notInterested, isFavorite = CASE WHEN :notInterested THEN 0 ELSE isFavorite END WHERE id = :trackId")
    suspend fun setNotInterested(trackId: Long, notInterested: Boolean)

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE analyzed = 1")
    fun observeAnalyzedCount(): Flow<Int>

    // --- v3: on-device audio analysis (see analysis/AudioAnalyzer.kt) ---

    @Query("SELECT * FROM tracks WHERE analyzed = 0 LIMIT :limit")
    suspend fun getUnanalyzed(limit: Int): List<TrackEntity>

    @Query(
        "UPDATE tracks SET energyLevel = :energyLevel, bpm = :bpm, moodTag = :moodTag, analyzed = 1 " +
        "WHERE id = :trackId"
    )
    suspend fun saveAnalysis(trackId: Long, energyLevel: Float?, bpm: Int?, moodTag: String?)

    /** Marks a track as analyzed without a result (decode failed / too short) so the worker doesn't retry it forever. */
    @Query("UPDATE tracks SET analyzed = 1 WHERE id = :trackId")
    suspend fun markAnalyzedNoResult(trackId: Long)

    @Query("UPDATE tracks SET analyzed = 0, energyLevel = NULL, bpm = NULL, moodTag = NULL")
    suspend fun resetAudioAnalysis()



}
