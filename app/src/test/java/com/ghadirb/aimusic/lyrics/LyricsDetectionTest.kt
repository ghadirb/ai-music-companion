package com.ghadirb.aimusic.lyrics

import com.ghadirb.aimusic.analysis.LyricsAnalyzer.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LyricsDetectionTest {

    // ---- which line is being sung ----
    private val lines = listOf(LrcLine(1000, "a"), LrcLine(5000, "b"), LrcLine(9000, "c"))

    @Test fun activeLineFollowsThePosition() {
        assertEquals(-1, LyricsTimeline.activeIndex(lines, 0, leadMs = 0))
        assertEquals(0, LyricsTimeline.activeIndex(lines, 1000, leadMs = 0))
        assertEquals(0, LyricsTimeline.activeIndex(lines, 4999, leadMs = 0))
        assertEquals(1, LyricsTimeline.activeIndex(lines, 5000, leadMs = 0))
        assertEquals(2, LyricsTimeline.activeIndex(lines, 999_999, leadMs = 0))
        assertEquals(-1, LyricsTimeline.activeIndex(emptyList(), 5))
    }

    @Test fun smallLeadSwitchesJustBeforeTheLineStarts() {
        assertEquals(1, LyricsTimeline.activeIndex(lines, 4900, leadMs = 200))
        assertEquals(0, LyricsTimeline.activeIndex(lines, 4700, leadMs = 200))
    }

    // ---- same-name file matching ----
    @Test fun matchesSameNameIgnoringCasePersianVariantsAndNumbering() {
        val files = listOf("Other.lrc", "دلتنگی.LRC", "readme.txt", "my song.lrc")
        assertEquals("my song.lrc", LyricsFileMatcher.pick(files, LyricsFileMatcher.keysFor("01 - My Song", "x", null)))
        assertEquals("my song.lrc", LyricsFileMatcher.pick(files, LyricsFileMatcher.keysFor("MY SONG", "x", null)))
        assertEquals("دلتنگی.LRC", LyricsFileMatcher.pick(files, LyricsFileMatcher.keysFor("دلتنگي", "t", "a"))) // Arabic ي
        assertNull(LyricsFileMatcher.pick(files, LyricsFileMatcher.keysFor("nothing here", "zzz", null)))
    }

    @Test fun fallsBackToArtistDashTitleAndTitle() {
        assertEquals("Ali - Song.lrc", LyricsFileMatcher.pick(listOf("Ali - Song.lrc"), LyricsFileMatcher.keysFor("weird-file-name", "Song", "Ali")))
        assertEquals("Song.lrc", LyricsFileMatcher.pick(listOf("Song.lrc"), LyricsFileMatcher.keysFor("weird-file-name", "Song", "Unknown artist")))
    }

    // ---- embedded tags ----
    private fun synchsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun int32be(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun id3(version: Int, vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (version == 4) synchsafe(data.size) else int32be(data.size))
            body.write(byteArrayOf(0, 0))
            body.write(data)
        }
        body.write(ByteArray(20)) // padding
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray()); out.write(byteArrayOf(version.toByte(), 0, 0)); out.write(synchsafe(body.size()))
        out.write(body.toByteArray())
        out.write(byteArrayOf(0x49, 0x44, 0x33)) // audio bytes follow
        return out.toByteArray()
    }

    private fun uslt(encoding: Int, text: ByteArray, wideDescriptor: Boolean = false): ByteArray =
        byteArrayOf(encoding.toByte(), 'f'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte()) +
            (if (wideDescriptor) byteArrayOf(0, 0) else byteArrayOf(0)) + text

    @Test fun readsUtf8UsltFromId3v24AndSkipsOtherFrames() {
        val lrc = "[00:01.00]سلام\n[00:05.00]دنیا"
        val tag = id3(4, "APIC" to ByteArray(5000) { 7 }, "TIT2" to byteArrayOf(3, 'x'.code.toByte()), "USLT" to uslt(3, lrc.toByteArray()))
        assertEquals(lrc, EmbeddedLyricsReader.read(ByteArrayInputStream(tag)))
    }

    @Test fun readsUtf16UsltFromId3v23() {
        val text = "[00:02.00]متن آهنگ"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val tag = id3(3, "USLT" to uslt(1, bom + text.toByteArray(Charsets.UTF_16LE), wideDescriptor = true).let { d ->
            // descriptor is an (empty) UTF-16 string: BOM-less terminator only
            d
        })
        assertEquals(text, EmbeddedLyricsReader.read(ByteArrayInputStream(tag)))
    }

    @Test fun decodesLegacyWindows1256UsltAndPrefersSyncedText() {
        val legacy = "[00:01.00]سلام".toByteArray(java.nio.charset.Charset.forName("windows-1256"))
        val plain = "just plain words".toByteArray()
        val tag = id3(3, "USLT" to uslt(0, plain), "USLT" to uslt(0, legacy))
        assertEquals("[00:01.00]سلام", EmbeddedLyricsReader.read(ByteArrayInputStream(tag)))
    }

    @Test fun noLyricsOrGarbageGivesNull() {
        assertNull(EmbeddedLyricsReader.read(ByteArrayInputStream(id3(4, "TIT2" to byteArrayOf(3, 'x'.code.toByte())))))
        assertNull(EmbeddedLyricsReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))))
        assertNull(EmbeddedLyricsReader.read(ByteArrayInputStream(ByteArray(0))))
        assertNull(EmbeddedLyricsReader.read(ByteArrayInputStream("ID3".toByteArray() + byteArrayOf(4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F))))
    }

    @Test fun readsFlacVorbisLyricsComment() {
        fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val vendor = "test".toByteArray()
        val comment = "LYRICS=[00:01.00]hello".toByteArray()
        val block = le(vendor.size) + vendor + le(2) + le(12) + "TITLE=Some Ti".toByteArray().copyOf(12) + le(comment.size) + comment
        val streamInfo = ByteArray(34)
        val flac = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf(0x84.toByte(), 0, (block.size shr 8).toByte(), block.size.toByte()) + block
        assertEquals("[00:01.00]hello", EmbeddedLyricsReader.read(ByteArrayInputStream(flac)))
    }

    @Test fun parsedEmbeddedTextGoesThroughTheSameLrcParser() {
        val text = EmbeddedLyricsReader.read(ByteArrayInputStream(id3(4, "USLT" to uslt(3, "[00:03.00]الف\n[00:07.00]ب".toByteArray()))))!!
        val parsed = LrcParser.parse(text)
        assertTrue(parsed.synced)
        assertEquals(listOf(3000L, 7000L), parsed.lines.map { it.timeMs })
    }
}
