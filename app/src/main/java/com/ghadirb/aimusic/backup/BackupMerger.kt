package com.ghadirb.aimusic.backup

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.search.SearchText
import kotlin.math.abs

data class PlaylistPlan(val name: String, val existingId: Long?, val trackIds: List<Long>)

data class RestorePlan(
    val favoriteIds: List<Long>,
    val playlists: List<PlaylistPlan>,
    val history: List<ListeningHistoryEntity>,
    val settings: Map<String, String>,
    val applyTasteProfile: Boolean,
    val unmatchedTracks: Int
)

data class RestoreSummary(
    val favorites: Int,
    val playlistsCreated: Int,
    val playlistsMerged: Int,
    val tracksLinked: Int,
    val historyEntries: Int,
    val unmatchedTracks: Int,
    val darkTheme: Boolean? = null
)

/** Finds the local song for a backed-up reference: exact path first, then by metadata (stable across devices). */
class TrackMatcher(tracks: List<TrackEntity>) {
    private val byPath = tracks.associateBy { it.path }
    private val bySignature = tracks.groupBy { signature(it.title, it.artist) }

    fun match(ref: TrackRef): TrackEntity? {
        byPath[ref.path]?.let { return it }
        if (ref.title.isBlank()) return null
        val candidates = bySignature[signature(ref.title, ref.artist)].orEmpty()
        if (candidates.isEmpty()) return null
        if (ref.durationMs > 0) {
            candidates.firstOrNull { abs(it.durationMs - ref.durationMs) <= DURATION_TOLERANCE_MS }?.let { return it }
            // Same title+artist but a clearly different length is a different recording.
            return null
        }
        return candidates.first()
    }

    private fun signature(title: String, artist: String) =
        SearchText.normalize(title) + "|" + SearchText.normalize(artist.takeIf { it != "Unknown artist" })

    private companion object { const val DURATION_TOLERANCE_MS = 2_500L }
}

/**
 * Pure planning of a restore: what to favourite, which playlists to create or MERGE (same name => no duplicates),
 * and which history entries are new. The caller applies the plan in one transaction.
 */
object BackupMerger {
    fun plan(
        data: BackupData,
        localTracks: List<TrackEntity>,
        existingPlaylists: Map<String, Pair<Long, Set<Long>>>, // lowercase name -> (id, track ids)
        existingHistoryKeys: Set<Pair<Long, Long>>,             // (trackId, startTime)
        hasLocalTasteProfile: Boolean
    ): RestorePlan {
        val matcher = TrackMatcher(localTracks)
        var unmatched = 0
        fun resolve(ref: TrackRef): Long? = matcher.match(ref)?.id.also { if (it == null) unmatched++ }

        val favorites = data.favorites.mapNotNull(::resolve).distinct()

        val playlists = data.playlists.map { p ->
            val ids = p.tracks.mapNotNull(::resolve).distinct()
            val existing = existingPlaylists[p.name.lowercase()]
            if (existing != null) PlaylistPlan(p.name, existing.first, ids.filter { it !in existing.second })
            else PlaylistPlan(p.name, null, ids)
        }

        val seen = HashSet(existingHistoryKeys)
        val history = data.history.mapNotNull { h ->
            val id = matcher.match(h.track)?.id ?: return@mapNotNull null
            if (!seen.add(id to h.startTime)) return@mapNotNull null
            ListeningHistoryEntity(
                trackId = id, startTime = h.startTime, listenDurationMs = h.listenDurationMs,
                completedPercentage = h.completedPercentage, skipped = h.skipped, replayCount = h.replayCount
            )
        }

        return RestorePlan(
            favoriteIds = favorites,
            playlists = playlists,
            history = history,
            settings = data.settings,
            applyTasteProfile = data.tasteProfile != null && !hasLocalTasteProfile,
            unmatchedTracks = unmatched
        )
    }
}
