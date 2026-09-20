package com.ghadirb.aimusic.backup

import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import org.json.JSONArray
import org.json.JSONObject

/** How a song is identified in a backup. Content URIs change across devices/rescans, so we also keep metadata. */
data class TrackRef(
    val path: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L
)

data class BackupPlaylist(val name: String, val tracks: List<TrackRef>)

data class BackupHistoryEntry(
    val track: TrackRef,
    val startTime: Long,
    val listenDurationMs: Long,
    val completedPercentage: Float,
    val skipped: Boolean,
    val replayCount: Int
)

data class BackupData(
    val version: Int = BackupCodec.CURRENT_VERSION,
    val createdAt: Long = 0L,
    val appVersion: String = "",
    val favorites: List<TrackRef> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val history: List<BackupHistoryEntry> = emptyList(),
    val tasteProfile: UserPreferenceEntity? = null,
    val settings: Map<String, String> = emptyMap()
)

class BackupException(message: String) : Exception(message)

/**
 * Backup file format (JSON). Never contains audio files. v2 adds song metadata for cross-device matching,
 * listening history, app settings and a version/compatibility check. v1 files are still readable.
 */
object BackupCodec {
    const val FORMAT = "ai-music-companion-backup"
    const val CURRENT_VERSION = 2
    const val MIN_SUPPORTED_VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_PLAYLISTS = 1_000
    const val MAX_TRACKS_PER_LIST = 50_000
    const val MAX_HISTORY = 20_000

    fun encode(data: BackupData): String {
        val root = JSONObject().put("format", FORMAT).put("version", CURRENT_VERSION)
            .put("createdAt", data.createdAt).put("appVersion", data.appVersion)
        root.put("favorites", JSONArray(data.favorites.map(::refToJson)))
        root.put("playlists", JSONArray(data.playlists.map { p ->
            JSONObject().put("name", p.name).put("tracks", JSONArray(p.tracks.map(::refToJson)))
        }))
        root.put("history", JSONArray(data.history.take(MAX_HISTORY).map { h ->
            JSONObject().put("track", refToJson(h.track)).put("start", h.startTime).put("listened", h.listenDurationMs)
                .put("completed", h.completedPercentage.toDouble()).put("skipped", h.skipped).put("replays", h.replayCount)
        }))
        data.tasteProfile?.let { root.put("tasteProfile", preferenceToJson(it)) }
        root.put("settings", JSONObject(data.settings))
        return root.toString()
    }

    /** Parses and validates a backup. Throws [BackupException] with a user-readable (Persian) message. */
    fun decode(text: String): BackupData {
        if (text.length > MAX_BYTES) throw BackupException("فایل بکاپ بیش از حد بزرگ است.")
        val root = try { JSONObject(text) } catch (e: Exception) { throw BackupException("این فایل، بکاپ معتبری نیست.") }
        if (root.optString("format") != FORMAT) throw BackupException("این فایل، بکاپ این برنامه نیست.")
        val version = root.optInt("version", 0)
        if (version < MIN_SUPPORTED_VERSION) throw BackupException("نسخهٔ این بکاپ نامعتبر است.")
        if (version > CURRENT_VERSION) throw BackupException("این بکاپ با نسخهٔ جدیدتری از برنامه ساخته شده؛ ابتدا برنامه را به‌روز کنید.")

        return if (version == 1) decodeV1(root) else decodeV2(root, version)
    }

    private fun decodeV1(root: JSONObject): BackupData = BackupData(
        version = 1,
        favorites = root.optJSONArray("favoritePaths").strings().take(MAX_TRACKS_PER_LIST).map { TrackRef(it) },
        playlists = root.optJSONArray("playlists").objects().take(MAX_PLAYLISTS).mapNotNull { p ->
            val name = p.optString("name").trim().take(80).removeSuffix(" (بازیابی‌شده)")
            if (name.isBlank()) null else BackupPlaylist(name, p.optJSONArray("trackPaths").strings().take(MAX_TRACKS_PER_LIST).map { TrackRef(it) })
        },
        tasteProfile = root.optJSONObject("tasteProfile")?.let(::preferenceFromJson)
    )

    private fun decodeV2(root: JSONObject, version: Int): BackupData = BackupData(
        version = version,
        createdAt = root.optLong("createdAt"),
        appVersion = root.optString("appVersion"),
        favorites = root.optJSONArray("favorites").objects().take(MAX_TRACKS_PER_LIST).mapNotNull(::refFromJson),
        playlists = root.optJSONArray("playlists").objects().take(MAX_PLAYLISTS).mapNotNull { p ->
            val name = p.optString("name").trim().take(80)
            if (name.isBlank()) null
            else BackupPlaylist(name, p.optJSONArray("tracks").objects().take(MAX_TRACKS_PER_LIST).mapNotNull(::refFromJson))
        },
        history = root.optJSONArray("history").objects().take(MAX_HISTORY).mapNotNull { h ->
            val ref = h.optJSONObject("track")?.let(::refFromJson) ?: return@mapNotNull null
            val start = h.optLong("start", -1L)
            if (start < 0) return@mapNotNull null
            BackupHistoryEntry(
                track = ref, startTime = start,
                listenDurationMs = h.optLong("listened").coerceAtLeast(0L),
                completedPercentage = h.optDouble("completed", 0.0).toFloat().coerceIn(0f, 1f),
                skipped = h.optBoolean("skipped"),
                replayCount = h.optInt("replays").coerceIn(0, 1000)
            )
        },
        tasteProfile = root.optJSONObject("tasteProfile")?.let(::preferenceFromJson),
        settings = root.optJSONObject("settings")?.let { s ->
            s.keys().asSequence().filter { it in ALLOWED_SETTINGS }.associateWith { s.optString(it) }
        }.orEmpty()
    )

    /** Only these app settings are ever read from a backup (nothing that carries consent, tokens or purchases). */
    val ALLOWED_SETTINGS = setOf("dark_theme")

    private fun refToJson(t: TrackRef) = JSONObject().put("path", t.path).put("title", t.title)
        .put("artist", t.artist).put("album", t.album).put("durationMs", t.durationMs)

    private fun refFromJson(o: JSONObject): TrackRef? {
        val path = o.optString("path")
        val title = o.optString("title")
        if (path.isBlank() && title.isBlank()) return null
        return TrackRef(path, title.take(300), o.optString("artist").take(300), o.optString("album").take(300), o.optLong("durationMs"))
    }

    private fun preferenceToJson(v: UserPreferenceEntity) = JSONObject()
        .put("favoriteArtists", v.favoriteArtists).put("favoriteGenres", v.favoriteGenres)
        .put("favoriteEnergyLevel", v.favoriteEnergyLevel).put("preferredDurationMs", v.preferredDurationMs)
        .put("preferredTimeOfDay", v.preferredTimeOfDay).put("favoriteMoods", v.favoriteMoods)
        .put("preferredBpm", v.preferredBpm).put("energyRange", v.energyRange).put("topTrackIds", v.topTrackIds)
        .put("skipRate", v.skipRate.toDouble()).put("favoriteRatio", v.favoriteRatio.toDouble())
        .put("peakHours", v.peakHours).put("updatedAt", v.updatedAt)

    private fun preferenceFromJson(o: JSONObject) = UserPreferenceEntity(
        favoriteArtists = o.optString("favoriteArtists"),
        favoriteGenres = o.optString("favoriteGenres"),
        favoriteEnergyLevel = o.optString("favoriteEnergyLevel", "unknown"),
        preferredDurationMs = o.optLong("preferredDurationMs"),
        preferredTimeOfDay = o.optString("preferredTimeOfDay", "unknown"),
        favoriteMoods = o.optString("favoriteMoods"),
        preferredBpm = o.optInt("preferredBpm"),
        energyRange = o.optString("energyRange"),
        // Track ids are local to a device, so they are not restored.
        topTrackIds = "",
        skipRate = o.optDouble("skipRate", 0.0).toFloat().coerceIn(0f, 1f),
        favoriteRatio = o.optDouble("favoriteRatio", 0.0).toFloat().coerceIn(0f, 1f),
        peakHours = o.optString("peakHours"),
        updatedAt = o.optLong("updatedAt")
    )

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
