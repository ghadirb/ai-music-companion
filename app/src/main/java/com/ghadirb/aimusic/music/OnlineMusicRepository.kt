package com.ghadirb.aimusic.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which sources failed lately so a dead SITE is not hammered, while a single dead LINK only skips that file.
 * A source is "cooling down" once several DIFFERENT links of it failed within the window; one success clears it.
 */
class SourceHealth(
    private val now: () -> Long = System::currentTimeMillis,
    private val threshold: Int = 3,
    private val coolDownMs: Long = 5 * 60_000L,
) {
    private val recent = HashMap<String, MutableMap<String, Long>>() // source -> failed url -> time

    @Synchronized fun recordFailure(sourceId: String, url: String) { recent.getOrPut(sourceId) { HashMap() }[url] = now() }
    @Synchronized fun recordSuccess(sourceId: String) { recent.remove(sourceId) }
    @Synchronized fun isCoolingDown(sourceId: String): Boolean {
        val m = recent[sourceId] ?: return false
        val cutoff = now() - coolDownMs
        m.values.removeAll { it < cutoff }
        return m.size >= threshold
    }
}

data class TracksFeed(val tracks: List<OnlineTrack>, val serverReachable: Boolean, val serverTracks: Int, val deviceTracks: Int)

sealed class ArchiveOutcome {
    data class Ok(val entries: List<ArchiveTrack>) : ArchiveOutcome()
    /** [temporary] = worth retrying later (network/limit); false = the archive itself is unusable (encrypted, unsafe, too big ...). */
    data class Unavailable(val reason: String, val temporary: Boolean) : ArchiveOutcome()
    data object Offline : ArchiveOutcome()
}

/**
 * One place that combines the Worker catalog (Giso/Mellody/Roz and, when reachable, Ahaang) with what the phone found
 * itself on Ahaang, and picks the playable version of a song. Pure Kotlin: no Android types, so it is unit-tested.
 */
class OnlineMusicRepository(
    private val backend: MusicBackend,
    private val scanner: AhaangScanner,
    private val health: SourceHealth = SourceHealth(),
    private val now: () -> Long = System::currentTimeMillis,
    private val rangeSourceFactory: (String) -> RangeSource = { HttpRangeSource(it) },
) {
    @Volatile var priority: Map<String, Int> = DEFAULT_SOURCE_PRIORITY
        private set

    fun sinceIso(windowDays: Int): String = Jalali.recentDays(now(), windowDays).last().gregorian.toString()

    /** Reads the server's source priorities and lets the phone scan Ahaang, in parallel. Never throws for network problems. */
    suspend fun refresh(windowDays: Int): AhaangScanReport = coroutineScope {
        val config = async { backend.config() }
        val scan = async { scanner.scan(windowDays) }
        (config.await() as? ApiResult.Ok)?.value?.let { cfg ->
            cfg.priorities.takeIf { it.isNotEmpty() }?.let { priority = DEFAULT_SOURCE_PRIORITY + it }
            scanner.collectionWindowDays = cfg.collectionWindowDays
        }
        scan.await()
    }

    /**
     * Walks the OLD Ahaang day folders (years back) for singer-collection ZIPs. Resumable: finished days are never fetched
     * again, so the caller simply repeats while [CollectionScanReport.remainingDays] > 0 and nothing stopped it.
     */
    suspend fun scanCollections(songWindowDays: Int, onProgress: (done: Int, remaining: Int) -> Unit = { _, _ -> }): CollectionScanReport =
        scanner.scanCollections(songWindowDays, onProgress = onProgress)

    suspend fun loadTracks(windowDays: Int, query: String): TracksFeed {
        val since = sinceIso(windowDays)
        val server = ArrayList<OnlineTrack>()
        var reachable = true
        var offset: Int? = 0
        var pages = 0
        while (offset != null && pages < MAX_PAGES) {
            when (val r = backend.tracks(windowDays, query, offset, PAGE_SIZE)) {
                is ApiResult.Ok -> { server += r.value.tracks; offset = r.value.nextOffset }
                else -> { reachable = false; offset = null }
            }
            pages += 1
        }
        val device = scanner.tracks(since)
        return TracksFeed(OnlineMerge.merge(server, device, priority, query, since), reachable, server.size, device.size)
    }

    /** First link worth trying: not already failed, and from a source that is not cooling down (falls back to any untried link). */
    fun pickVersion(track: OnlineTrack, failed: Set<String> = emptySet()): OnlineVersion? {
        val usable = track.versions.filter { it.url !in failed }
        return usable.firstOrNull { !health.isCoolingDown(it.sourceId) } ?: usable.firstOrNull()
    }

    fun reportFailure(version: OnlineVersion) = health.recordFailure(version.sourceId, version.url)
    fun reportSuccess(version: OnlineVersion) = health.recordSuccess(version.sourceId)

    /** Last resort when every known link failed: the Worker re-checks the sources and returns a working copy of the SAME song. */
    suspend fun resolveFromServer(track: OnlineTrack, failed: Set<String>): OnlineVersion? {
        if (track.id.startsWith("dev:")) return null // songs only the phone knows about do not exist on the server
        return (backend.resolve(track.id, failed.toList()) as? ApiResult.Ok)?.value?.version
    }

    suspend fun artists(windowDays: Int): List<OnlineArtist> {
        val since = sinceIso(maxOf(windowDays, scanner.collectionWindowDays))
        val server = (backend.artists() as? ApiResult.Ok)?.value ?: emptyList()
        val device = scanner.collections(since).filter { it.kind == Autoindex.CollectionKind.ARTIST }.groupBy { it.artistKey }.map { (key, list) ->
            OnlineArtist(key, list.firstNotNullOfOrNull { it.artist } ?: key, list.size, list.maxOf { it.folderIso })
        }
        val byKey = LinkedHashMap<String, OnlineArtist>()
        for (a in server + device) {
            val e = byKey[a.key]
            byKey[a.key] = if (e == null) a else e.copy(collections = e.collections + a.collections, latestIso = maxOf(e.latestIso, a.latestIso))
        }
        return byKey.values.sortedBy { Autoindex.normalizeText(it.name) }
    }

    suspend fun collections(artistKey: String, windowDays: Int): List<OnlineCollection> {
        val since = sinceIso(maxOf(windowDays, scanner.collectionWindowDays))
        val server = (backend.collections(artistKey) as? ApiResult.Ok)?.value ?: emptyList()
        val device = scanner.collections(since).filter { it.artistKey == artistKey && it.kind == Autoindex.CollectionKind.ARTIST }
        val seen = HashSet<String>()
        return (server + device).filter { seen.add(it.url) }.sortedByDescending { it.folderIso }
    }

    /** Lists the audio inside an archive without downloading it (central directory only). Archives the Worker found are asked from the Worker. */
    suspend fun archive(collection: OnlineCollection): ArchiveOutcome {
        if (collection.onDevice) {
            val size = collection.size ?: return ArchiveOutcome.Unavailable("size_unknown", true)
            return withContext(Dispatchers.IO) {
                when (val r = ZipSafe.readDirectory(rangeSourceFactory(collection.url), size)) {
                    is ZipListing.Ok -> ArchiveOutcome.Ok(r.audio.map { ArchiveTrack(it.path, it.name, it.size) })
                    is ZipListing.Failed -> ArchiveOutcome.Unavailable(r.reason, !r.permanent)
                }
            }
        }
        return when (val r = backend.archive(collection.url)) {
            is ApiResult.Ok -> ArchiveOutcome.Ok(r.value.entries)
            is ApiResult.Offline -> ArchiveOutcome.Offline
            is ApiResult.Failed -> ArchiveOutcome.Unavailable(r.error ?: "http_${r.code}", r.code == 429 || r.code >= 500)
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 6
    }
}

// ---------------------------------------------------------------------------------------------- favourites

interface TextStore {
    fun read(): String?
    fun write(text: String)
}

/** Favourites among ONLINE songs (the local library keeps its own favourites). Stored as small JSON, newest first, capped. */
class OnlineFavorites(private val store: TextStore) {
    private val items = LinkedHashMap<String, OnlineTrack>()

    init {
        runCatching {
            val arr = JSONArray(store.read() ?: "[]")
            for (i in 0 until arr.length()) {
                val t = MusicApi.parseTrack(arr.getJSONObject(i))
                items[t.key] = t
            }
        } // corrupt data just means an empty list
    }

    fun all(): List<OnlineTrack> = items.values.toList()
    fun isFavorite(track: OnlineTrack): Boolean = track.key in items

    /** Returns true when the song is a favourite after the call. */
    fun toggle(track: OnlineTrack): Boolean {
        val added = if (track.key in items) { items.remove(track.key); false } else {
            val copy = LinkedHashMap<String, OnlineTrack>(); copy[track.key] = track; copy.putAll(items)
            items.clear(); items.putAll(copy)
            while (items.size > MAX_ITEMS) items.remove(items.keys.last())
            true
        }
        store.write(toJson())
        return added
    }

    private fun toJson(): String {
        val arr = JSONArray()
        for (t in items.values) {
            arr.put(JSONObject().put("id", t.id).put("artist", t.artist ?: JSONObject.NULL).put("title", t.title).put("folderIso", t.folderIso)
                .put("versions", JSONArray().also { va ->
                    t.versions.forEach { v -> va.put(JSONObject().put("url", v.url).put("sourceId", v.sourceId).put("source", v.source)
                        .put("quality", v.quality ?: JSONObject.NULL).put("size", v.size ?: JSONObject.NULL).put("status", if (v.ok) "ok" else "bad")) }
                }))
        }
        return arr.toString()
    }

    private companion object { const val MAX_ITEMS = 500 }
}
