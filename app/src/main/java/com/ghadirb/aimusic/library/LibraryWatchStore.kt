package com.ghadirb.aimusic.library

import android.content.Context

/**
 * Settings + last-result store for "Smart Library Watch" (spec item 7): an entirely
 * optional periodic check for new music files. Off by default — nothing runs unless
 * the user turns it on in Settings. Mirrors the plain-SharedPreferences pattern already
 * used by [com.ghadirb.aimusic.recommendation.TuningStore] / [com.ghadirb.aimusic.cloud.CloudConsent].
 */
class LibraryWatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("library_watch", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Set right after a check finishes; cleared once the user has seen it (Settings screen). */
    var pendingSummary: String?
        get() = prefs.getString(KEY_SUMMARY, null)
        set(value) = prefs.edit().putString(KEY_SUMMARY, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SUMMARY = "pending_summary"
    }
}
