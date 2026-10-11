package com.ghadirb.aimusic.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------------------------- network

sealed class Fetch {
    data class Ok(val body: String) : Fetch()
    data object NotFound : Fetch()
    data class Error(val kind: String) : Fetch()
}

/** Blocking page fetch (call from Dispatchers.IO). An interface so scanning is unit-testable without a network. */
fun interface ListingFetcher { fun fetch(url: String): Fetch }

/**
 * Plain HTTPS GET with timeouts and a size cap. Redirects are NOT followed (a redirect to another host is how these sites
 * block unwanted traffic, and following it blindly could loop or leave the allowed host).
 */
class HttpListingFetcher(private val timeoutMs: Int = 10_000, private val maxBytes: Int = 2 * 1024 * 1024) : ListingFetcher {
    override fun fetch(url: String): Fetch {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (_: IOException) { return Fetch.Error("bad_url") }
        return try {
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "NovasaMusic/1.1")
            when (val code = c.responseCode) {
                200 -> {
                    val text = c.inputStream.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (out.size() < maxBytes) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        out.toString("UTF-8")
                    }
                    Fetch.Ok(text)
                }
                404, 410 -> Fetch.NotFound
                401, 403 -> Fetch.Error("forbidden")
                in 300..399 -> Fetch.Error("redirect")
                in 500..599 -> Fetch.Error("server_error")
                else -> Fetch.Error("status_$code")
            }
        } catch (_: java.net.SocketTimeoutException) { Fetch.Error("timeout") }
        catch (_: IOException) { Fetch.Error("network") }
        finally { c.disconnect() }
    }
}

/** Range requests against a remote ZIP (only the central directory is read; the archive is never downloaded for listing). */
class HttpRangeSource(private val url: String, private val timeoutMs: Int = 12_000) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            c.setRequestProperty("User-Agent", "NovasaMusic/1.1")
            if (c.responseCode != 206) throw IOException("range_unsupported_${c.responseCode}")
            val buf = ByteArray(length)
            c.inputStream.use { s ->
                var read = 0
                while (read < length) { val n = s.read(buf, read, length - read); if (n < 0) throw IOException("short_read"); read += n }
            }
            return buf
        } finally { c.disconnect() }
    }
}

// ---------------------------------------------------------------------------------------------- persistence

data class FileRecord(val name: String, val size: Long?, val modified: String?)
data class DayRecord(val dayKey: String, val iso: String, val status: String, val scannedAt: Long, val files: List<FileRecord>)

class AhaangSnapshot(val days: Map<String, DayRecord> = emptyMap()) {
    fun toJson(): String {
        val arr = JSONArray()
        for (d in days.values) {
            arr.put(JSONObject().put("k", d.dayKey).put("i", d.iso).put("s", d.status).put("t", d.scannedAt).put("f", JSONArray().also { fa ->
                d.files.forEach { fa.put(JSONObject().put("n", it.name).put("z", it.size ?: JSONObject.NULL).put("m", it.modified ?: JSONObject.NULL)) }
            }))
        }
        return JSONObject().put("v", 1).put("days", arr).toString()
    }

    companion object {
        fun fromJson(text: String): AhaangSnapshot = try {
            val arr = JSONObject(text).getJSONArray("days")
            val map = LinkedHashMap<String, DayRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fa = o.getJSONArray("f")
                val files = (0 until fa.length()).map { j ->
                    val f = fa.getJSONObject(j)
                    FileRecord(f.getString("n"), if (f.isNull("z")) null else f.getLong("z"), if (f.isNull("m")) null else f.getString("m"))
                }
                map[o.getString("k")] = DayRecord(o.getString("k"), o.getString("i"), o.getString("s"), o.getLong("t"), files)
            }
            AhaangSnapshot(map)
        } catch (_: Exception) { AhaangSnapshot() } // a corrupt cache just means "scan again"
    }
}

interface AhaangStore {
    fun load(): AhaangSnapshot
    fun save(snapshot: AhaangSnapshot)
}

class FileAhaangStore(private val file: File) : AhaangStore {
    override fun load(): AhaangSnapshot = if (file.exists()) AhaangSnapshot.fromJson(runCatching { file.readText() }.getOrDefault("")) else AhaangSnapshot()
    override fun save(snapshot: AhaangSnapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.writeText(snapshot.toJson()); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) } }
    }
}

// ---------------------------------------------------------------------------------------------- scanner

data class AhaangScanReport(val scannedDays: Int, val errors: Int, val stoppedEarly: Boolean)

/**
 * Scans the Ahaang day folders ON THE PHONE. The site redirects datacenter traffic (Cloudflare) in a loop, so the Worker
 * cannot read it, while a phone on a normal connection can. Same incremental rules as the Worker: old days are never
 * re-fetched, only recent ones (late uploads), failures (retry) and a slow re-check of the last two weeks.
 */
class AhaangScanner(
    private val fetcher: ListingFetcher,
    private val store: AhaangStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = "https://ahaaang.musitraf.com",
    private val concurrency: Int = 4,
) {
    private var snapshot: AhaangSnapshot = store.load()

    fun dayUrl(day: FolderDay): String = String.format(java.util.Locale.ROOT, "%s/%02d/%02d/%02d/", baseUrl, day.jalali.jy % 100, day.jalali.jm, day.jalali.jd)

    private fun isDue(record: DayRecord?, age: Int, nowMs: Long): Boolean {
        if (record == null) return true
        val since = nowMs - record.scannedAt
        return when {
            record.status == "error" && since >= 30 * MIN -> true
            age < 3 && since >= 25 * MIN -> true
            age < 14 && since >= 6 * HOUR -> true
            else -> false
        }
    }

    suspend fun scan(windowDays: Int, onProgress: () -> Unit = {}): AhaangScanReport = withContext(Dispatchers.IO) {
        val nowMs = now()
        val days = Jalali.recentDays(nowMs, windowDays)
        val due = days.withIndex().filter { (age, d) -> isDue(snapshot.days[d.jalali.key], age, nowMs) }.map { it.value }
        var scanned = 0; var errors = 0; var consecutiveBad = 0; var stopped = false
        for (batch in due.chunked(concurrency)) {
            val results = coroutineScope { batch.map { d -> async { d to scanDay(d, nowMs) } }.awaitAll() }
            val updated = LinkedHashMap(snapshot.days)
            for ((d, rec) in results) {
                updated[d.jalali.key] = rec
                scanned += 1
                if (rec.status == "error") { errors += 1; consecutiveBad += 1 } else consecutiveBad = 0
            }
            snapshot = AhaangSnapshot(prune(updated, nowMs))
            store.save(snapshot)
            onProgress()
            if (consecutiveBad >= 3) { stopped = true; break } // the site is unreachable from here: stop hammering it
        }
        AhaangScanReport(scanned, errors, stopped)
    }

    private fun scanDay(day: FolderDay, nowMs: Long): DayRecord {
        val key = day.jalali.key
        val iso = day.gregorian.toString()
        return when (val res = fetcher.fetch(dayUrl(day))) {
            is Fetch.NotFound -> DayRecord(key, iso, "empty", nowMs, emptyList())
            is Fetch.Error -> DayRecord(key, iso, "error", nowMs, emptyList())
            is Fetch.Ok -> when (val parsed = Autoindex.parse(res.body)) {
                is ListingResult.NotAListing -> DayRecord(key, iso, "error", nowMs, emptyList())
                is ListingResult.Listing -> {
                    val files = parsed.entries.filter { !it.isDir && (Autoindex.isAudioName(it.name) || Autoindex.isZipName(it.name)) }
                        .take(MAX_FILES_PER_DAY).map { FileRecord(it.name, it.size, it.modified) }
                    DayRecord(key, iso, if (files.isEmpty()) "empty" else "ok", nowMs, files)
                }
            }
        }
    }

    private fun prune(days: Map<String, DayRecord>, nowMs: Long): Map<String, DayRecord> {
        val cutoff = Jalali.recentDays(nowMs, RETENTION_DAYS).last().gregorian.toString()
        return days.filterValues { it.iso >= cutoff }
    }

    private fun fileUrl(record: DayRecord, name: String): String {
        val p = record.dayKey.split('-')
        return String.format(java.util.Locale.ROOT, "%s/%02d/%s/%s/%s", baseUrl, p[0].toInt() % 100, p[1], p[2], Autoindex.encodeSegment(name))
    }

    /** One entry per audio file (the caller merges duplicates across sources). */
    fun tracks(sinceIso: String): List<OnlineTrack> = snapshot.days.values.filter { it.iso >= sinceIso }.flatMap { rec ->
        rec.files.filter { Autoindex.isAudioName(it.name) }.mapNotNull { f ->
            val parsed = Autoindex.parseTrackName(f.name)
            if (parsed.title.isBlank()) return@mapNotNull null
            val url = fileUrl(rec, f.name)
            OnlineTrack(
                id = OnlineMerge.deviceId(url), artist = parsed.artist, title = parsed.title, folderIso = rec.iso,
                versions = listOf(OnlineVersion(url, AHAANG_ID, AHAANG_NAME, parsed.quality, f.size)),
            )
        }
    }

    fun collections(sinceIso: String): List<OnlineCollection> = snapshot.days.values.filter { it.iso >= sinceIso }.flatMap { rec ->
        rec.files.filter { Autoindex.isZipName(it.name) }.map { f ->
            val (artist, collection) = Autoindex.parseZipName(f.name)
            OnlineCollection(
                url = fileUrl(rec, f.name), sourceId = AHAANG_ID, source = AHAANG_NAME, fileName = f.name, collection = collection,
                artist = artist, artistKey = Autoindex.artistKey(artist), size = f.size, folderIso = rec.iso, entryCount = null, onDevice = true,
            )
        }
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val MAX_FILES_PER_DAY = 400
        const val RETENTION_DAYS = 200
    }
}
