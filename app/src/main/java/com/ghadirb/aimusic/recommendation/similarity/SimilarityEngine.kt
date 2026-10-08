package com.ghadirb.aimusic.recommendation.similarity

import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import kotlin.math.sqrt

/** Relative importance of each feature in [SimilarityEngine.trackSimilarity]. */
data class SimilarityWeights(
    val artist: Double = 0.22,
    val genre: Double = 0.18,
    val mood: Double = 0.14,
    val energy: Double = 0.14,
    val bpm: Double = 0.10,
    val album: Double = 0.06,
    val duration: Double = 0.06,
    val language: Double = 0.06,
    val year: Double = 0.04
)

/** Aggregated profile of one artist, built from the library (not from history). */
class ArtistProfile(
    val artist: String,
    val trackCount: Int,
    val genres: Map<String, Double>,
    val moods: Map<String, Double>,
    val energy: Double?,
    val bpm: Double?
)

/**
 * Spec §10 — similarity between tracks / artists / moods, always a score in 0..1.
 * Only features present on BOTH sides are compared; confidence shrinks the score when little
 * overlapping metadata exists, so two tracks with nothing in common metadata-wise are not "similar".
 */
class SimilarityEngine(private val w: SimilarityWeights = SimilarityWeights()) {

    fun trackSimilarity(a: TrackFeatureVector, b: TrackFeatureVector): Double {
        if (a.trackId == b.trackId) return 1.0
        var sum = 0.0
        var used = 0.0
        fun add(weight: Double, value: Double?) {
            if (value != null) { sum += weight * value; used += weight }
        }
        add(w.artist, if (a.artistId != null && b.artistId != null) (if (a.artistId == b.artistId) 1.0 else 0.0) else null)
        add(w.genre, if (a.genreId != null && b.genreId != null) (if (a.genreId == b.genreId) 1.0 else 0.0) else null)
        add(w.album, if (a.albumId != null && b.albumId != null) (if (a.albumId == b.albumId) 1.0 else 0.0) else null)
        add(w.mood, FeatureNormalizer.moodSimilarity(a.mood, b.mood))
        add(w.energy, if (a.energy != null && b.energy != null) FeatureNormalizer.closeness(a.energy, b.energy, 0.5) else null)
        add(w.bpm, if (a.bpm != null && b.bpm != null) FeatureNormalizer.closeness(a.bpm.toDouble(), b.bpm.toDouble(), 60.0) else null)
        add(
            w.duration,
            FeatureNormalizer.closeness(FeatureNormalizer.duration01(a.durationMs), FeatureNormalizer.duration01(b.durationMs), 0.6)
        )
        add(w.language, if (a.language != null && b.language != null) (if (a.language == b.language) 1.0 else 0.0) else null)
        add(w.year, if (a.year != null && b.year != null) FeatureNormalizer.closeness(a.year.toDouble(), b.year.toDouble(), 20.0) else null)
        if (used <= 0.0) return 0.0
        val confidence = (used / CONFIDENCE_FULL_AT).coerceAtMost(1.0)
        return ((sum / used) * confidence).coerceIn(0.0, 1.0)
    }

    /** Mood-only similarity (0..1); falls back to energy when a mood label is missing. */
    fun moodSimilarity(a: TrackFeatureVector, b: TrackFeatureVector): Double {
        FeatureNormalizer.moodSimilarity(a.mood, b.mood)?.let { return it }
        if (a.energy != null && b.energy != null) return FeatureNormalizer.closeness(a.energy, b.energy, 0.5)
        return 0.0
    }

    fun buildArtistProfiles(vectors: List<TrackFeatureVector>): Map<String, ArtistProfile> {
        val out = HashMap<String, ArtistProfile>()
        for ((id, list) in vectors.filter { it.artistId != null }.groupBy { it.artistId!! }) {
            val genres = HashMap<String, Double>()
            val moods = HashMap<String, Double>()
            for (v in list) {
                v.genreId?.let { genres.merge(it, 1.0, Double::plus) }
                v.mood?.let { moods.merge(FeatureNormalizer.key(it), 1.0, Double::plus) }
            }
            out[id] = ArtistProfile(
                artist = list.first().artist!!,
                trackCount = list.size,
                genres = normalizeSum(genres),
                moods = normalizeSum(moods),
                energy = list.mapNotNull { it.energy }.average().takeIf { !it.isNaN() },
                bpm = list.mapNotNull { it.bpm?.toDouble() }.average().takeIf { !it.isNaN() }
            )
        }
        return out
    }

    fun artistSimilarity(a: ArtistProfile, b: ArtistProfile): Double {
        if (a.artist == b.artist) return 1.0
        var sum = 0.0
        var used = 0.0
        if (a.genres.isNotEmpty() && b.genres.isNotEmpty()) { sum += 0.45 * cosine(a.genres, b.genres); used += 0.45 }
        if (a.moods.isNotEmpty() && b.moods.isNotEmpty()) { sum += 0.20 * cosine(a.moods, b.moods); used += 0.20 }
        if (a.energy != null && b.energy != null) { sum += 0.25 * FeatureNormalizer.closeness(a.energy, b.energy, 0.5); used += 0.25 }
        if (a.bpm != null && b.bpm != null) { sum += 0.10 * FeatureNormalizer.closeness(a.bpm, b.bpm, 60.0); used += 0.10 }
        if (used <= 0.0) return 0.0
        return ((sum / used) * (used / CONFIDENCE_FULL_AT).coerceAtMost(1.0)).coerceIn(0.0, 1.0)
    }

    /** Best similarity of [candidate] to any of [seeds] (0 if no seeds). */
    fun maxSimilarity(candidate: TrackFeatureVector, seeds: List<TrackFeatureVector>): Double {
        var best = 0.0
        for (s in seeds) {
            val sim = trackSimilarity(s, candidate)
            if (sim > best) best = sim
        }
        return best
    }

    private fun normalizeSum(m: Map<String, Double>): Map<String, Double> {
        val t = m.values.sum()
        return if (t <= 0.0) emptyMap() else m.mapValues { it.value / t }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        var dot = 0.0
        for ((k, v) in a) dot += v * (b[k] ?: 0.0)
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    private companion object {
        /** Overlapping-feature weight at which the score is fully trusted. */
        const val CONFIDENCE_FULL_AT = 0.5
    }
}
