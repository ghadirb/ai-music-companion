package com.ghadirb.aimusic.music

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveImporterTest {
    private lateinit var server: HttpServer
    private lateinit var cache: File
    private lateinit var dest: File
    private val hits = AtomicInteger()
    private val dl = OnlineDownloader(allowHttp = true, timeoutMs = 3_000)
    private fun mp3(seed: Int) = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0x21) + ByteArray(3000) { (it + seed).toByte() }
    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun serve(path: String, status: Int, type: String, body: ByteArray) {
        server.createContext(path) { ex ->
            hits.incrementAndGet()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
    }

    private fun collection(url: String, vararg alt: String) = OnlineCollection(
        url = url, sourceId = "ahaang", source = "A", fileName = "Artoosh - Goldenhits.zip", collection = "Goldenhits",
        artist = "Artoosh", artistKey = Autoindex.artistKey("Artoosh"), size = null, folderIso = "2026-09-01", entryCount = null,
        onDevice = true, altUrls = alt.toList(),
    )

    @Before fun start() {
        cache = Files.createTempDirectory("zc").toFile(); dest = Files.createTempDirectory("zd").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serve("/good.zip", 200, "application/zip", zip("01 Gole Yakh.mp3" to mp3(1), "02 Bahar.mp3" to mp3(2), "cover.jpg" to ByteArray(500) { 7 }))
        serve("/gone.zip", 404, "text/html", "<html>no</html>".toByteArray())
        serve("/page.zip", 200, "application/zip", "<html>login</html>".toByteArray())
        serve("/evil.zip", 200, "application/zip", zip("../../evil.mp3" to mp3(3)))
        server.start()
    }

    @After fun stop() { server.stop(0); cache.deleteRecursively(); dest.deleteRecursively() }

    @Test fun downloadsOnceAndReusesTheCachedArchive() {
        val imp = ArchiveImporter(dl, cache)
        val c = collection(base() + "/good.zip")
        assertTrue(imp.fetchZip(c) is ZipFetch.Ok)
        assertTrue(imp.fetchZip(c) is ZipFetch.Ok)
        assertEquals(1, hits.get())
    }

    @Test fun deadMainLinkFallsBackToAnotherCopyOfTheSameCollection() {
        val imp = ArchiveImporter(dl, cache)
        val r = imp.fetchZip(collection(base() + "/gone.zip", base() + "/page.zip", base() + "/good.zip"))
        assertTrue(r is ZipFetch.Ok)
        assertEquals(3, hits.get())
    }

    @Test fun whenEveryCopyFailsTheLastReasonIsReported() {
        val imp = ArchiveImporter(dl, cache)
        val r = imp.fetchZip(collection(base() + "/gone.zip", base() + "/page.zip"))
        assertTrue(r is ZipFetch.Failed)
        assertEquals(0, cache.listFiles()!!.size) // nothing half-written is left behind
    }

    @Test fun extractsOneSongOrTheWholeCollection() {
        val imp = ArchiveImporter(dl, cache)
        val zip = (imp.fetchZip(collection(base() + "/good.zip")) as ZipFetch.Ok).zip
        val one = imp.extract(zip, setOf("02 Bahar.mp3"), File(dest, "one"))
        assertEquals(listOf("02 Bahar.mp3"), one.files.map { it.name }); assertTrue(one.failed.isEmpty())
        val all = imp.extract(zip, null, File(dest, "all"))
        assertEquals(listOf("01 Gole Yakh.mp3", "02 Bahar.mp3"), all.files.map { it.name }.sorted()) // the cover image is not extracted
        assertTrue(all.files.all { it.length() == 3010L })
    }

    @Test fun anArchiveWithAnEscapingPathIsRefusedAndNothingIsWritten() {
        val imp = ArchiveImporter(dl, cache)
        val zip = (imp.fetchZip(collection(base() + "/evil.zip")) as ZipFetch.Ok).zip
        val r = imp.extract(zip, null, File(dest, "x"))
        assertTrue(r.files.isEmpty()); assertEquals(listOf("zip_unusable"), r.failed)
        assertFalse(File(dest.parentFile, "evil.mp3").exists())
    }

    @Test fun purgeRemovesOnlyOldArchivesAndPartFiles() {
        var clock = 10_000_000L
        val imp = ArchiveImporter(dl, cache, now = { clock })
        val old = File(cache, "old.zip").also { it.writeText("x"); it.setLastModified(1_000L) }
        val oldPart = File(cache, "old.zip.part").also { it.writeText("x"); it.setLastModified(1_000L) }
        val fresh = File(cache, "fresh.zip").also { it.writeText("x"); it.setLastModified(clock) }
        val other = File(cache, "keep.txt").also { it.writeText("x"); it.setLastModified(1_000L) }
        imp.purgeStale(60 * 60_000L)
        assertFalse(old.exists()); assertFalse(oldPart.exists()); assertTrue(fresh.exists()); assertTrue(other.exists())
    }
}
