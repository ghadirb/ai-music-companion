package com.ghadirb.aimusic.data.repository

import android.content.Context
import com.ghadirb.aimusic.data.local.dao.VideoDao
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.scanner.VideoScanner
import com.ghadirb.aimusic.video.VideoMoveCarryOver
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
    private val context: Context,
    private val playlistDao: com.ghadirb.aimusic.data.local.dao.VideoPlaylistDao? = null
) {
    private val prefs by lazy { context.getSharedPreferences("video_move_carry_over", Context.MODE_PRIVATE) }

    private fun loadPending(): List<VideoMoveCarryOver.Pending> =
        VideoMoveCarryOver.decode(prefs.getString("pending", "").orEmpty())

    private fun savePending(list: List<VideoMoveCarryOver.Pending>) {
        prefs.edit().putString("pending", VideoMoveCarryOver.encode(list)).apply()
    }

    /**
     * Call right before a video is moved by copy + delete (its id will change). Once the new file is indexed,
     * [sync] hands its favourite flag, resume position and playlists over to it.
     */
    fun rememberForMove(videos: List<VideoEntity>, nowMs: Long = System.currentTimeMillis()) {
        val keep = loadPending().filter { p -> videos.none { it.id == p.oldId } }
        savePending(keep + videos.map { VideoMoveCarryOver.pendingOf(it, nowMs) })
    }

    /** Applies pending carry-overs to freshly scanned rows; returns the (id changes) so playlists can follow. */
    private fun applyCarryOver(merged: List<VideoEntity>, existingIds: Set<Long>, nowMs: Long): Pair<List<VideoEntity>, List<Pair<Long, Long>>> {
        val pending = loadPending().filterNot { VideoMoveCarryOver.isExpired(it, nowMs) }
        if (pending.isEmpty()) return merged to emptyList()
        val result = merged.toMutableList()
        val remaps = ArrayList<Pair<Long, Long>>()
        val stillWaiting = ArrayList<VideoMoveCarryOver.Pending>()
        for (p in pending) {
            // Only brand-new rows can be the moved copy; the old row (same id) may still be there until it is deleted.
            val candidates = result.filter { it.id !in existingIds }
            val target = VideoMoveCarryOver.match(p, candidates)
            if (target == null) { stillWaiting += p; continue }
            val i = result.indexOfFirst { it.id == target.id }
            result[i] = target.copy(lastPositionMs = p.positionMs, lastPlayedAt = p.lastPlayedAt, isFavorite = p.favorite)
            remaps += p.oldId to target.id
        }
        savePending(stillWaiting)
        return result to remaps
    }

    fun observeVideos(): Flow<List<VideoEntity>> = dao.observeAll()

    suspend fun snapshot(): List<VideoEntity> = dao.getAll()

    suspend fun get(id: Long): VideoEntity? = dao.getById(id)

    /** Videos for [ids] (unordered; unknown ids are simply absent). */
    suspend fun getMany(ids: List<Long>): List<VideoEntity> =
        ids.filter { it > 0 }.chunked(500).flatMap { dao.getByIds(it) } + com.ghadirb.aimusic.video.StreamUrl.find(ids)

    /**
     * Re-scans MediaStore and reconciles the database. Returns false (and changes nothing) when the
     * scan could not run, e.g. the video permission was revoked, so a failed scan never wipes the cache.
     */
    suspend fun sync(deep: Boolean = false): Boolean {
        // Videos that are on the phone but unknown to Android's media database are re-indexed first (same
        // mechanism as Settings > "find unknown files"). Manual refresh always does it; the automatic sync on
        // tab open only if the last run is older than a few minutes. Harmless where storage is not readable.
        runCatching {
            if (deep) com.ghadirb.aimusic.library.MediaRescue.scanAll(context)
            else com.ghadirb.aimusic.library.MediaRescue.scanAllIfStale(context)
        }
        val rawScanned = VideoScanner.scan(context) ?: return false
        val excluded = com.ghadirb.aimusic.library.ExcludedFoldersStore(context).load()
        val scanned = if (excluded.isEmpty()) rawScanned
        else rawScanned.filterNot { com.ghadirb.aimusic.library.ExcludedFolders.isExcludedRelative(it.relativePath, excluded) }
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
        // A moved video (copy + delete) came back with a new id: give it the old favourite / position / playlists.
        val (carried, remaps) = applyCarryOver(merged, existing.keys, System.currentTimeMillis())
        remaps.forEach { (oldId, newId) -> playlistDao?.remapVideo(oldId, newId) }
        // Only rewrite rows that are new or actually changed; large libraries stay cheap.
        val changed = carried.filter { existing[it.id] != it }
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

    suspend fun setFavorites(ids: List<Long>, favorite: Boolean) {
        ids.chunked(500).forEach { dao.setFavorites(it, favorite) }
    }

    /** Clears "recent" and "continue watching" only; never touches files or favourites. */
    suspend fun clearHistory() = dao.clearHistory()

    /** Marks as watched / starts over: next play begins at 0. */
    suspend fun resetPosition(id: Long) = dao.resetPosition(id)
}
