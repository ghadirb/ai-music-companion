package com.ghadirb.aimusic.video

/**
 * How the picture is fitted to the screen. Pure (no Android types) so the cycle is unit tested.
 * FIT keeps the whole picture (black bars), FILL stretches to the screen, ZOOM crops to fill without distortion.
 */
enum class VideoAspect(val label: String) {
    FIT("متناسب"),
    FILL("کشیده"),
    ZOOM("پرشده");

    fun next(): VideoAspect = values()[(ordinal + 1) % values().size]
}
