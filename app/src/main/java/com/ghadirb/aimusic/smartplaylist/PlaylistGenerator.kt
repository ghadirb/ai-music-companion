package com.ghadirb.aimusic.smartplaylist

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.ListeningStats
import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.RecommendationScorer
import com.ghadirb.aimusic.recommendation.ScoringConfig
import com.ghadirb.aimusic.recommendation.TrackSimilarity
import com.ghadirb.aimusic.search.SearchText
import java.util.Random
import java.util.TimeZone

data class GeneratedPlaylist(
    val name: String,
    val tracks: List<Recommendation>,
    val intent: PlaylistIntent,
    val totalDurationMs: Long
)

/** Builds a playlist from the user's own library for a [PlaylistIntent]. Pure and deterministic (seeded). */
object PlaylistGenerator {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun generate(
        rawIntent: PlaylistIntent,
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        seed: Long = nowMs / DAY_MS,
        config: ScoringConfig = ScoringConfig(),
        zone: TimeZone = TimeZone.getDefault()
    ): GeneratedPlaylist {
        val intent = rawIntent.sanitized()
        val name = intent.title ?: "پلی‌لیست هوشمند"
        if (tracks.isEmpty()) return GeneratedPlaylist(name, emptyList(), intent, 0L)

        val stats = ListeningStats.build(tracks, history, nowMs, config, zone)
        val scored = RecommendationScorer.score(tracks, history, nowMs, config, zone, stats)
        val source = intent.similarToTrackId?.let { id -> tracks.firstOrNull { it.id == id } }
        val artistFilter = intent.artist?.let { SearchText.normalize(it) }
        val genreFilter = intent.genre?.let { SearchText.normalize(it) }

        val filtered = scored.filter { r ->
            val t = r.track
            if (source != null && t.id == source.id) return@filter false
            if (intent.favoriteOnly && !t.isFavorite) return@filter false
            if (artistFilter != null && !SearchText.normalize(t.artist).contains(artistFilter)) return@filter false
            if (genreFilter != null && !SearchText.normalize(t.genre).contains(genreFilter)) return@filter false
            if (!matchesLanguage(t, intent.language)) return@filter false
            if (!matchesMoodEnergy(t, intent)) return@filter false
            intent.excludeRecentDays?.let { days ->
                val last = stats.signals[t.id]?.lastPlayedAt
                if (last != null && nowMs - last < days * DAY_MS) return@filter false
            }
            true
        }

        val ordered: List<Recommendation> = when {
            source != null -> filtered.sortedByDescending { TrackSimilarity.score(source, it.track) }
            intent.sort == SmartSort.LEAST_PLAYED -> filtered.sortedWith(
                compareBy<Recommendation> { stats.signals[it.track.id]?.plays ?: 0 }
                    .thenBy { stats.signals[it.track.id]?.lastPlayedAt ?: 0L }
                    .thenByDescending { it.score }
            )
            intent.sort == SmartSort.MOST_PLAYED -> filtered.sortedByDescending { stats.signals[it.track.id]?.completedRaw ?: 0 }
            intent.sort == SmartSort.RECENTLY_ADDED -> filtered.sortedByDescending { it.track.dateAdded }
            intent.sort == SmartSort.RANDOM -> filtered.shuffled(Random(seed))
            else -> filtered
        }

        val targetMs = intent.durationMinutes?.let { it * 60_000L }
        val picked = ArrayList<Recommendation>()
        var total = 0L
        for (r in ordered) {
            if (picked.size >= intent.limit) break
            if (targetMs != null && total >= targetMs) break
            picked += r
            total += r.track.durationMs
        }
        return GeneratedPlaylist(name, picked, intent, total)
    }

    private fun matchesLanguage(t: TrackEntity, language: LanguageFilter?): Boolean {
        if (language == null) return true
        val persian = SearchText.hasPersianScript(t.title) || SearchText.hasPersianScript(t.artist)
        return if (language == LanguageFilter.PERSIAN) persian else !persian
    }

    /** Mood OR energy when both are requested (they describe the same feeling); tracks not yet analysed never match. */
    private fun matchesMoodEnergy(t: TrackEntity, intent: PlaylistIntent): Boolean {
        val moodOk = intent.moods.isNotEmpty() && t.moodTag != null && t.moodTag in intent.moods
        val energyOk = intent.energy != null && intent.energy.contains(t.energyLevel)
        return when {
            intent.moods.isEmpty() && intent.energy == null -> true
            intent.moods.isNotEmpty() && intent.energy != null -> moodOk || energyOk
            intent.moods.isNotEmpty() -> moodOk
            else -> energyOk
        }
    }
}
