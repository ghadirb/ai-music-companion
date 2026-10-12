package com.ghadirb.aimusic.premium

import android.content.SharedPreferences

class PrefsQuotaStorage(private val prefs: SharedPreferences) : QuotaStorage {
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
}
