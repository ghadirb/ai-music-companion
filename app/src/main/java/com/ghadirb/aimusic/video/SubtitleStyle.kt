package com.ghadirb.aimusic.video

/** How subtitles look in the video player. Pure data + mapping (JVM unit tested); saved by [SubtitleStyleStore]. */
data class SubtitleStyle(
    val size: Int = 1,
    val color: Int = 0,
    val background: Int = 1,
    val outline: Boolean = true
) {
    fun sanitized() = SubtitleStyle(
        size.coerceIn(0, SIZE_LABELS.lastIndex), color.coerceIn(0, COLORS.lastIndex),
        background.coerceIn(0, BG_LABELS.lastIndex), outline
    )

    /** Media3 fractional text size (its default is 0.0533). */
    fun fractionalTextSize(): Float = SIZES[sanitized().size]

    /** ARGB text colour. */
    fun textColor(): Int = COLORS[sanitized().color]

    /** ARGB background behind the text. */
    fun backgroundColor(): Int = BACKGROUNDS[sanitized().background]

    /** libVLC reads these only when it is created, so they apply the next time the VLC engine starts. */
    fun vlcArgs(): List<String> {
        val s = sanitized()
        val rgb = COLORS[s.color] and 0xFFFFFF
        val opacity = (BACKGROUNDS[s.background].toLong() and 0xFFFFFFFFL) ushr 24
        return listOf(
            "--freetype-rel-fontsize=${VLC_SIZES[s.size]}",
            "--freetype-color=$rgb",
            "--freetype-background-opacity=$opacity",
            "--freetype-background-color=0",
            "--freetype-outline-thickness=${if (s.outline) 4 else 0}"
        )
    }

    companion object {
        val SIZE_LABELS = listOf("کوچک", "متوسط", "بزرگ", "خیلی بزرگ")
        val COLOR_LABELS = listOf("سفید", "زرد", "فیروزه‌ای", "سبز")
        val BG_LABELS = listOf("بدون پس‌زمینه", "نیمه‌شفاف", "مشکی")

        private val SIZES = listOf(0.04f, 0.0533f, 0.07f, 0.09f)
        /** In VLC a SMALLER number means bigger text. */
        private val VLC_SIZES = listOf(20, 16, 12, 8)
        private val COLORS = listOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF4DD0E1.toInt(), 0xFF8BC34A.toInt())
        private val BACKGROUNDS = listOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt())
    }
}
