package com.ghadirb.aimusic.data

import com.ghadirb.aimusic.data.scanner.AudioEligibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEligibilityTest {
    private fun ok(
        music: Boolean = false, ring: Boolean = false, alarm: Boolean = false, notif: Boolean = false,
        ms: Long = 200_000, path: String? = "/storage/emulated/0/Download/a.mp3"
    ) = AudioEligibility.isLibraryCandidate(music, ring, alarm, notif, ms, path)

    @Test fun flaggedMusicIsAccepted() = assertTrue(ok(music = true))
    @Test fun unflaggedSongInDownloadsIsAccepted() = assertTrue(ok(music = false))
    @Test fun tooShortIsRejected() = assertFalse(ok(music = true, ms = 14_999))
    @Test fun ringtoneAlarmNotificationAreRejected() {
        assertFalse(ok(music = true, ring = true))
        assertFalse(ok(music = true, alarm = true))
        assertFalse(ok(music = true, notif = true))
    }
    @Test fun voiceNotesAndRecordingsAreRejectedWhenNotMusic() {
        assertFalse(ok(path = "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/x.opus"))
        assertFalse(ok(path = "/storage/emulated/0/Recordings/call.m4a"))
    }
    @Test fun flaggedMusicInOddFolderStillAccepted() = assertTrue(ok(music = true, path = "/storage/emulated/0/Recordings/song.mp3"))
    @Test fun missingPathIsAccepted() = assertTrue(ok(path = null))
}
