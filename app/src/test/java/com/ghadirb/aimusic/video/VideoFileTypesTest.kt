package com.ghadirb.aimusic.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFileTypesTest {
    @Test fun extensionIsCaseInsensitive() = assertEquals("mkv", VideoFileTypes.extensionOf("Movie.Part1.MKV"))
    @Test fun noExtension() = assertEquals("", VideoFileTypes.extensionOf("README"))
    @Test fun knownVideoNames() {
        assertTrue(VideoFileTypes.isVideoName("a.mkv"))
        assertTrue(VideoFileTypes.isVideoName("b.TS"))
        assertTrue(VideoFileTypes.isVideoName("c.avi"))
        assertFalse(VideoFileTypes.isVideoName("d.mp3"))
        assertFalse(VideoFileTypes.isVideoName("e.srt"))
    }
    @Test fun mimeDecides() {
        assertTrue(VideoFileTypes.isUnclassifiedVideo("noext", "video/x-matroska"))
        assertFalse(VideoFileTypes.isUnclassifiedVideo("notes.txt", "text/plain"))
        assertFalse(VideoFileTypes.isUnclassifiedVideo("x", null))
    }
}
