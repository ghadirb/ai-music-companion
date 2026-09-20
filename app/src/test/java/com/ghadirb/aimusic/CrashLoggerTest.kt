package com.ghadirb.aimusic

import com.ghadirb.aimusic.crash.CrashLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoggerTest {
    @Test fun scrubRemovesUrisPathsEmailsAndTokens() {
        val raw = "java.io.FileNotFoundException: content://media/external/audio/media/42 at /storage/emulated/0/Music/song.mp3 " +
            "user@example.com token ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        val clean = CrashLogger.scrub(raw)
        assertFalse(clean.contains("content://media"))
        assertFalse(clean.contains("/storage/emulated"))
        assertFalse(clean.contains("user@example.com"))
        assertFalse(clean.contains("ghp_AbCdEf"))
        assertTrue(clean.contains("FileNotFoundException"))
    }
}
