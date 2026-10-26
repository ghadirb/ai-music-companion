package com.ghadirb.aimusic.video

import java.util.Locale

/**
 * A plain-Persian explanation of WHY a video cannot play, built from the file's real codecs and this phone's
 * decoders. Shown under the error message only when playback has failed. Contains codec names only - never the
 * file path or link. Pure Kotlin: unit tested on the JVM.
 */
object VideoDiagnosis {

    private val names = mapOf(
        "video/avc" to "H.264", "video/hevc" to "H.265 (HEVC)", "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8", "video/av01" to "AV1", "video/vvc" to "H.266 (VVC)",
        "video/mp4v-es" to "MPEG-4", "video/mpeg2" to "MPEG-2", "video/3gpp" to "H.263",
        "audio/mp4a-latm" to "AAC", "audio/mpeg" to "MP3", "audio/opus" to "Opus", "audio/flac" to "FLAC",
        "audio/ac3" to "AC-3", "audio/eac3" to "E-AC-3", "audio/vnd.dts" to "DTS", "audio/vnd.dts.hd" to "DTS-HD",
        "audio/true-hd" to "Dolby TrueHD", "audio/vorbis" to "Vorbis", "audio/raw" to "PCM",
    )

    fun codecName(mime: String): String = names[mime.lowercase(Locale.ROOT)] ?: mime.substringAfter('/').uppercase(Locale.ROOT)

    /** Null when the probe shows nothing wrong with the codecs (the failure is then something else, e.g. a damaged file). */
    fun explain(probe: MediaProbeInfo, caps: DecoderCaps): String? {
        if (!probe.ok) {
            return "این فایل با تحلیل‌گر اندروید خوانده نشد؛ ممکن است قالب قدیمی یا فایل ناقص/خراب باشد."
        }
        val video = probe.videoMime
        if (video != null && !caps.canDecode(video)) {
            return if (VideoEngineAdvisor.isVvc(video)) {
                "این ویدئو با کدک H.266 (VVC) ساخته شده و این گوشی رمزگشای آن را ندارد. " +
                    "موتورهای نواسا (ExoPlayer و VLC) هنوز VVC را به‌صورت نرم‌افزاری پخش نمی‌کنند. " +
                    "برای دیدن آن، فایل را روی رایانه به MP4 با H.264 یا H.265 تبدیل کنید یا نسخه‌ی دیگری از آن را بگیرید."
            } else {
                "کدک تصویر این فایل (${codecName(video)}) روی این گوشی رمزگشا ندارد. " +
                    "فایل را به MP4 با H.264 تبدیل کنید یا نسخه‌ی دیگری از آن را بگیرید."
            }
        }
        val missingAudio = probe.audioMimes.filter { it != "audio/raw" && !caps.canDecode(it) }
        if (missingAudio.isNotEmpty() && missingAudio.size == probe.audioMimes.count { it != "audio/raw" }) {
            return "کدک صدای این فایل (${missingAudio.distinct().joinToString("، ") { codecName(it) }}) روی این گوشی رمزگشا ندارد."
        }
        if (VideoEngineAdvisor.isVvc(video)) {
            return "این ویدئو H.266 (VVC) است. رمزگشای آن در گوشی هست ولی پخش موفق نشد؛ پشتیبانی VVC هنوز آزمایشی است."
        }
        return null
    }
}
