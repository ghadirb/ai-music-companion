package com.ghadirb.aimusic.playback

import android.content.Context

/** Snapshot of the queue used to "continue playback" after the process was killed. */
data class SavedPlayback(
    val trackIds: List<Long>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int
)

/** Tiny on-device store (SharedPreferences). Nothing here ever leaves the device. */
class PlaybackStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(state: SavedPlayback) {
        val s = SessionRestore.limit(state, MAX_SAVED)
        prefs.edit()
            .putString(KEY_IDS, s.trackIds.joinToString(","))
            .putInt(KEY_INDEX, s.index)
            .putLong(KEY_POSITION, s.positionMs)
            .putBoolean(KEY_SHUFFLE, s.shuffle)
            .putInt(KEY_REPEAT, s.repeatMode)
            .apply()
    }

    /**
     * Cheap periodic update while playing: only the position changes, so the (possibly long) queue
     * string is not rewritten every few seconds. The index is stored relative to the saved window,
     * so it is only valid while the queue itself has not changed; queue changes call [save].
     */
    fun savePosition(positionMs: Long) {
        prefs.edit().putLong(KEY_POSITION, positionMs.coerceAtLeast(0L)).apply()
    }

    fun load(): SavedPlayback? {
        val ids = prefs.getString(KEY_IDS, "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return null
        return SavedPlayback(
            trackIds = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0)
        )
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS = "playback_state"
        const val KEY_IDS = "ids"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val MAX_SAVED = 2000
    }
}
