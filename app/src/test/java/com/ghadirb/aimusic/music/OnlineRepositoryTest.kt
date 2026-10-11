package com.ghadirb.aimusic.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val REPO_NOW = Instant.parse("2026-10-11T08:00:00Z").toEpochMilli()
private const val AH_DAY = "https://ahaaang.musitraf.com/05/07/19/"

private fun repoListing(vararg rows: String) =
    "<html><head><title>Index of /x/</title></head><body><h1>Index of /x/</h1><hr><pre><a href=\"../\">../</a>\n" + rows.joinToString("\n") + "\n</pre></body></html>"

private fun repoRow(name: String, size: Long = 1000) =
    "<a href=\"${Autoindex.encodeSegment(name)}\">${name.take(40)}</a>   19-Sep-2026 10:20   $size"

private class RepoMemStore : AhaangStore {
    var snap = AhaangSnapshot()
    override fun load() = snap
    override fun save(snapshot: AhaangSnapshot) { snap = snapshot }
}

private class RepoFetcher(val pages: Map<String, Fetch>) : ListingFetcher {
    override fun fetch(url: String): Fetch = pages[url] ?: Fetch.NotFound
}

private class RepoBackend : MusicBackend {
    var tracks: List<OnlineTrack> = emptyList()
    var offline = false
    var config: ApiResult<MusicConfigInfo> = ApiResult.Offline
    var artists: List<OnlineArtist> = emptyList()
    var collections: List<OnlineCollection> = emptyList()
    var archive: ApiResult<ArchiveInfo> = ApiResult.Offline
    var resolved: OnlineVersion? = null
    val resolveCalls = ArrayList<String>()

    override suspend fun config() = config
    override suspend fun tracks(windowDays: Int, query: String, offset: Int, limit: Int): ApiResult<TracksPage> {
        if (offline) return ApiResult.Offline
        val page = tracks.drop(offset).take(limit)
        val next = if (offset + page.size < tracks.size) offset + page.size else null
        return ApiResult.Ok(TracksPage(page, tracks.size, next))
    }
    override suspend fun artists(): ApiResult<List<OnlineArtist>> = ApiResult.Ok(artists)
    override suspend fun collections(artistKey: String): ApiResult<List<OnlineCollection>> = ApiResult.Ok(collections.filter { it.artistKey == artistKey })
    override suspend fun archive(url: String) = archive
    override suspend fun resolve(trackId: String, exclude: List<String>): ApiResult<ResolveResult> {
        resolveCalls.add(trackId); return ApiResult.Ok(ResolveResult(resolved))
    }
}

private fun ver(src: String, url: String) = OnlineVersion(url, src, src, null, null)
private fun trk(id: String, artist: String?, title: String, iso: String, vararg v: OnlineVersion) = OnlineTrack(id, artist, title, iso, v.toList())

private fun repo(backend: RepoBackend, pages: Map<String, Fetch> = emptyMap(), health: SourceHealth = SourceHealth({ REPO_NOW }), range: (String) -> RangeSource = { HttpRangeSource(it) }) =
    OnlineMusicRepository(backend, AhaangScanner(RepoFetcher(pages), RepoMemStore(), { REPO_NOW }), health, { REPO_NOW }, range)

class OnlineRepositoryTest {

    @Test fun mergesServerAndPhoneScanWithAhaangFirstAndEarliestDate() = runBlocking {
        val backend = RepoBackend().apply { tracks = listOf(trk("0123456789abcdef", "Ali", "Song", "2026-10-10", ver("giso", "https://g/s.mp3"))) }
        val r = repo(backend, mapOf(AH_DAY to Fetch.Ok(repoListing(repoRow("Ali - Song.mp3")))))
        r.refresh(3)
        val feed = r.loadTracks(90, "")
        assertTrue(feed.serverReachable)
        assertEquals(1, feed.tracks.size)
        val t = feed.tracks.single()
        assertEquals(listOf("ahaang", "giso"), t.versions.map { it.sourceId })
        assertEquals("2026-10-10", t.folderIso)
        assertEquals("0123456789abcdef", t.id)
    }

    @Test fun serverOutageStillShowsWhatThePhoneFound() = runBlocking {
        val backend = RepoBackend().apply { offline = true }
        val r = repo(backend, mapOf(AH_DAY to Fetch.Ok(repoListing(repoRow("Ali - Song.mp3")))))
        r.refresh(3)
        val feed = r.loadTracks(90, "")
        assertFalse(feed.serverReachable)
        assertEquals(1, feed.deviceTracks)
        assertEquals(1, feed.tracks.size)
    }

    @Test fun readsAllServerPages() = runBlocking {
        val backend = RepoBackend().apply { tracks = (1..120).map { trk("%016x".format(it), "A$it", "T$it", "2026-10-10", ver("giso", "https://g/$it.mp3")) } }
        assertEquals(120, repo(backend).loadTracks(90, "").tracks.size)
    }

    @Test fun serverPrioritiesOverrideButDefaultsRemain() = runBlocking {
        val backend = RepoBackend().apply { config = ApiResult.Ok(MusicConfigInfo(90, 183, mapOf("ahaang" to 2, "giso" to 1), null)) }
        val r = repo(backend)
        r.refresh(1)
        assertEquals(2, r.priority["ahaang"]); assertEquals(1, r.priority["giso"]); assertEquals(3, r.priority["mellody"])
    }

    @Test fun deadLinkSkipsOnlyThatFileButDeadSiteSkipsTheSource() {
        val track = trk("1", "A", "S", "2026-10-11", ver("ahaang", "https://a/1.mp3"), ver("ahaang", "https://a/2.mp3"), ver("giso", "https://g/1.mp3"))
        val health = SourceHealth({ REPO_NOW }, threshold = 2)
        val r = repo(RepoBackend(), health = health)
        assertEquals("https://a/1.mp3", r.pickVersion(track)?.url)
        r.reportFailure(track.versions[0])
        assertEquals("https://a/2.mp3", r.pickVersion(track, setOf("https://a/1.mp3"))?.url) // one bad link: next link of the same site
        r.reportFailure(track.versions[1])
        assertEquals("https://g/1.mp3", r.pickVersion(track, setOf("https://a/1.mp3"))?.url) // two bad links: site cools down
        r.reportSuccess(track.versions[2].copy(sourceId = "ahaang"))
        assertEquals("https://a/1.mp3", r.pickVersion(track)?.url)
    }

    @Test fun healthWindowExpires() {
        var clock = REPO_NOW
        val h = SourceHealth({ clock }, threshold = 2, coolDownMs = 60_000)
        h.recordFailure("s", "u1"); h.recordFailure("s", "u2")
        assertTrue(h.isCoolingDown("s"))
        clock += 61_000
        assertFalse(h.isCoolingDown("s"))
    }

    @Test fun noViableVersionWhenEveryLinkFailed() {
        val track = trk("1", "A", "S", "2026-10-11", ver("giso", "https://g/1.mp3"))
        assertNull(repo(RepoBackend()).pickVersion(track, setOf("https://g/1.mp3")))
    }

    @Test fun serverResolveIsSkippedForPhoneOnlySongs() = runBlocking {
        val backend = RepoBackend().apply { resolved = ver("roz", "https://r/ok.mp3") }
        val r = repo(backend)
        assertNull(r.resolveFromServer(trk("dev:abc", "A", "S", "2026-10-11"), emptySet()))
        assertTrue(backend.resolveCalls.isEmpty())
        assertEquals("https://r/ok.mp3", r.resolveFromServer(trk("0123456789abcdef", "A", "S", "2026-10-11"), setOf("x"))?.url)
    }

    private fun collection(url: String, artist: String, iso: String, device: Boolean = false, size: Long? = 100) =
        OnlineCollection(url, "giso", "G", "$artist - X.zip", "X", artist, Autoindex.artistKey(artist), size, iso, null, device)

    @Test fun artistsAndCollectionsCombineServerAndPhone() = runBlocking {
        val backend = RepoBackend().apply {
            artists = listOf(OnlineArtist("artoosh", "Artoosh", 2, "2026-09-01"))
            collections = listOf(collection("https://g/a.zip", "Artoosh", "2026-09-01"), collection("https://g/dup.zip", "Artoosh", "2026-08-01"))
        }
        val r = repo(backend, mapOf(AH_DAY to Fetch.Ok(repoListing(repoRow("Artoosh - Goldenhits.zip", 5000)))))
        r.refresh(3)
        val a = r.artists(90).single()
        assertEquals(3, a.collections)
        assertEquals("2026-10-11", a.latestIso)
        val cols = r.collections("artoosh", 90)
        assertEquals(3, cols.size)
        assertEquals("2026-10-11", cols.first().folderIso)       // newest first
        assertTrue(cols.first().onDevice)
    }

    @Test fun phoneArchiveListingUsesRangeRequestsAndSafetyChecks() = runBlocking {
        val bos = ByteArrayOutputStream()
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0) + ByteArray(500)
        ZipOutputStream(bos).use { z -> listOf("01.mp3", "02.mp3").forEach { z.putNextEntry(ZipEntry(it)); z.write(mp3); z.closeEntry() } }
        val file = File(Files.createTempDirectory("repo").toFile(), "a.zip").also { it.writeBytes(bos.toByteArray()) }
        val r = repo(RepoBackend(), range = { FileRangeSource(file) })
        val ok = r.archive(collection("https://a/x.zip", "Artoosh", "2026-10-11", device = true, size = file.length()))
        assertEquals(listOf("01.mp3", "02.mp3"), (ok as ArchiveOutcome.Ok).entries.map { it.name })

        val junk = File(file.parentFile, "j.zip").also { it.writeBytes(ByteArray(300) { 9 }) }
        val bad = repo(RepoBackend(), range = { FileRangeSource(junk) }).archive(collection("https://a/y.zip", "A", "2026-10-11", true, junk.length()))
        assertEquals(ArchiveOutcome.Unavailable("no_eocd", false), bad)
        assertEquals(ArchiveOutcome.Unavailable("size_unknown", true), r.archive(collection("https://a/z.zip", "A", "2026-10-11", true, null)))
    }

    @Test fun serverArchiveErrorsAreClassified() = runBlocking {
        val backend = RepoBackend()
        val c = collection("https://g/a.zip", "A", "2026-10-11")
        backend.archive = ApiResult.Offline
        assertEquals(ArchiveOutcome.Offline, repo(backend).archive(c))
        backend.archive = ApiResult.Failed(422, "archive_unavailable")
        assertEquals(ArchiveOutcome.Unavailable("archive_unavailable", false), repo(backend).archive(c))
        backend.archive = ApiResult.Failed(429, "daily_limit_reached")
        assertEquals(ArchiveOutcome.Unavailable("daily_limit_reached", true), repo(backend).archive(c))
        backend.archive = ApiResult.Ok(ArchiveInfo(listOf(ArchiveTrack("a.mp3", "a.mp3", 5))))
        assertEquals(1, (repo(backend).archive(c) as ArchiveOutcome.Ok).entries.size)
    }
}

class OnlineFavoritesTest {
    private class Mem : TextStore { var text: String? = null; override fun read() = text; override fun write(text: String) { this.text = text } }

    private val song = OnlineTrack("0123456789abcdef", "علي", "عشق", "2026-10-11", listOf(OnlineVersion("https://g/1.mp3", "giso", "G", 128, 99L, false)))

    @Test fun toggleAddsAndRemovesAndSurvivesReload() {
        val mem = Mem()
        val f = OnlineFavorites(mem)
        assertTrue(f.toggle(song))
        assertTrue(f.isFavorite(song))
        val again = OnlineFavorites(mem).all().single()
        assertEquals(song, again)                                         // every field incl. version health round-trips
        assertFalse(f.toggle(song.copy(artist = "علی")))                 // same song, other Persian spelling -> same favourite
        assertTrue(OnlineFavorites(mem).all().isEmpty())
    }

    @Test fun newestFirstAndCorruptDataIgnored() {
        val mem = Mem()
        val f = OnlineFavorites(mem)
        f.toggle(song); f.toggle(song.copy(id = "fedcba9876543210", title = "دیگر"))
        assertEquals(listOf("دیگر", "عشق"), f.all().map { it.title })
        mem.text = "{broken"
        assertTrue(OnlineFavorites(mem).all().isEmpty())
    }
}
