package com.ghadirb.aimusic.library

import com.ghadirb.aimusic.video.VideoOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedFoldersTest {
    private val ex = setOf("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio")

    @Test fun fileInsideExcludedFolderIsExcluded() =
        assertTrue(ExcludedFolders.isExcluded("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/a.mp3", ex))
    @Test fun folderItselfIsExcluded() = assertTrue(ExcludedFolders.isExcluded("/storage/emulated/0/whatsapp/media/whatsapp audio/", ex))
    @Test fun siblingWithSamePrefixIsNotExcluded() =
        assertFalse(ExcludedFolders.isExcluded("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio Backup/a.mp3", ex))
    @Test fun otherFolderNotExcluded() = assertFalse(ExcludedFolders.isExcluded("/storage/emulated/0/Music/a.mp3", ex))
    @Test fun emptyInputsAreSafe() {
        assertFalse(ExcludedFolders.isExcluded(null, ex))
        assertFalse(ExcludedFolders.isExcluded("", ex))
        assertFalse(ExcludedFolders.isExcluded("/a/b.mp3", emptySet()))
        assertFalse(ExcludedFolders.isExcluded("/a/b.mp3", setOf("", "/")))
    }
    @Test fun backslashesAndCaseAreNormalised() = assertTrue(ExcludedFolders.isExcluded("C:\\Music\\Podcasts\\x.mp3", setOf("c:/music/PODCASTS")))
    @Test fun persianFolderNames() = assertTrue(ExcludedFolders.isExcluded("/storage/emulated/0/Music/پادکست/یک.mp3", setOf("/storage/emulated/0/Music/پادکست")))

    @Test fun stripVolume() {
        assertEquals("music/podcasts", ExcludedFolders.stripVolume("/storage/emulated/0/Music/Podcasts"))
        assertEquals("movies", ExcludedFolders.stripVolume("/storage/1234-ABCD/Movies/"))
        assertEquals("dcim/camera", ExcludedFolders.stripVolume("/sdcard/DCIM/Camera"))
    }
    @Test fun videoRelativeMatch() {
        val v = setOf("/storage/emulated/0/Movies/Family")
        assertTrue(ExcludedFolders.isExcludedRelative("Movies/Family/", v))
        assertTrue(ExcludedFolders.isExcludedRelative("Movies/Family/2025/", v))
        assertFalse(ExcludedFolders.isExcludedRelative("Movies/Other/", v))
        assertFalse(ExcludedFolders.isExcludedRelative("Movies/FamilyTrip/", v))
        assertFalse(ExcludedFolders.isExcludedRelative("", v))
    }

    @Test fun orientationCycles() {
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.AUTO.next())
        assertEquals(VideoOrientation.PORTRAIT, VideoOrientation.LANDSCAPE.next())
        assertEquals(VideoOrientation.AUTO, VideoOrientation.PORTRAIT.next())
    }
    @Test fun suggestedOrientation() {
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.suggestedFor(1920, 1080))
        assertEquals(VideoOrientation.PORTRAIT, VideoOrientation.suggestedFor(720, 1280))
        assertEquals(VideoOrientation.AUTO, VideoOrientation.suggestedFor(0, 0))
    }

    @Test fun persianFolderNamesMatchAcrossArabicLettersAndInvisibleCharacters() {
        // saved with Persian ی/ک, reported by the phone with Arabic ي/ك (and a stray ZWNJ / direction mark)
        val excluded = setOf("/storage/emulated/0/Music/\u0645\u0648\u0633\u06CC\u0642\u06CC \u06A9\u0648\u062F\u06A9")
        val arabicForm = "/storage/emulated/0/Music/\u0645\u0648\u0633\u064A\u0642\u064A \u0643\u0648\u062F\u0643/a.mp3"
        assertTrue(ExcludedFolders.isExcluded(arabicForm, excluded))
        val withInvisible = "/storage/emulated/0/Music/\u0645\u0648\u0633\u06CC\u0642\u06CC\u200C \u06A9\u0648\u062F\u06A9\u200F/a.mp3"
        assertTrue(ExcludedFolders.isExcluded(withInvisible, excluded))
        // a different folder with a similar name is still not excluded
        assertFalse(ExcludedFolders.isExcluded("/storage/emulated/0/Music/\u0645\u0648\u0633\u06CC\u0642\u06CC/a.mp3", excluded))
    }
}
