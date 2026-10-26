package com.ghadirb.aimusic.video

import androidx.media3.common.PlaybackException
import java.util.Locale

/**
 * Decides when the built-in ExoPlayer engine is not enough and the libVLC engine (LGPL, bundled
 * ffmpeg-based demuxers/decoders: AVI, WMV, RMVB, VOB, DTS/AC3 audio ...) should play the file.
 * Pure Kotlin so it is unit tested on the JVM.
 */
object VlcFallbackPolicy {

    /** Containers ExoPlayer cannot demux at all - go straight to libVLC. */
    private val vlcFirstExtensions = setOf(
        "avi", "wmv", "asf", "rm", "rmvb", "divx", "xvid", "vob", "ogm", "ogv",
        "dat", "f4v", "mxf", "nsv", "wtv", "mod", "tod", "amv"
    )

    fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun prefersVlc(displayName: String): Boolean = extensionOf(displayName) in vlcFirstExtensions

    /**
     * ExoPlayer failed. Fall back to libVLC for container problems, and for decoder problems once
     * the Android software decoders were already tried. Missing file / permission errors are not
     * an engine problem, so they never trigger it.
     */
    fun shouldFallBackToVlc(errorCode: Int, softwareDecodersTried: Boolean): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> false
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> true
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> softwareDecodersTried
        else -> false
    }
}
