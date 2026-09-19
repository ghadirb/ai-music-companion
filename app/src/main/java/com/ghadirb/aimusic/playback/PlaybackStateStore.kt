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
        prefs.edit()
            .putString(KEY_IDS, state.trackIds.take(MAX_SAVED).joinToString(","))
            .putInt(KEY_INDEX, state.index)
            .putLong(KEY_POSITION, state.positionMs)
            .putBoolean(KEY_SHUFFLE, state.shuffle)
            .putInt(KEY_REPEAT, state.repeatMode)
            .apply()
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
