package com.ghadirb.aimusic.video

import androidx.media3.common.PlaybackException
import java.util.Locale

/**
 * Pure decision logic for choosing video/audio decoders (no Android framework calls, unit-testable).
 *
 * Idea (like MX Player's "S/W decoder"): when a device's hardware decoder fails or is missing,
 * fall back to the software decoders that Android itself ships (OMX.google.* / c2.android.*).
 * No third-party native library is involved, so there is no extra licence obligation.
 */
object VideoDecoderPolicy {

    /** True for decoders implemented in software by the platform / vendor. */
    fun isSoftwareDecoder(codecName: String): Boolean {
        val n = codecName.lowercase(Locale.ROOT)
        return n.startsWith("omx.google.") ||
            n.startsWith("c2.android.") ||
            n.startsWith("c2.google.") ||
            n.endsWith(".sw") || n.contains(".sw.") ||
            n.contains("sw.dec") || n.contains("software")
    }

    /** Stable reorder: software decoders first when [preferSoftware], otherwise untouched. */
    fun <T> order(items: List<T>, preferSoftware: Boolean, nameOf: (T) -> String): List<T> =
        if (!preferSoftware) items
        else items.sortedBy { if (isSoftwareDecoder(nameOf(it))) 0 else 1 }

    /** Errors for which retrying with software decoders is worthwhile. */
    fun isDecoderFailure(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    /**
     * Automatic retry happens once per video and only if software decoding is not already on,
     * so a genuinely broken file can never cause a retry loop.
     */
    fun shouldRetryWithSoftware(errorCode: Int, alreadySoftware: Boolean, alreadyRetried: Boolean): Boolean =
        isDecoderFailure(errorCode) && !alreadySoftware && !alreadyRetried
}
