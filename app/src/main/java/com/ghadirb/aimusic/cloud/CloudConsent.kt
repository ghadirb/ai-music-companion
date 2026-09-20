package com.ghadirb.aimusic.cloud

import android.content.Context

/**
 * User consent for features that send data to the gateway (see PRIVACY_POLICY.md):
 * "Improve similar tracks with AI" (short track descriptors) and AI DJ (the user's typed request).
 * Off by default. Music files, lyrics and listening history are never sent.
 */
class CloudConsent(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }

    companion object {
        const val PREFS = "cloud_ai_preferences"
        const val KEY = "cloud_ai_consent"
    }
}
