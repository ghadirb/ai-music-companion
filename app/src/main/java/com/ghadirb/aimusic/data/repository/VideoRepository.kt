package com.ghadirb.aimusic.data.repository

import android.content.Context
import com.ghadirb.aimusic.data.local.dao.VideoDao
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.scanner.VideoScanner
import com.ghadirb.aimusic.video.VideoResume
import kotlinx.coroutines.flow.Flow

/**
 * Single access point for the local video library. Separate from [MusicRepository] on purpose.
 *
 * The Room table is a cache of MediaStore plus the user's own state (resume position, last played,
 * favourite). [sync] keeps the two consistent: new files are added, changed metadata is refreshed
 * WITHOUT losing user state, and files that no longer exist are dropped so nothing missing is shown.
 */
class VideoRepository(
    private val dao: VideoDao,
    private val context: Context
) {
    fun observeVideos(): Flow<List<VideoEntity>> = dao.observeAll()

    suspend fun snapshot(): List<VideoEntity> = dao.getAll()

    suspend fun get(id: Long): VideoEntity? = dao.getById(id)

    /** Videos for [ids] (unordered; unknown ids are simply absent). */
    suspend fun getMany(ids: List<Long>): List<VideoEntity> =
        ids.chunked(500).flatMap { dao.getByIds(it) }

    /**
     * Re-scans MediaStore and reconciles the database. Returns false (and changes nothing) when the
     * scan could not run, e.g. the video permission was revoked, so a failed scan never wipes the cache.
     */
    suspend fun sync(): Boolean {
        val scanned = VideoScanner.scan(context) ?: return false
        val existing = dao.getAll().associateBy { it.id }
        val merged = scanned.map { fresh ->
            val old = existing[fresh.id]
            if (old == null) fresh
            else fresh.copy(
                lastPositionMs = old.lastPositionMs,
                lastPlayedAt = old.lastPlayedAt,
                isFavorite = old.isFavorite
            )
        }
        // Only rewrite rows that are new or actually changed; large libraries stay cheap.
        val changed = merged.filter { existing[it.id] != it }
        if (changed.isNotEmpty()) dao.upsertAll(changed)

        val scannedIds = scanned.mapTo(HashSet()) { it.id }
        val gone = existing.keys.filter { it !in scannedIds }
        // SQLite caps bound parameters per statement, so delete in chunks.
        gone.chunked(500).forEach { dao.deleteByIds(it) }
        return true
    }

    /** Called when playback of [id] starts, so "اخیراً پخش‌شده" ordering is right even if the app is killed. */
    suspend fun markPlayed(id: Long, nowMs: Long = System.currentTimeMillis()) {
        val current = dao.getById(id) ?: return
        dao.updateProgress(id, current.lastPositionMs, nowMs)
    }

    /**
     * Stores the resume position. Positions near the end are reset to 0 (see [VideoResume]) so the
     * next play starts from the beginning. Callers throttle this (every few seconds, pause, stop...).
     */
    suspend fun saveProgress(id: Long, positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        dao.updateProgress(id, VideoResume.positionToSave(positionMs, durationMs), nowMs)
    }

    /** Drops a record whose file turned out to be missing/unreadable so it stops being listed. */
    suspend fun remove(ids: List<Long>) {
        ids.chunked(500).forEach { dao.deleteByIds(it) }
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)
}
