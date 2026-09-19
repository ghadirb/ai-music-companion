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

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("UPDATE tracks SET isFavorite = :isFavorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, isFavorite: Boolean)

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

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

    @Query(
        "SELECT * FROM tracks WHERE moodTag IN (:moodTags) " +
        "ORDER BY (CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END), energyLevel ASC LIMIT :limit"
    )
    suspend fun getByMoodTags(moodTags: List<String>, limit: Int): List<TrackEntity>

    @Query(
        "SELECT * FROM tracks WHERE energyLevel IS NOT NULL AND energyLevel >= :minEnergy " +
        "ORDER BY (CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END), energyLevel DESC LIMIT :limit"
    )
    suspend fun getHighEnergyTracks(minEnergy: Float, limit: Int): List<TrackEntity>

    @Query(
        "SELECT * FROM tracks WHERE energyLevel IS NOT NULL AND energyLevel <= :maxEnergy " +
        "ORDER BY (CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END), energyLevel ASC LIMIT :limit"
    )
    suspend fun getLowEnergyTracks(maxEnergy: Float, limit: Int): List<TrackEntity>
}
