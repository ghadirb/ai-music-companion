package com.ghadirb.aimusic.video

/**
 * File types that count as "video" even when Android's media database failed to classify them
 * (typical for .mkv/.ts/.avi/.wmv copied from other apps). Pure helper, unit-tested.
 */
object VideoFileTypes {
    val EXTENSIONS: Set<String> = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "3g2", "m4v", "ts", "m2ts", "mts", "flv", "wmv",
        "mpg", "mpeg", "vob", "ogv", "rmvb", "asf"
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideoName(name: String): Boolean = extensionOf(name) in EXTENSIONS

    fun isVideoMime(mime: String?): Boolean = mime?.lowercase()?.startsWith("video/") == true

    /** True when an unclassified MediaStore file row (media type NONE) is really a video. */
    fun isUnclassifiedVideo(name: String, mime: String?): Boolean = isVideoMime(mime) || isVideoName(name)
}
