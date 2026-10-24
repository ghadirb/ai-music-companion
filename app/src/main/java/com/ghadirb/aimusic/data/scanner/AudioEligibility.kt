package com.ghadirb.aimusic.data.scanner

/**
 * Decides whether an audio row reported by MediaStore belongs in the music library.
 *
 * The old rule was "IS_MUSIC != 0", but many real songs are stored with IS_MUSIC = 0 (copied from
 * messengers, downloaded by a browser, tagged oddly), so they stayed invisible until the manual
 * "find unknown files" run. Now a file is accepted when it is flagged as music OR when it is not
 * flagged as a ringtone/alarm/notification and does not live in a voice-note / call-recording folder.
 * Pure function (no Android) so it is unit-tested.
 */
object AudioEligibility {
    const val MIN_DURATION_MS = 15_000L

    private val NOISE_FOLDERS = listOf(
        "/voice notes", "/whatsapp voice", "/call", "/recordings", "/recorder", "/sound recorder",
        "/voice recorder", "/ringtones", "/notifications", "/alarms", "/android/data/", "/android/media/"
    )

    fun isLibraryCandidate(
        isMusic: Boolean,
        isRingtone: Boolean,
        isAlarm: Boolean,
        isNotification: Boolean,
        durationMs: Long,
        path: String?
    ): Boolean {
        if (durationMs < MIN_DURATION_MS) return false
        if (isRingtone || isAlarm || isNotification) return false
        if (isMusic) return true
        val p = path?.lowercase().orEmpty()
        return NOISE_FOLDERS.none { p.contains(it) }
    }
}
