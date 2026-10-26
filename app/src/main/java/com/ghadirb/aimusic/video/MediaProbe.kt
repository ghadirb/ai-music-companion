package com.ghadirb.aimusic.video

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * What a media file really contains, read from its tracks - never guessed from the file extension (a container
 * says nothing about the codec inside it). Only codec names and sizes are kept: no path, no link, no file content.
 */
data class MediaProbeInfo(
    /** False when the platform could not open/parse the file at all (legacy container, damaged file ...). */
    val ok: Boolean,
    val videoMime: String? = null,
    val audioMimes: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val hasEmbeddedSubtitles: Boolean = false,
) {
    companion object { val Failed = MediaProbeInfo(ok = false) }
}

/** Which codecs this phone can decode (hardware or software). Tiny interface so decisions are unit-testable. */
fun interface DecoderCaps {
    fun canDecode(mime: String): Boolean
}

/** [DecoderCaps] backed by the platform's codec list. */
class DeviceDecoderCaps : DecoderCaps {
    private val decodable: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.toList() }
                .map { it.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    override fun canDecode(mime: String): Boolean = mime.lowercase() in decodable
}

object MediaProbe {
    /** Reads the track list with the platform [MediaExtractor]. Blocking (can touch disk/network): call off the main thread. */
    fun probe(context: Context, uri: Uri): MediaProbeInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context.applicationContext, uri, null)
            var video: String? = null
            var w = 0
            var h = 0
            var subs = false
            val audio = mutableListOf<String>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
                when {
                    mime.startsWith("video/") && video == null -> {
                        video = mime
                        w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                        h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    }
                    mime.startsWith("audio/") -> audio += mime
                    mime.startsWith("text/") || mime == "application/x-subrip" || mime == "application/x-quicktime-tx3g" -> subs = true
                }
            }
            MediaProbeInfo(ok = true, videoMime = video, audioMimes = audio, width = w, height = h, hasEmbeddedSubtitles = subs)
        } catch (_: Exception) {
            MediaProbeInfo.Failed
        } finally {
            runCatching { extractor.release() }
        }
    }
}
