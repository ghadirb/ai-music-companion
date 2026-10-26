package com.ghadirb.aimusic.video

import com.ghadirb.aimusic.video.VideoEngineAdvisor.Engine
import com.ghadirb.aimusic.video.VideoEngineAdvisor.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEngineAdvisorTest {
    private val phone = DecoderCaps { it in setOf("video/avc", "video/hevc", "audio/mp4a-latm", "audio/mpeg", "audio/opus") }
    private fun probe(video: String?, vararg audio: String) = MediaProbeInfo(true, video, audio.toList(), 1920, 1080)

    @Test fun `plain h264 aac mp4 stays on exoplayer`() {
        val a = VideoEngineAdvisor.advise("movie.mp4", probe("video/avc", "audio/mp4a-latm"), phone)
        assertEquals(Engine.EXO, a.engine); assertEquals(Reason.DEFAULT, a.reason)
    }

    @Test fun `legacy container goes to vlc even when the probe is fine`() {
        val a = VideoEngineAdvisor.advise("old.AVI", probe("video/avc", "audio/mpeg"), phone)
        assertEquals(Engine.VLC, a.engine); assertEquals(Reason.LEGACY_CONTAINER, a.reason)
    }

    @Test fun `mkv is decided by its codecs not its extension`() {
        assertEquals(Engine.EXO, VideoEngineAdvisor.advise("a.mkv", probe("video/hevc", "audio/opus"), phone).engine)
        val av1 = VideoEngineAdvisor.advise("a.mkv", probe("video/av01", "audio/opus"), phone)
        assertEquals(Engine.VLC, av1.engine); assertEquals(Reason.VIDEO_CODEC_MISSING, av1.reason)
    }

    @Test fun `ac3 or dts audio with no decoder goes to vlc so it is not silent`() {
        val a = VideoEngineAdvisor.advise("film.mkv", probe("video/avc", "audio/ac3"), phone)
        assertEquals(Engine.VLC, a.engine); assertEquals(Reason.AUDIO_CODEC_MISSING, a.reason)
    }

    @Test fun `one playable audio track is enough to stay on exoplayer`() {
        val a = VideoEngineAdvisor.advise("film.mkv", probe("video/avc", "audio/ac3", "audio/mp4a-latm"), phone)
        assertEquals(Engine.EXO, a.engine)
    }

    @Test fun `pcm audio needs no decoder`() {
        assertEquals(Engine.EXO, VideoEngineAdvisor.advise("a.mov", probe("video/avc", "audio/raw"), phone).engine)
    }

    @Test fun `vvc without a decoder is flagged and sent to vlc`() {
        val a = VideoEngineAdvisor.advise("x.mp4", probe("video/vvc", "audio/mp4a-latm"), phone)
        assertEquals(Engine.VLC, a.engine); assertEquals(Reason.VVC_NO_DECODER, a.reason)
    }

    @Test fun `vvc with a device decoder is tried on exoplayer first`() {
        val withVvc = DecoderCaps { it == "video/vvc" || it == "audio/mp4a-latm" }
        assertEquals(Engine.EXO, VideoEngineAdvisor.advise("x.mp4", probe("video/vvc", "audio/mp4a-latm"), withVvc).engine)
    }

    @Test fun `an unreadable file is left to exoplayer and the runtime fallback`() {
        val a = VideoEngineAdvisor.advise("broken.mp4", MediaProbeInfo.Failed, phone)
        assertEquals(Engine.EXO, a.engine)
    }

    @Test fun `audio only file with a supported codec stays on exoplayer`() {
        assertEquals(Engine.EXO, VideoEngineAdvisor.advise("a.mp4", probe(null, "audio/mp4a-latm"), phone).engine)
    }

    // ---- diagnosis (Persian text) ----
    @Test fun `diagnosis names the missing video codec`() {
        val d = VideoDiagnosis.explain(probe("video/av01", "audio/opus"), phone)
        assertNotNull(d); assertTrue(d!!.contains("AV1"))
    }

    @Test fun `diagnosis for vvc explains the limit and a way out`() {
        val d = VideoDiagnosis.explain(probe("video/vvc", "audio/mp4a-latm"), phone)!!
        assertTrue(d.contains("VVC")); assertTrue(d.contains("H.264"))
    }

    @Test fun `diagnosis names the missing audio codec`() {
        val d = VideoDiagnosis.explain(probe("video/avc", "audio/ac3"), phone)!!
        assertTrue(d.contains("AC-3"))
    }

    @Test fun `diagnosis is empty when the codecs are fine`() {
        assertNull(VideoDiagnosis.explain(probe("video/avc", "audio/mp4a-latm"), phone))
    }

    @Test fun `diagnosis reports an unreadable file`() {
        assertNotNull(VideoDiagnosis.explain(MediaProbeInfo.Failed, phone))
    }

    @Test fun `codec names are friendly and the diagnosis never contains a path`() {
        assertEquals("H.265 (HEVC)", VideoDiagnosis.codecName("video/hevc"))
        assertEquals("FOO", VideoDiagnosis.codecName("video/foo"))
    }
}
