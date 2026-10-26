package com.ghadirb.aimusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PublicMusicStoreTest {
    private val root = File("/data/app/Navasa")

    @Test fun `file directly in the download folder goes to Music Navasa`() {
        assertEquals("Music/Navasa", PublicMusicStore.relativePath(File(root, "a.mp3"), root))
    }

    @Test fun `extracted collection keeps its own sub-folder`() {
        assertEquals("Music/Navasa/Golden hits", PublicMusicStore.relativePath(File(File(root, "Golden hits"), "a.mp3"), root))
    }

    @Test fun `unsafe characters in a collection folder name are replaced`() {
        assertEquals("Music/Navasa/a_b", PublicMusicStore.relativePath(File(File(root, "a:b"), "a.mp3"), root))
    }

    @Test fun `mime types follow the extension`() {
        assertEquals("audio/mpeg", PublicMusicStore.mimeFor("x.MP3"))
        assertEquals("audio/mp4", PublicMusicStore.mimeFor("x.m4a"))
        assertEquals("audio/flac", PublicMusicStore.mimeFor("x.flac"))
        assertEquals("audio/mpeg", PublicMusicStore.mimeFor("x.unknown"))
    }

    @Test fun `only audio files count as publishable`() {
        assertTrue(PublicMusicStore.isAudio("song.mp3"))
        assertTrue(PublicMusicStore.isAudio("song.OGG"))
        assertFalse(PublicMusicStore.isAudio("song.mp3.part"))
        assertFalse(PublicMusicStore.isAudio("archive.zip"))
        assertFalse(PublicMusicStore.isAudio("noext"))
    }

    @Test fun `legacy scan finds audio in the root and one folder deep only`() {
        val dir = java.nio.file.Files.createTempDirectory("navasa").toFile()
        try {
            File(dir, "a.mp3").writeText("x")
            File(dir, "b.zip").writeText("x")
            File(dir, "c.mp3.part").writeText("x")
            File(dir, "col").mkdirs(); File(dir, "col/d.flac").writeText("x")
            val names = PublicMusicStore.legacyFiles(dir).map { it.name }.sorted()
            assertEquals(listOf("a.mp3", "d.flac"), names)
        } finally { dir.deleteRecursively() }
    }
}
