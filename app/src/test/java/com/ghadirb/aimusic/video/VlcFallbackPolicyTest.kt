package com.ghadirb.aimusic.video

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcFallbackPolicyTest {
    @Test fun extensionsAreCaseInsensitive() {
        assertEquals("avi", VlcFallbackPolicy.extensionOf("Movie.Part1.AVI"))
        assertEquals("", VlcFallbackPolicy.extensionOf("noextension"))
    }

    @Test fun legacyContainersGoStraightToVlc() {
        listOf("a.avi", "b.WMV", "c.rmvb", "d.vob", "e.divx").forEach {
            assertTrue(it, VlcFallbackPolicy.prefersVlc(it))
        }
    }

    @Test fun commonContainersStayOnExoPlayer() {
        listOf("a.mp4", "b.mkv", "c.webm", "d.mov", "e.ts", "f.m4v").forEach {
            assertFalse(it, VlcFallbackPolicy.prefersVlc(it))
        }
    }

    @Test fun containerErrorsFallBack() {
        assertTrue(VlcFallbackPolicy.shouldFallBackToVlc(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, false))
        assertTrue(VlcFallbackPolicy.shouldFallBackToVlc(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, false))
    }

    @Test fun decoderErrorsFallBackOnlyAfterSoftwareWasTried() {
        val c = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        assertFalse(VlcFallbackPolicy.shouldFallBackToVlc(c, softwareDecodersTried = false))
        assertTrue(VlcFallbackPolicy.shouldFallBackToVlc(c, softwareDecodersTried = true))
    }

    @Test fun missingFilesNeverFallBack() {
        assertFalse(VlcFallbackPolicy.shouldFallBackToVlc(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, true))
        assertFalse(VlcFallbackPolicy.shouldFallBackToVlc(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, true))
    }
}
