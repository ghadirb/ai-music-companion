package com.ghadirb.aimusic.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val NOW = Instant.parse("2026-10-11T08:00:00Z").toEpochMilli() // 11:30 in Tehran, 19 Mehr 1405

private fun listing(vararg rows: String) =
    "<html><head><title>Index of /x/</title></head><body><h1>Index of /x/</h1><hr><pre><a href=\"../\">../</a>\n" +
        rows.joinToString("\n") + "\n</pre><hr></body></html>"

private fun fileRow(name: String, size: Long = 1000) =
    "<a href=\"${Autoindex.encodeSegment(name)}\">${name.take(40)}</a>   19-Sep-2026 10:20   $size"

// ------------------------------------------------------------------------------------------------ Jalali

class JalaliTest {
    @Test fun knownDates() {
        assertEquals(JalaliDate(1405, 7, 19), Jalali.fromGregorian(2026, 10, 11))
        assertEquals(JalaliDate(1404, 1, 1), Jalali.fromGregorian(2025, 3, 21))   // Nowruz
        assertEquals(JalaliDate(1403, 12, 30), Jalali.fromGregorian(2025, 3, 20)) // 1403 is a leap year
        assertEquals(JalaliDate(1405, 7, 1), Jalali.fromGregorian(2026, 9, 23))
        assertEquals(JalaliDate(1405, 6, 31), Jalali.fromGregorian(2026, 9, 22))  // month boundary
    }

    @Test fun keyIsZeroPadded() {
        assertEquals("1405-07-01", JalaliDate(1405, 7, 1).key)
    }

    @Test fun recentDaysAreNewestFirstAndCrossMonthAndYear() {
        val days = Jalali.recentDays(NOW, 3)
        assertEquals(listOf("2026-10-11", "2026-10-10", "2026-10-09"), days.map { it.gregorian.toString() })
        assertEquals(listOf("1405-07-19", "1405-07-18", "1405-07-17"), days.map { it.jalali.key })
        val crossing = Jalali.recentDays(Instant.parse("2026-03-21T10:00:00Z").toEpochMilli(), 3)
        assertEquals(listOf("1405-01-01", "1404-12-29", "1404-12-28"), crossing.map { it.jalali.key })
    }

    @Test fun todayUsesTehranTimeZone() {
        // 21:00 UTC on Oct 10 is already 00:30 on Oct 11 in Tehran (UTC+3:30, no DST).
        val d = Jalali.recentDays(Instant.parse("2026-10-10T21:00:00Z").toEpochMilli(), 1).single()
        assertEquals("2026-10-11", d.gregorian.toString())
    }
}

// ------------------------------------------------------------------------------------------------ Autoindex

class AutoindexTest {
    @Test fun parsesNginxListing() {
        val html = listing(fileRow("Artoosh - Goldenhits.zip", 1048576), "<a href=\"sub/\">sub/</a>   19-Sep-2026 10:21   -")
        val r = Autoindex.parse(html) as ListingResult.Listing
        assertEquals(2, r.entries.size)
        val zip = r.entries.first { !it.isDir }
        assertEquals("Artoosh - Goldenhits.zip", zip.name)
        assertEquals(1048576L, zip.size)
        assertEquals("2026-09-19T10:20", zip.modified)
        assertTrue(r.entries.first { it.isDir }.name == "sub")
    }

    @Test fun nonListingsAreNotTreatedAsEmptyFolders() {
        assertTrue(Autoindex.parse("") is ListingResult.NotAListing)
        assertTrue(Autoindex.parse("<html><title>Login</title><form></form></html>") is ListingResult.NotAListing)
    }

    @Test fun hostileLinksAreRejected() {
        val html = listing(
            "<a href=\"/etc/passwd\">x</a>", "<a href=\"http://evil.example/x.mp3\">x</a>",
            "<a href=\"a%2Fb.mp3\">x</a>", "<a href=\"..%5Cx.mp3\">x</a>", "<a href=\"ok.mp3\">ok.mp3</a>",
        )
        val names = (Autoindex.parse(html) as ListingResult.Listing).entries.map { it.name }
        assertEquals(listOf("ok.mp3"), names)
    }

    @Test fun nameParsingHandlesQualityMarkers() {
        assertEquals(Autoindex.ParsedName("Ali", "Song", 320), Autoindex.parseTrackName("Ali - Song [320].mp3"))
        assertEquals(Autoindex.ParsedName("Ali", "Song", 128), Autoindex.parseTrackName("Ali - Song (128).mp3"))
        assertEquals(Autoindex.ParsedName("Ali", "Song", 128), Autoindex.parseTrackName("Ali - Song - 128.mp3"))
        assertEquals("Song (77)", Autoindex.parseTrackName("Ali - Song (77).mp3").title) // 77 is not a real bitrate
        assertNull(Autoindex.parseTrackName("Only a title.mp3").artist)
    }

    @Test fun persianTextIsNormalized() {
        assertEquals("علی کریمی 123", Autoindex.normalizeText("علي  كريمي ۱۲۳"))
        assertEquals(Autoindex.normalizeText("می‌خواهم"), Autoindex.normalizeText("می خواهم"))
    }

    @Test fun trackKeyIgnoresArtistOrderAndSpelling() {
        assertEquals(Autoindex.trackKey("A & B", "Song"), Autoindex.trackKey("B و A", "song"))
        assertEquals(Autoindex.trackKey("علي", "عشق"), Autoindex.trackKey("علی", "عشق"))
    }

    @Test fun zipNamesGiveArtistAndCollection() {
        assertEquals(Pair<String?, String>("Artoosh", "Goldenhits"), Autoindex.parseZipName("Artoosh - Goldenhits.zip"))
        assertEquals("artoosh", Autoindex.artistKey("Artoosh ft. Someone"))
        assertEquals("", Autoindex.artistKey(null))
    }

    @Test fun extensionsAreRecognised() {
        assertTrue(Autoindex.isAudioName("a.MP3"))
        assertTrue(Autoindex.isZipName("a.Zip"))
        assertFalse(Autoindex.isAudioName("a.mp4"))
        assertFalse(Autoindex.isAudioName(".mp3"))
    }

    @Test fun segmentsAreEncodedOnce() {
        assertEquals("Ali%20-%20Song%20%5B320%5D.mp3", Autoindex.encodeSegment("Ali - Song [320].mp3"))
    }
}

// ------------------------------------------------------------------------------------------------ merge / fallback

class OnlineMergeTest {
    private fun v(src: String, url: String, ok: Boolean = true, q: Int? = null) = OnlineVersion(url, src, src, q, null, ok)
    private fun t(id: String, artist: String?, title: String, iso: String, vararg vs: OnlineVersion) = OnlineTrack(id, artist, title, iso, vs.toList())

    @Test fun duplicatesAcrossSourcesMergeAndKeepEveryLink() {
        val server = listOf(t("s1", "Ali", "Song", "2026-10-10", v("giso", "https://g/1.mp3", q = 128)))
        val device = listOf(t("dev:1", "ali", "song", "2026-10-11", v("ahaang", "https://a/1.mp3")))
        val merged = OnlineMerge.merge(server, device)
        assertEquals(1, merged.size)
        val m = merged.single()
        assertEquals(listOf("ahaang", "giso"), m.versions.map { it.sourceId }) // best-priority source first
        assertEquals("2026-10-10", m.folderIso)                                 // earliest filing date wins
        assertEquals("s1", m.id)                                                // a server id beats a device id
    }

    @Test fun sortingFollowsDateNotSourcePriority() {
        val newer = t("1", "A", "New", "2026-10-11", v("roz", "https://r/1.mp3"))
        val older = t("2", "B", "Old", "2026-10-05", v("ahaang", "https://a/2.mp3"))
        assertEquals(listOf("New", "Old"), OnlineMerge.merge(listOf(older, newer), emptyList()).map { it.title })
    }

    @Test fun deadLinksSinkToTheEndOfTheVersionList() {
        val track = t("1", "A", "S", "2026-10-11", v("ahaang", "https://a/1.mp3", ok = false), v("giso", "https://g/1.mp3"))
        val merged = OnlineMerge.merge(listOf(track), emptyList()).single()
        assertEquals("giso", merged.versions.first().sourceId)
    }

    @Test fun sameSongBetterQualityComesFirstWithinASource() {
        val track = t("1", "A", "S", "2026-10-11", v("giso", "https://g/128.mp3", q = 128), v("giso", "https://g/orig.mp3"))
        assertEquals("https://g/orig.mp3", OnlineMerge.merge(listOf(track), emptyList()).single().versions.first().url)
    }

    @Test fun windowAndQueryFilters() {
        val a = t("1", "علي", "عشق", "2026-10-11", v("giso", "https://g/1.mp3"))
        val b = t("2", "Zed", "Old", "2026-06-01", v("giso", "https://g/2.mp3"))
        assertEquals(listOf("1"), OnlineMerge.merge(listOf(a, b), emptyList(), sinceIso = "2026-09-01").map { it.id })
        assertEquals(listOf("1"), OnlineMerge.merge(listOf(a, b), emptyList(), query = "علی عشق").map { it.id }) // ی vs ي
        assertTrue(OnlineMerge.merge(listOf(a, b), emptyList(), query = "nothing here").isEmpty())
    }

    @Test fun nextVersionNeverLeavesTheSameSong() {
        val track = t("1", "A", "S", "2026-10-11", v("ahaang", "https://a/1.mp3"), v("giso", "https://g/1.mp3"), v("roz", "https://r/1.mp3"))
        assertEquals("https://g/1.mp3", OnlineMerge.nextVersion(track, setOf("https://a/1.mp3"))?.url)
        assertEquals("https://r/1.mp3", OnlineMerge.nextVersion(track, setOf("https://a/1.mp3", "https://g/1.mp3"))?.url)
        assertNull(OnlineMerge.nextVersion(track, track.versions.map { it.url }.toSet()))
    }

    @Test fun deviceIdIsStable() {
        assertEquals(OnlineMerge.deviceId("https://a/x"), OnlineMerge.deviceId("https://a/x"))
        assertTrue(OnlineMerge.deviceId("https://a/x").startsWith("dev:"))
    }
}

// ------------------------------------------------------------------------------------------------ on-device Ahaang scanner

class AhaangScannerTest {
    private class MemStore : AhaangStore {
        var snap = AhaangSnapshot()
        override fun load() = snap
        override fun save(snapshot: AhaangSnapshot) { snap = snapshot }
    }

    private class FakeFetcher(val pages: Map<String, Fetch>, val default: Fetch = Fetch.NotFound) : ListingFetcher {
        val calls = ArrayList<String>()
        @Synchronized override fun fetch(url: String): Fetch { calls.add(url); return pages[url] ?: default }
    }

    private val base = "https://ahaaang.musitraf.com"
    private val day19 = "$base/05/07/19/"

    @Test fun dayUrlUsesTwoDigitJalaliYear() {
        val scanner = AhaangScanner(FakeFetcher(emptyMap()), MemStore(), { NOW })
        assertEquals(day19, scanner.dayUrl(Jalali.recentDays(NOW, 1).single()))
    }

    @Test fun findsSongsAndArchivesAndBuildsEncodedUrls() = runBlocking {
        val html = listing(fileRow("Ali - Song [320].mp3"), fileRow("Artoosh - Goldenhits.zip", 5_000_000), fileRow("cover.jpg"))
        val fetcher = FakeFetcher(mapOf(day19 to Fetch.Ok(html)))
        val scanner = AhaangScanner(fetcher, MemStore(), { NOW })
        val report = scanner.scan(windowDays = 2)
        assertEquals(2, report.scannedDays)
        assertEquals(0, report.errors)

        val tracks = scanner.tracks("2026-10-01")
        assertEquals(1, tracks.size)
        assertEquals("$base/05/07/19/Ali%20-%20Song%20%5B320%5D.mp3", tracks.single().versions.single().url)
        assertEquals(320, tracks.single().versions.single().quality)
        assertEquals("2026-10-11", tracks.single().folderIso)

        val zips = scanner.collections("2026-10-01")
        assertEquals(1, zips.size)
        assertEquals("artoosh", zips.single().artistKey)
        assertEquals("Goldenhits", zips.single().collection)
        assertTrue(zips.single().onDevice)
    }

    @Test fun recentScansAreNotRepeated() = runBlocking {
        val fetcher = FakeFetcher(mapOf(day19 to Fetch.Ok(listing(fileRow("A - B.mp3")))))
        val scanner = AhaangScanner(fetcher, MemStore(), { NOW })
        scanner.scan(2)
        val first = fetcher.calls.size
        scanner.scan(2)
        assertEquals(first, fetcher.calls.size)
    }

    @Test fun unreachableSiteStopsEarlyInsteadOfHammering() = runBlocking {
        val fetcher = FakeFetcher(emptyMap(), default = Fetch.Error("redirect"))
        val scanner = AhaangScanner(fetcher, MemStore(), { NOW }, concurrency = 4)
        val report = scanner.scan(30)
        assertTrue(report.stoppedEarly)
        assertEquals(4, fetcher.calls.size)
        assertEquals(4, report.errors)
    }

    @Test fun failedDaysAreRetriedOnlyAfterTheCooldown() = runBlocking {
        var clock = NOW
        val fetcher = FakeFetcher(emptyMap(), default = Fetch.Error("timeout"))
        val scanner = AhaangScanner(fetcher, MemStore(), { clock })
        scanner.scan(1)
        val after1 = fetcher.calls.size
        clock += 5 * 60_000L
        scanner.scan(1)
        assertEquals(after1, fetcher.calls.size)
        clock += 31 * 60_000L
        scanner.scan(1)
        assertEquals(after1 + 1, fetcher.calls.size)
    }

    @Test fun loginPageIsAnErrorNotAnEmptyDay() = runBlocking {
        val fetcher = FakeFetcher(mapOf(day19 to Fetch.Ok("<html><title>Login</title></html>")))
        val store = MemStore()
        AhaangScanner(fetcher, store, { NOW }).scan(1)
        assertEquals("error", store.snap.days.values.single().status)
    }

    @Test fun snapshotSurvivesJsonRoundTripAndCorruption() {
        val snap = AhaangSnapshot(mapOf("1405-07-19" to DayRecord("1405-07-19", "2026-10-11", "ok", 123L, listOf(FileRecord("a.mp3", 5L, null)))))
        val back = AhaangSnapshot.fromJson(snap.toJson())
        assertEquals(snap.days, back.days)
        assertTrue(AhaangSnapshot.fromJson("{not json").days.isEmpty())
    }
}

// ------------------------------------------------------------------------------------------------ ZIP safety

class ZipSafeTest {
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0) + ByteArray(2000) { (it % 251).toByte() }

    private fun tmpDir(): File = Files.createTempDirectory("zipsafe").toFile().also { it.deleteOnExit() }

    private fun zip(vararg entries: Pair<String, ByteArray>, patch: ((ByteArray) -> Unit)? = null): File {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        val bytes = bos.toByteArray()
        patch?.invoke(bytes)
        return File(tmpDir(), "a.zip").also { it.writeBytes(bytes) }
    }

    private fun list(f: File) = ZipSafe.readDirectory(FileRangeSource(f), f.length())
    private fun failure(r: ZipListing) = r as ZipListing.Failed

    @Test fun listsAudioAndSkipsTheRest() {
        val f = zip("b.mp3" to mp3, "a.mp3" to mp3, "readme.txt" to "hi".toByteArray())
        val r = list(f) as ZipListing.Ok
        assertEquals(listOf("a.mp3", "b.mp3"), r.audio.map { it.path })
        assertEquals(1, r.skippedEntries)
    }

    @Test fun zipSlipPathsAreRejected() {
        assertEquals("unsafe_path", failure(list(zip("../evil.mp3" to mp3))).reason)
        assertEquals("unsafe_path", failure(list(zip("/abs/evil.mp3" to mp3))).reason)
        assertEquals("unsafe_path", failure(list(zip("C:evil.mp3" to mp3))).reason)
        assertFalse(ZipSafe.isSafeEntryName("a/../../b.mp3"))
        assertTrue(ZipSafe.isSafeEntryName("CD1/01 - Song.mp3"))
    }

    @Test fun executablesAreRejected() {
        assertEquals("executable", failure(list(zip("a.mp3" to mp3, "run.exe" to ByteArray(10)))).reason)
        assertEquals("executable", failure(list(zip("a.mp3" to mp3, "x.apk" to ByteArray(10)))).reason)
    }

    @Test fun compressionBombsAreRejected() {
        val r = failure(list(zip("zeros.mp3" to ByteArray(8 * 1024 * 1024))))
        assertEquals("zip_bomb", r.reason)
        assertTrue(r.permanent)
    }

    @Test fun archiveWithoutAudioIsRejected() {
        assertEquals("no_audio", failure(list(zip("a.txt" to "x".toByteArray()))).reason)
    }

    @Test fun encryptedEntriesAreRejected() {
        val f = zip("a.mp3" to mp3) { bytes ->
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()) {
                    bytes[i + 8] = (bytes[i + 8].toInt() or 1).toByte(); break
                }
            }
        }
        assertEquals("encrypted", failure(list(f)).reason)
    }

    @Test fun garbageAndTinyFilesAreRejected() {
        val d = tmpDir()
        val junk = File(d, "j.zip").also { it.writeBytes(ByteArray(500) { 7 }) }
        assertEquals("no_eocd", failure(list(junk)).reason)
        val tiny = File(d, "t.zip").also { it.writeBytes(ByteArray(5)) }
        assertEquals("too_small", failure(list(tiny)).reason)
    }

    @Test fun oversizedArchivesAreRefusedWithoutReading() {
        val never = object : RangeSource { override fun read(offset: Long, length: Int): ByteArray = throw AssertionError("must not read") }
        assertEquals("too_large", failure(ZipSafe.readDirectory(never, ZipSafe.MAX_ARCHIVE_BYTES + 1)).reason)
    }

    @Test fun networkErrorsAreTemporary() {
        val flaky = object : RangeSource { override fun read(offset: Long, length: Int): ByteArray = throw IOException("down") }
        val r = failure(ZipSafe.readDirectory(flaky, 10_000))
        assertEquals("network", r.reason)
        assertFalse(r.permanent)
    }

    @Test fun extractWritesOnlyInsideTheDestination() {
        val f = zip("CD1/01 - Song.mp3" to mp3)
        val entry = (list(f) as ZipListing.Ok).audio.single()
        val dest = File(f.parentFile, "out")
        val out = ZipSafe.extract(f, entry, dest)
        assertEquals(dest.canonicalFile, out.canonicalFile.parentFile)
        assertEquals("01 - Song.mp3", out.name)
        assertEquals(mp3.size.toLong(), out.length())
        val again = ZipSafe.extract(f, entry, dest) // name collision gets a suffix instead of overwriting
        assertEquals("01 - Song (2).mp3", again.name)
    }

    @Test fun extractRefusesNonAudioContentAndLeavesNoFiles() {
        val f = zip("fake.mp3" to "this is not audio at all".toByteArray())
        val entry = (list(f) as ZipListing.Ok).audio.single()
        val dest = File(f.parentFile, "out")
        try { ZipSafe.extract(f, entry, dest); fail("expected not_audio") } catch (e: ZipSafeException) { assertEquals("not_audio", e.reason) }
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }

    @Test fun extractRejectsUnsafeNamesAndLyingSizes() {
        val f = zip("a.mp3" to mp3)
        val dest = File(f.parentFile, "out")
        try { ZipSafe.extract(f, ZipEntryInfo("../a.mp3", 10, 10), dest); fail() } catch (e: ZipSafeException) { assertEquals("unsafe_path", e.reason) }
        try { ZipSafe.extract(f, ZipEntryInfo("a.mp3", 10, 10), dest); fail() } catch (e: ZipSafeException) { assertEquals("size_mismatch", e.reason) }
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }

    @Test fun audioSniffer() {
        assertTrue(AudioSniffer.looksLikeAudio(mp3))
        assertTrue(AudioSniffer.looksLikeAudio("fLaC0000".toByteArray()))
        assertFalse(AudioSniffer.looksLikeAudio("<html>nope".toByteArray()))
        assertTrue(AudioSniffer.looksLikeHtml("  <!DOCTYPE html><html>".toByteArray()))
        assertFalse(AudioSniffer.looksLikeHtml(mp3))
        assertNotNull(ZipSafe.MAX_ENTRIES)
    }
}
