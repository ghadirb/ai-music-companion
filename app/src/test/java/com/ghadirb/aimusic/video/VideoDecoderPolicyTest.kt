package com.ghadirb.aimusic.video

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderPolicyTest {
    @Test fun recognisesPlatformSoftwareDecoders() {
        assertTrue(VideoDecoderPolicy.isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(VideoDecoderPolicy.isSoftwareDecoder("c2.android.hevc.decoder"))
        assertTrue(VideoDecoderPolicy.isSoftwareDecoder("c2.android.av1.decoder"))
    }

    @Test fun vendorHardwareDecodersAreNotSoftware() {
        assertFalse(VideoDecoderPolicy.isSoftwareDecoder("OMX.qcom.video.decoder.avc"))
        assertFalse(VideoDecoderPolicy.isSoftwareDecoder("c2.mtk.hevc.decoder"))
        assertFalse(VideoDecoderPolicy.isSoftwareDecoder("c2.exynos.h264.decoder"))
    }

    @Test fun orderKeepsListWhenSoftwareNotPreferred() {
        val l = listOf("OMX.qcom.avc", "OMX.google.h264.decoder")
        assertEquals(l, VideoDecoderPolicy.order(l, false) { it })
    }

    @Test fun orderPutsSoftwareFirstAndIsStable() {
        val l = listOf("OMX.qcom.a", "OMX.google.x", "OMX.qcom.b", "c2.android.y")
        assertEquals(
            listOf("OMX.google.x", "c2.android.y", "OMX.qcom.a", "OMX.qcom.b"),
            VideoDecoderPolicy.order(l, true) { it }
        )
    }

    @Test fun decoderErrorsTriggerRetryOnlyOnce() {
        val c = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        assertTrue(VideoDecoderPolicy.shouldRetryWithSoftware(c, alreadySoftware = false, alreadyRetried = false))
        assertFalse(VideoDecoderPolicy.shouldRetryWithSoftware(c, alreadySoftware = true, alreadyRetried = false))
        assertFalse(VideoDecoderPolicy.shouldRetryWithSoftware(c, alreadySoftware = false, alreadyRetried = true))
    }

    @Test fun nonDecoderErrorsNeverRetry() {
        assertFalse(VideoDecoderPolicy.shouldRetryWithSoftware(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, false, false))
        assertFalse(VideoDecoderPolicy.shouldRetryWithSoftware(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, false, false))
    }
}
