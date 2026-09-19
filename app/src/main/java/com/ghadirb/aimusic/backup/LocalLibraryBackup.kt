package com.ghadirb.aimusic.backup

import android.content.ContentResolver
import android.net.Uri
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, local-only backup. Audio files and listening history never leave
 * the device; only paths of favourites, playlists and taste settings are
 * stored. Restore merges into the current library and ignores missing songs.
 */
class LocalLibraryBackup(private val repository: MusicRepository) {
    suspend fun exportTo(resolver: ContentResolver, destination: Uri) {
        val tracks = repository.allTracksSnapshot()
        val root = JSONObject().put("format", FORMAT).put("version", 1)
        root.put("favoritePaths", JSONArray(tracks.filter { it.isFavorite }.map { it.path }))
        repository.getUserPreference()?.let { root.put("tasteProfile", preferenceToJson(it)) }

        val playlists = JSONArray()
        repository.getAllPlaylists().forEach { playlist ->
            val paths = repository.getTracksInPlaylist(playlist.id).map { it.path }
            playlists.put(JSONObject().put("name", playlist.name).put("trackPaths", JSONArray(paths)))
        }
        root.put("playlists", playlists)
        resolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(root.toString()) }
            ?: error("Cannot open the selected backup file")
    }

    suspend fun restoreFrom(resolver: ContentResolver, source: Uri): RestoreSummary {
        val text = resolver.openInputStream(source)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Cannot read the selected backup file")
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "این فایل، بکاپ این برنامه نیست." }
        val knownTracks = repository.allTracksSnapshot().associateBy { it.path }
        val favorites = root.optJSONArray("favoritePaths").stringList().filter { it in knownTracks }
        repository.restoreFavoritePaths(favorites)

        root.optJSONObject("tasteProfile")?.let { profile -> repository.saveUserPreference(profile.toPreference()) }

        var importedPlaylists = 0
        var linkedTracks = 0
        root.optJSONArray("playlists").forEachObject { playlistJson ->
            val name = playlistJson.optString("name").trim().take(80)
            if (name.isBlank()) return@forEachObject
            val playlistId = repository.createPlaylist("$name (بازیابی‌شده)")
            playlistJson.optJSONArray("trackPaths").stringList().forEach { path ->
                knownTracks[path]?.let { track ->
                    repository.addTrackToPlaylist(playlistId, track.id)
                    linkedTracks++
                }
            }
            importedPlaylists++
        }
        return RestoreSummary(favorites.size, importedPlaylists, linkedTracks)
    }

    private fun preferenceToJson(value: UserPreferenceEntity) = JSONObject()
        .put("favoriteArtists", value.favoriteArtists)
        .put("favoriteGenres", value.favoriteGenres)
        .put("favoriteEnergyLevel", value.favoriteEnergyLevel)
        .put("preferredDurationMs", value.preferredDurationMs)
        .put("preferredTimeOfDay", value.preferredTimeOfDay)

    private fun JSONObject.toPreference() = UserPreferenceEntity(
        favoriteArtists = optString("favoriteArtists"),
        favoriteGenres = optString("favoriteGenres"),
        favoriteEnergyLevel = optString("favoriteEnergyLevel", "unknown"),
        preferredDurationMs = optLong("preferredDurationMs"),
        preferredTimeOfDay = optString("preferredTimeOfDay", "unknown")
    )

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private inline fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
        if (this == null) return
        for (index in 0 until length()) optJSONObject(index)?.let(action)
    }

    companion object { private const val FORMAT = "ai-music-companion-backup" }
}

data class RestoreSummary(val favorites: Int, val playlists: Int, val tracksLinked: Int)
