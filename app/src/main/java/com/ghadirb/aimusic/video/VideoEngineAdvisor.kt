package com.ghadirb.aimusic.video

import java.util.Locale

/**
 * Chooses which engine starts a video, from what the file REALLY contains (see [MediaProbe]) and what this phone
 * can decode - not from the extension alone. Pure Kotlin: unit tested on the JVM.
 *
 * Engines: ExoPlayer (Media3) is the default; libVLC (software ffmpeg-based decoders) takes over when the phone
 * has no decoder for the video or audio codec, or the container is a legacy one. The existing runtime fallback
 * ([VlcFallbackPolicy]) stays as the safety net when a choice made here turns out wrong; it is bounded
 * (software retry once, then one switch to libVLC, then an error message) so nothing loops.
 */
object VideoEngineAdvisor {
    enum class Engine { EXO, VLC }

    enum class Reason {
        DEFAULT,
        /** AVI/WMV/RMVB/VOB ... - ExoPlayer cannot demux these. */
        LEGACY_CONTAINER,
        /** The phone has no decoder for the video codec. */
        VIDEO_CODEC_MISSING,
        /** The phone has no decoder for an audio track (AC-3, DTS ...): ExoPlayer would play it silently. */
        AUDIO_CODEC_MISSING,
        /** H.266/VVC and the phone has no VVC decoder: libVLC is the only candidate. */
        VVC_NO_DECODER,
    }

    data class Advice(val engine: Engine, val reason: Reason)

    const val MIME_VVC = "video/vvc"

    /** Audio that needs no codec at all. */
    private val noCodecNeeded = setOf("audio/raw")

    fun isVvc(mime: String?): Boolean = mime?.lowercase(Locale.ROOT) == MIME_VVC

    fun advise(displayName: String, probe: MediaProbeInfo, caps: DecoderCaps): Advice {
        if (VlcFallbackPolicy.prefersVlc(displayName)) return Advice(Engine.VLC, Reason.LEGACY_CONTAINER)
        // Unreadable by the platform parser: let ExoPlayer try (its parsers differ); the runtime fallback covers the rest.
        if (!probe.ok) return Advice(Engine.EXO, Reason.DEFAULT)

        val video = probe.videoMime
        if (video != null && !caps.canDecode(video)) {
            return Advice(Engine.VLC, if (isVvc(video)) Reason.VVC_NO_DECODER else Reason.VIDEO_CODEC_MISSING)
        }
        val audioMissing = probe.audioMimes.isNotEmpty() &&
            probe.audioMimes.none { it in noCodecNeeded || caps.canDecode(it) }
        if (audioMissing) return Advice(Engine.VLC, Reason.AUDIO_CODEC_MISSING)
        return Advice(Engine.EXO, Reason.DEFAULT)
    }
}
