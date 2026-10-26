package com.ghadirb.aimusic.recommendation.diversity

import com.ghadirb.aimusic.recommendation.config.DiversityConfig
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack
import kotlin.math.ceil

/**
 * Spec §13 — after ranking, keep the top of the list varied: at most [DiversityConfig.maxPerArtist]
 * tracks per artist and [DiversityConfig.maxPerAlbum] per album in the top [DiversityConfig.topN]
 * (optionally per genre). Limits adapt to small libraries: when there are fewer distinct artists/albums
 * than the caps would need, the cap is raised just enough so the list can still be filled.
 * Items are only re-ordered and never invented; unknown artist/album/genre are not counted.
 */
class DiversityReRanker(private val cfg: DiversityConfig = DiversityConfig()) {

    fun reRank(sorted: List<ScoredTrack>, limit: Int = sorted.size, topN: Int = cfg.topN): List<ScoredTrack> {
        if (sorted.size <= 1) return sorted.take(limit)
        val region = minOf(topN, limit, sorted.size)

        val maxArtist = adaptive(cfg.maxPerArtist, region, sorted.mapNotNull { it.vector.artistId }.distinct().size)
        val maxAlbum = adaptive(cfg.maxPerAlbum, region, sorted.mapNotNull { it.vector.albumId }.distinct().size)
        val maxGenre = cfg.maxPerGenre?.let { adaptive(it, region, sorted.mapNotNull { s -> s.vector.genreId }.distinct().size) }

        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<String, Int>()
        val genreCount = HashMap<String, Int>()
        val selected = ArrayList<ScoredTrack>(region)
        val chosen = HashSet<Long>()

        fun fits(s: ScoredTrack): Boolean {
            val artist = s.vector.artistId
            val album = s.vector.albumId
            val genre = s.vector.genreId
            if (artist != null && (artistCount[artist] ?: 0) >= maxArtist) return false
            if (album != null && (albumCount[album] ?: 0) >= maxAlbum) return false
            if (maxGenre != null && genre != null && (genreCount[genre] ?: 0) >= maxGenre) return false
            return true
        }
        fun take(s: ScoredTrack) {
            val v = s.vector
            v.artistId?.let { artistCount.merge(it, 1, Int::plus) }
            v.albumId?.let { albumCount.merge(it, 1, Int::plus) }
            v.genreId?.let { genreCount.merge(it, 1, Int::plus) }
            selected += s
            chosen += s.trackId
        }

        for (s in sorted) {
            if (selected.size >= region) break
            if (fits(s)) take(s)
        }
        // Not enough "clean" items: relax the caps rather than return a short list.
        if (selected.size < region) {
            for (s in sorted) {
                if (selected.size >= region) break
                if (s.trackId !in chosen) take(s)
            }
        }
        val rest = sorted.filter { it.trackId !in chosen }
        return (selected + rest).take(limit)
    }

    /** Smallest cap that still lets [region] slots be filled from [distinct] groups, never below [base]. */
    private fun adaptive(base: Int, region: Int, distinct: Int): Int {
        if (distinct <= 0) return Int.MAX_VALUE
        return maxOf(base, ceil(region.toDouble() / distinct).toInt())
    }
}
