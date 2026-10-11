package com.ghadirb.aimusic.music

import com.ghadirb.aimusic.cloud.CloudApi
import org.json.JSONArray
import org.json.JSONObject

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data object Offline : ApiResult<Nothing>()
    data class Failed(val code: Int, val error: String?) : ApiResult<Nothing>()
}

data class MusicConfigInfo(val defaultWindowDays: Int, val maxWindowDays: Int, val priorities: Map<String, Int>, val lastScanAt: Long?)
data class TracksPage(val tracks: List<OnlineTrack>, val total: Int, val nextOffset: Int?)
data class ArchiveInfo(val entries: List<ArchiveTrack>)
data class ResolveResult(val version: OnlineVersion?)

/** Client of the Worker's /v1/music/* endpoints (anonymous session, no secret in the APK). */
class MusicApi(private val cloud: CloudApi) {

    private fun <T> map(r: CloudApi.Response, parse: (JSONObject) -> T): ApiResult<T> = when {
        r.isOffline -> ApiResult.Offline
        r.isSuccess -> try { ApiResult.Ok(parse(r.body)) } catch (_: Exception) { ApiResult.Failed(r.code, "bad_response") }
        else -> ApiResult.Failed(r.code, r.error)
    }

    suspend fun config(): ApiResult<MusicConfigInfo> = map(cloud.post("/v1/music/config", JSONObject())) { b ->
        val pr = HashMap<String, Int>()
        val src = b.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until src.length()) src.getJSONObject(i).let { pr[it.getString("id")] = it.optInt("priority", 99) }
        MusicConfigInfo(b.optInt("defaultWindowDays", 90), b.optInt("maxWindowDays", 183), pr, if (b.has("lastScanAt") && !b.isNull("lastScanAt")) b.optLong("lastScanAt") else null)
    }

    suspend fun tracks(windowDays: Int, query: String, offset: Int, limit: Int = 30): ApiResult<TracksPage> =
        map(cloud.post("/v1/music/tracks", JSONObject().put("windowDays", windowDays).put("q", query).put("offset", offset).put("limit", limit))) { b ->
            val arr = b.getJSONArray("tracks")
            TracksPage((0 until arr.length()).map { parseTrack(arr.getJSONObject(it)) }, b.optInt("total", 0), if (b.isNull("nextOffset")) null else b.optInt("nextOffset"))
        }

    suspend fun artists(): ApiResult<List<OnlineArtist>> = map(cloud.post("/v1/music/artists", JSONObject())) { b ->
        val arr = b.getJSONArray("artists")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            OnlineArtist(o.getString("artistKey"), o.optString("artist").ifBlank { o.getString("artistKey") }, o.optInt("collections", 0), o.optString("latestIso"))
        }
    }

    suspend fun collections(artistKey: String): ApiResult<List<OnlineCollection>> =
        map(cloud.post("/v1/music/collections", JSONObject().put("artistKey", artistKey))) { b ->
            val arr = b.getJSONArray("collections")
            (0 until arr.length()).map { parseCollection(arr.getJSONObject(it)) }
        }

    /** Contents of a validated archive. Errors such as "archive_unavailable" (encrypted, unsafe, too big) are returned, not thrown. */
    suspend fun archive(url: String): ApiResult<ArchiveInfo> = map(cloud.post("/v1/music/archive", JSONObject().put("url", url))) { b ->
        val arr = b.getJSONArray("entries")
        ArchiveInfo((0 until arr.length()).map { val o = arr.getJSONObject(it); ArchiveTrack(o.getString("path"), o.getString("name"), o.optLong("size")) })
    }

    /** Asks the Worker for the first version of the song that really serves audio, skipping links the phone already saw fail. */
    suspend fun resolve(trackId: String, exclude: List<String>): ApiResult<ResolveResult> =
        map(cloud.post("/v1/music/resolve", JSONObject().put("trackId", trackId).put("exclude", JSONArray(exclude.take(12))))) { b ->
            if (!b.optBoolean("ok")) ResolveResult(null) else ResolveResult(b.getJSONObject("version").let {
                OnlineVersion(it.getString("url"), it.getString("sourceId"), it.optString("source"), it.optIntOrNull("quality"), it.optLongOrNull("size"))
            })
        }

    companion object {
        private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
        private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null

        fun parseTrack(o: JSONObject): OnlineTrack {
            val vs = o.getJSONArray("versions")
            return OnlineTrack(
                id = o.getString("id"),
                artist = if (o.isNull("artist")) null else o.optString("artist").ifBlank { null },
                title = o.getString("title"),
                folderIso = o.getString("folderIso"),
                versions = (0 until vs.length()).map {
                    val v = vs.getJSONObject(it)
                    OnlineVersion(v.getString("url"), v.getString("sourceId"), v.optString("source"), v.optIntOrNull("quality"), v.optLongOrNull("size"), v.optString("status") != "bad")
                },
            )
        }

        fun parseCollection(o: JSONObject) = OnlineCollection(
            url = o.getString("url"), sourceId = o.getString("sourceId"), source = o.optString("source"), fileName = o.getString("fileName"),
            collection = o.getString("collection"), artist = if (o.isNull("artist")) null else o.optString("artist").ifBlank { null },
            artistKey = o.optString("artistKey"), size = o.optLongOrNull("size"), folderIso = o.optString("folderIso"), entryCount = o.optIntOrNull("entryCount"),
        )
    }
}
