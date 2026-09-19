package com.ghadirb.aimusic.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    private val persian = "[ar:خواننده]\n[ti:نمونه]\n[00:12.50]سلام دنیا\n[00:20.00]این یک آزمایش است\n[01:05:30]خط سوم"

    @Test fun parsesPersianSyncedLyrics() {
        val parsed = LrcParser.parse(persian)
        assertTrue(parsed.synced)
        assertEquals(3, parsed.lines.size)
        assertEquals(12_500L, parsed.lines[0].timeMs)
        assertEquals("سلام دنیا", parsed.lines[0].text)
        assertEquals(65_300L, parsed.lines[2].timeMs) // [mm:ss:xx] variant
    }

    @Test fun supportsMultipleStampsPerLineAndSorts() {
        val parsed = LrcParser.parse("[00:30.00][00:10.00]تکرار\n[00:20.00]وسط")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), parsed.lines.map { it.timeMs })
    }

    @Test fun appliesOffsetHeader() {
        val parsed = LrcParser.parse("[offset:+500]\n[00:10.00]خط")
        assertEquals(9_500L, parsed.lines.single().timeMs)
    }

    @Test fun stripsEnhancedWordTimestampsAndSkipsEmptyLines() {
        val parsed = LrcParser.parse("[00:01.00]<00:01.00>سلام <00:01.50>دوست\n[00:05.00]")
        assertEquals("سلام دوست", parsed.lines.single().text)
    }

    @Test fun handlesCrLfAndBom() {
        val parsed = LrcParser.parse("\uFEFF[00:01.00]الف\r\n[00:02.00]ب\r\n")
        assertEquals(2, parsed.lines.size)
    }

    @Test fun plainLyricsWithoutStampsAreReturnedUnsynced() {
        val parsed = LrcParser.parse("[ti:x]\nخط اول\nخط دوم")
        assertFalse(parsed.synced)
        assertEquals(listOf("خط اول", "خط دوم"), parsed.lines.map { it.text })
    }

    @Test fun emptyOrGarbageGivesEmpty() {
        assertTrue(LrcParser.parse("").isEmpty)
        assertTrue(LrcParser.parse("[ti:only header]").isEmpty)
    }

    @Test fun decodesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01.00]سلام".toByteArray(Charsets.UTF_8)
        assertEquals("سلام", LrcParser.parse(bytes).lines.single().text)
    }

    @Test fun decodesUtf16WithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[00:01.00]سلام".toByteArray(Charsets.UTF_16LE)
        assertEquals("سلام", LrcParser.parse(bytes).lines.single().text)
    }

    @Test fun decodesLegacyWindows1256() {
        val bytes = "[00:01.00]سلام".toByteArray(java.nio.charset.Charset.forName("windows-1256"))
        assertEquals("سلام", LrcParser.parse(bytes).lines.single().text)
    }
}
