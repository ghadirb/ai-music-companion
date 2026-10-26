package com.ghadirb.aimusic.video

/** Rotation lock for the video player: auto -> landscape -> portrait -> auto. */
enum class VideoOrientation(val label: String) {
    AUTO("چرخش خودکار"), LANDSCAPE("قفل افقی"), PORTRAIT("قفل عمودی");

    fun next(): VideoOrientation = values()[(ordinal + 1) % values().size]

    /** The mode a video would naturally want when first opened: wide videos landscape, tall videos portrait. */
    companion object {
        fun suggestedFor(width: Int, height: Int): VideoOrientation = when {
            width <= 0 || height <= 0 -> AUTO
            width >= height -> LANDSCAPE
            else -> PORTRAIT
        }
    }
}
