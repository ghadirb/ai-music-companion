package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {
    private fun ok(input: String) = StreamUrl.parse(input) as StreamUrl.Result.Ok
    private fun bad(input: String) = StreamUrl.parse(input) as StreamUrl.Result.Bad

    @Test fun httpsAccepted() = assertEquals("https://example.com/a/movie.mp4", ok("  https://example.com/a/movie.mp4 ").url)
    @Test fun titleFromLastSegment() = assertEquals("movie.mp4", ok("https://example.com/a/movie.mp4?x=1").title)
    @Test fun titleDecodesPercentEncoding() = assertEquals("فیلم خانگی.mp4", ok("https://e.com/%D9%81%DB%8C%D9%84%D9%85%20%D8%AE%D8%A7%D9%86%DA%AF%DB%8C.mp4").title)
    @Test fun titleFallsBackToHost() = assertEquals("example.com", ok("https://example.com/").title)
    @Test fun httpRejectedWithReason() = assertTrue(bad("http://example.com/a.mp4").reason.contains("https"))
    @Test fun otherSchemesRejected() {
        bad("ftp://example.com/a.mp4"); bad("file:///sdcard/a.mp4"); bad("javascript:alert(1)"); bad("example.com/a.mp4")
    }
    @Test fun emptyAndSpacesRejected() { bad(""); bad("   "); bad("https://exa mple.com/a.mp4") }
    @Test fun missingHostRejected() { bad("https:///a.mp4") }
    @Test fun idsAreNegativeAndStable() {
        val a = StreamUrl.idFor("https://e.com/a.mp4")
        assertTrue(a < 0)
        assertEquals(a, StreamUrl.idFor("https://e.com/a.mp4"))
        assertTrue(StreamUrl.idFor("") < 0)
    }
    @Test fun registeredEntryIsFoundOnlyByNegativeId() {
        val v = StreamUrl.register("https://e.com/z.mp4", "z.mp4")
        assertEquals(listOf(v.id), StreamUrl.find(listOf(v.id, 5L, 6L)).map { it.id })
        assertTrue(StreamUrl.isStream(v))
        assertEquals("https://e.com/z.mp4", v.contentUri)
    }
}
