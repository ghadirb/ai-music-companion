package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleShiftTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,500\nسلام\nدنیا\n\n2\n00:01:00,250 --> 00:01:02,000\nSecond\n"

    @Test fun clockParsing() {
        assertEquals(3_723_456L, SubtitleShift.parseClock("01:02:03,456"))
        assertEquals(1_500L, SubtitleShift.parseClock("00:00:01.5"))
        assertNull(SubtitleShift.parseClock("garbage"))
    }
    @Test fun clockFormatting() {
        assertEquals("01:02:03,456", SubtitleShift.formatSrt(3_723_456))
        assertEquals("00:00:00.000", SubtitleShift.formatVtt(-5))
    }
    @Test fun srtForwardShift() {
        val out = SubtitleShift.shift(srt, "srt", 2_000)!!
        assertTrue(out.contains("00:00:03,000 --> 00:00:05,500"))
        assertTrue(out.contains("00:01:02,250 --> 00:01:04,000"))
        assertTrue(out.contains("سلام\nدنیا"))      // multi-line Persian text untouched
    }
    @Test fun srtBackwardShiftClampsAndDropsEarlyCues() {
        val out = SubtitleShift.shift(srt, "srt", -2_000)!!
        assertTrue(out.contains("00:00:00,000 --> 00:00:01,500"))   // start clamped at 0, still visible
        assertTrue(out.startsWith("1\n"))
        val dropped = SubtitleShift.shift(srt, "srt", -4_000)!!     // first cue ends at 3.5s -> gone
        assertFalse(dropped.contains("سلام"))
        assertTrue(dropped.startsWith("1\n00:00:56,250"))            // and cues are renumbered
    }
    @Test fun srtZeroShiftKeepsContent() {
        val out = SubtitleShift.shift(srt, "srt", 0)!!
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,500"))
        assertTrue(out.contains("Second"))
    }
    @Test fun bomAndWindowsLineEndings() {
        val out = SubtitleShift.shift("\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n", "SRT", 500)!!
        assertTrue(out.contains("00:00:01,500 --> 00:00:02,500"))
    }
    @Test fun vttShiftKeepsHeaderSettingsAndShortClock() {
        val vtt = "WEBVTT\n\nintro\n00:01.000 --> 00:02.000 align:start\nHello\n"
        val out = SubtitleShift.shift(vtt, "vtt", 1_000)!!
        assertTrue(out.startsWith("WEBVTT"))
        assertTrue(out.contains("intro\n00:00:02.000 --> 00:00:03.000 align:start\nHello"))
    }
    @Test fun assShiftsDialogueOnly() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Hello, world\n"
        val out = SubtitleShift.shift(ass, "ass", 1_500)!!
        assertTrue(out.contains("Dialogue: 0,0:00:02.50,0:00:05.00,Default,,0,0,0,,Hello, world"))   // comma in text survives
        assertTrue(out.contains("Title: x"))
        assertTrue(out.contains("Format: Layer, Start, End"))
    }
    @Test fun assDropsCueEndingBeforeZero() {
        val ass = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Bye\n"
        assertFalse(SubtitleShift.shift(ass, "ssa", -5_000)!!.contains("Bye"))
    }
    @Test fun unsupportedFormatReturnsNull() = assertNull(SubtitleShift.shift("x", "sub", 100))
    @Test fun garbageSrtGivesEmptyResultNotCrash() = assertNotNull(SubtitleShift.shift("nothing here", "srt", 100))
}
