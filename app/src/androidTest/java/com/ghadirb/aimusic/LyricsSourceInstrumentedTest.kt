package com.ghadirb.aimusic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.lyrics.LyricsOrigin
import com.ghadirb.aimusic.lyrics.LyricsSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the REAL Storage Access Framework code path of LyricsSource (DocumentsContract queries, recursion,
 * name matching, disk-cached index) against a fake DocumentsProvider that mimics ExternalStorageProvider.
 */
@RunWith(AndroidJUnit4::class)
class LyricsSourceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var tree: Uri

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "fakedocs").apply { deleteRecursively(); mkdirs() }
        File(root, "Music/Persian").mkdirs()
        File(root, "Music/Rock").mkdirs()
        File(root, "Music/Rock/Deep Song.lrc").writeText("[00:01.00]hello\n[00:05.00]world")
        File(root, "Music/Rock/Deep Song.mp3").writeText("not audio")
        File(root, "Music/Persian/دلتنگی.LRC").writeBytes("[00:02.00]سلام\n[00:06.00]دنیا".toByteArray(Charsets.UTF_8))
        File(root, "Music/Persian/legacy.lrc").writeBytes("[00:01.00]سلام".toByteArray(java.nio.charset.Charset.forName("windows-1256")))
        File(root, "Music/readme.txt").writeText("ignore me")
        tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, "root")
        context.getSharedPreferences("lyrics_source", Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "lyrics_index_v1.json").delete()
    }

    @After fun tearDown() {
        root.deleteRecursively()
        context.getSharedPreferences("lyrics_source", Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "lyrics_index_v1.json").delete()
    }

    private fun track(title: String, artist: String = "A", folder: String? = null) =
        TrackEntity(id = 900L + title.length, path = "content://nowhere/$title", title = title, artist = artist, album = "B", durationMs = 1000, folderPath = folder)

    @Test fun indexesTheGrantedFolderRecursivelyAndFindsSameNameLyrics() = runBlocking {
        val source = LyricsSource(context)
        assertTrue(source.addFolder(tree, persistPermission = false))
        val stats = source.refreshIndex()
        assertEquals(1, stats.folders)
        assertEquals(3, stats.files) // Deep Song.lrc, دلتنگی.LRC, legacy.lrc (readme.txt ignored)

        val loaded = source.load(track("Deep Song"))
        assertNotNull(loaded)
        assertEquals(LyricsOrigin.SIDECAR, loaded!!.origin)
        assertTrue(loaded.parsed.synced)
        assertEquals(listOf(1000L, 5000L), loaded.parsed.lines.map { it.timeMs })
    }

    @Test fun matchesPersianNamesAcrossArabicLetterVariantsAndCase() = runBlocking {
        val source = LyricsSource(context)
        source.addFolder(tree, persistPermission = false)
        source.refreshIndex()
        val persian = source.load(track("دلتنگي")) // Arabic yeh typed by the user's keyboard/metadata
        assertNotNull(persian)
        assertEquals("سلام", persian!!.parsed.lines.first().text)
    }

    @Test fun decodesLegacyWindows1256LyricsFiles() = runBlocking {
        val source = LyricsSource(context)
        source.addFolder(tree, persistPermission = false)
        source.refreshIndex()
        assertEquals("سلام", source.load(track("legacy"))!!.parsed.lines.single().text)
    }

    @Test fun unknownSongIsNotFoundAndDiagnosisExplainsWhy() {
        val source = LyricsSource(context)
        source.addFolder(tree, persistPermission = false)
        source.refreshIndex()
        assertNull(runBlocking { source.load(track("No Such Song")) })
        val text = source.diagnose(track("No Such Song"))
        assertTrue(text, text.contains("3")) // number of indexed .lrc files is reported
        assertTrue(text, text.contains("فایل هم‌نامی"))
    }

    @Test fun indexIsCachedOnDiskAndSurvivesAFreshInstance() = runBlocking {
        val first = LyricsSource(context)
        first.addFolder(tree, persistPermission = false)
        first.refreshIndex()
        assertTrue(File(context.filesDir, "lyrics_index_v1.json").isFile)
        assertEquals(3, LyricsSource(context).indexedFileCount())
    }

    @Test fun withoutAGrantedFolderNothingIsFoundAndDiagnosisSaysSo() {
        val source = LyricsSource(context)
        assertTrue(!source.hasFolder())
        assertNull(runBlocking { source.load(track("Deep Song")) })
        assertTrue(source.diagnose(track("Deep Song")).contains("انتخاب نکرده"))
    }
}
