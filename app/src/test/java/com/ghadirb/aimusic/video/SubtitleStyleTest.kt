package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    @Test fun defaultMatchesMedia3Default() = assertEquals(0.0533f, SubtitleStyle().fractionalTextSize(), 0.0001f)
    @Test fun sizesGrow() {
        val sizes = (0..3).map { SubtitleStyle(size = it).fractionalTextSize() }
        assertEquals(sizes.sorted(), sizes)
        assertEquals(4, sizes.distinct().size)
    }
    @Test fun outOfRangeValuesAreClamped() {
        val s = SubtitleStyle(size = 99, color = -4, background = 50).sanitized()
        assertEquals(3, s.size); assertEquals(0, s.color); assertEquals(2, s.background)
    }
    @Test fun defaultTextIsWhiteHalfTransparentBlackBackground() {
        val s = SubtitleStyle()
        assertEquals(0xFFFFFFFF.toInt(), s.textColor())
        assertEquals(0x99000000.toInt(), s.backgroundColor())
    }
    @Test fun vlcArgsReflectStyle() {
        val args = SubtitleStyle(size = 3, color = 1, background = 2, outline = false).vlcArgs()
        assertTrue("--freetype-rel-fontsize=8" in args)          // bigger text = smaller VLC number
        assertTrue("--freetype-color=${0xFFEB3B}" in args)
        assertTrue("--freetype-background-opacity=255" in args)
        assertTrue("--freetype-outline-thickness=0" in args)
    }
    @Test fun noBackgroundMeansZeroOpacity() = assertTrue("--freetype-background-opacity=0" in SubtitleStyle(background = 0).vlcArgs())
    @Test fun labelsMatchChoices() {
        assertEquals(4, SubtitleStyle.SIZE_LABELS.size); assertEquals(4, SubtitleStyle.COLOR_LABELS.size); assertEquals(3, SubtitleStyle.BG_LABELS.size)
    }
}
