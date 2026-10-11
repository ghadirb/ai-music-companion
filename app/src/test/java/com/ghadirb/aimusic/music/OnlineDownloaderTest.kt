package com.ghadirb.aimusic.music

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** Downloads against a real local HTTP server: nothing is faked except the site itself. */
class OnlineDownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val dl = OnlineDownloader(allowHttp = true, timeoutMs = 3_000)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0x21) + ByteArray(2000) { 1 }
    private val html = "<!DOCTYPE html><html><body>not found</body></html>".toByteArray()

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun serve(path: String, status: Int, type: String, body: ByteArray, lying: Long? = null) {
        server.createContext(path) { ex ->
            ex.responseHeaders.add("Content-Type", type)
            if (lying != null) ex.sendResponseHeaders(status, lying) else ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
    }

    @Before fun start() {
        dir = Files.createTempDirectory("dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serve("/ok.mp3", 200, "audio/mpeg", mp3)
        serve("/page.mp3", 200, "audio/mpeg", html) // HTTP 200 with an HTML body behind an .mp3 name
        serve("/ctype.mp3", 200, "text/html; charset=utf-8", mp3)
        serve("/missing.mp3", 404, "text/html", html)
        serve("/denied.mp3", 403, "text/html", html)
        serve("/busy.mp3", 503, "text/plain", "busy".toByteArray())
        serve("/zip.zip", 200, "application/zip", byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(100))
        serve("/notzip.zip", 200, "application/zip", mp3)
        serve("/short.mp3", 200, "audio/mpeg", mp3.copyOf(500), lying = mp3.size.toLong()) // connection ends before Content-Length
        server.start()
    }

    @After fun stop() { server.stop(0); dir.deleteRecursively() }

    @Test fun downloadsAudioAndLeavesNoPartFile() {
        val r = dl.download(base() + "/ok.mp3", File(dir, "a.mp3"), DownloadKind.AUDIO, 1_000_000)
        assertTrue(r is DownloadResult.Ok)
        assertEquals(mp3.size.toLong(), File(dir, "a.mp3").length())
        assertFalse(File(dir, "a.mp3.part").exists())
    }

    @Test fun htmlBodyWithStatus200IsNotKept() {
        val r = dl.download(base() + "/page.mp3", File(dir, "b.mp3"), DownloadKind.AUDIO, 1_000_000)
        assertEquals(DownloadResult.Failed("html_page", false), r)
        assertFalse(File(dir, "b.mp3").exists()); assertFalse(File(dir, "b.mp3.part").exists())
    }

    @Test fun htmlContentTypeIsRejectedBeforeReading() {
        assertEquals(DownloadResult.Failed("html_page", false), dl.download(base() + "/ctype.mp3", File(dir, "c.mp3"), DownloadKind.AUDIO, 1_000_000))
    }

    @Test fun permanentAndTemporaryHttpErrorsAreTold() {
        assertEquals(DownloadResult.Failed("http_404", false), dl.download(base() + "/missing.mp3", File(dir, "d.mp3"), DownloadKind.AUDIO, 1_000_000))
        assertEquals(DownloadResult.Failed("http_403", false), dl.download(base() + "/denied.mp3", File(dir, "d.mp3"), DownloadKind.AUDIO, 1_000_000))
        assertEquals(DownloadResult.Failed("http_503", true), dl.download(base() + "/busy.mp3", File(dir, "d.mp3"), DownloadKind.AUDIO, 1_000_000))
    }

    @Test fun tooLargeIsRefusedUsingContentLength() {
        assertEquals(DownloadResult.Failed("too_large", false), dl.download(base() + "/ok.mp3", File(dir, "e.mp3"), DownloadKind.AUDIO, 100))
        assertFalse(File(dir, "e.mp3").exists())
    }

    @Test fun truncatedTransferIsTemporaryAndCleanedUp() {
        val r = dl.download(base() + "/short.mp3", File(dir, "f.mp3"), DownloadKind.AUDIO, 1_000_000)
        assertTrue(r is DownloadResult.Failed && r.temporary)
        assertFalse(File(dir, "f.mp3").exists()); assertFalse(File(dir, "f.mp3.part").exists())
    }

    @Test fun zipMustStartWithPkAndAudioMustNotBeAcceptedAsZip() {
        assertTrue(dl.download(base() + "/zip.zip", File(dir, "g.zip"), DownloadKind.ZIP, 1_000_000) is DownloadResult.Ok)
        assertEquals(DownloadResult.Failed("wrong_type", false), dl.download(base() + "/notzip.zip", File(dir, "h.zip"), DownloadKind.ZIP, 1_000_000))
    }

    @Test fun plainHttpIsRefusedByDefault() {
        val strict = OnlineDownloader()
        assertEquals(DownloadResult.Failed("insecure_url", false), strict.download(base() + "/ok.mp3", File(dir, "i.mp3"), DownloadKind.AUDIO, 1_000_000))
        assertEquals(DownloadResult.Failed("bad_url", false), strict.download("not a url", File(dir, "i.mp3"), DownloadKind.AUDIO, 1_000_000))
    }

    @Test fun cancelStopsAndCleansUp() {
        val r = dl.download(base() + "/ok.mp3", File(dir, "j.mp3"), DownloadKind.AUDIO, 1_000_000, isCancelled = { true })
        assertEquals(DownloadResult.Failed("cancelled", true), r)
        assertFalse(File(dir, "j.mp3.part").exists())
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Artoosh - Gole Yakh.mp3", OnlineDownloader.safeFileName("Artoosh", "Gole Yakh", "https://x.test/a/b.mp3?x=1"))
        assertEquals("a_b_c.m4a", OnlineDownloader.safeFileName(null, "a/b:c", "https://x.test/f.m4a"))
        assertTrue(OnlineDownloader.safeFileName(null, "x".repeat(500), "https://x.test/f").length <= 124)
        assertEquals("song.mp3", OnlineDownloader.safeFileName(null, "..", "https://x.test/f.exe_a"))
    }
}
