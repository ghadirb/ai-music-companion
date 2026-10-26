package com.ghadirb.aimusic.video

import android.content.Context

/** Remembers the subtitle look and a few player choices between runs. */
object SubtitleStyleStore {
    private const val FILE = "video_player_prefs"

    fun load(context: Context): SubtitleStyle {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SubtitleStyle(
            size = p.getInt("sub_size", 1), color = p.getInt("sub_color", 0),
            background = p.getInt("sub_bg", 1), outline = p.getBoolean("sub_outline", true)
        ).sanitized()
    }

    fun save(context: Context, style: SubtitleStyle) {
        val s = style.sanitized()
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("sub_size", s.size).putInt("sub_color", s.color)
            .putInt("sub_bg", s.background).putBoolean("sub_outline", s.outline).apply()
    }

    fun loadLoopAll(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("loop_all", false)

    fun saveLoopAll(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("loop_all", value).apply()
    }

    /** Drag gestures (seek / brightness / volume) can be switched off for good; the lock button still works. */
    fun loadGestures(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("gestures", true)

    fun saveGestures(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("gestures", value).apply()
    }
}
