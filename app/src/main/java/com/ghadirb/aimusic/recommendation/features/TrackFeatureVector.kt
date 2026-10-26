package com.ghadirb.aimusic.recommendation.features

import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.signals.ListeningSignals
import com.ghadirb.aimusic.recommendation.signals.SignalAggregator
import com.ghadirb.aimusic.recommendation.signals.TrackSignal
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ln

/**
 * Spec §2 — one standard feature vector per track. Every metadata field is nullable; a missing
 * value means "unknown" and is simply ignored by the scorers (never a crash, never a fake number).
 */
data class TrackFeatureVector(
    val trackId: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    /** Not scanned from MediaStore yet → always null today; the profile/similarity code already supports it. */
    val year: Int?,
    val durationMs: Long,
    val bpm: Int?,
    val energy: Double?,
    val mood: String?,
    /** Coarse script-based language ("fa" = Arabic/Persian script, "latin"); null if undetectable. */
    val language: String?,
    /** Local popularity: plays / plays of the most-played track. 0..1 */
    val popularity: Double,
    val playCount: Int,
    /** null = never played (do not confuse with 0 % completion). */
    val completionRate: Double?,
    val skipRate: Double?,
    val replayRate: Double?,
    val favorite: Boolean,
    val lastPlayedAt: Long?,
    val dateAdded: Long,
    val morningAffinity: Double?,
    val afternoonAffinity: Double?,
    val eveningAffinity: Double?,
    val nightAffinity: Double?,
    val weekdayAffinity: Double?,
    val weekendAffinity: Double?,
    val notInterested: Boolean,
    val signal: TrackSignal?
) {
    val artistId: String? get() = artist?.let(FeatureNormalizer::key)
    val albumId: String? get() = album?.let(FeatureNormalizer::key)
    val genreId: String? get() = genre?.let(FeatureNormalizer::key)
    val isUnplayed: Boolean get() = playCount == 0
    val hasAudioFeatures: Boolean get() = energy != null || bpm != null || mood != null

    fun daypartAffinity(bucket: String): Double? = when (bucket) {
        TimeBuckets.MORNING -> morningAffinity
        TimeBuckets.AFTERNOON -> afternoonAffinity
        TimeBuckets.EVENING -> eveningAffinity
        else -> nightAffinity
    }
}

object FeatureNormalizer {
    const val UNKNOWN_ARTIST = "Unknown artist"
    const val UNKNOWN_ALBUM = "Unknown album"
    private const val BPM_LO = 60.0
    private const val BPM_HI = 180.0

    fun key(s: String): String = s.trim().lowercase()

    fun bpm01(bpm: Int?): Double? = bpm?.let { ((it - BPM_LO) / (BPM_HI - BPM_LO)).coerceIn(0.0, 1.0) }

    /** Log scale between 1 and 10 minutes. */
    fun duration01(ms: Long): Double {
        val sec = (ms / 1000.0).coerceAtLeast(1.0)
        return ((ln(sec) - ln(60.0)) / (ln(600.0) - ln(60.0))).coerceIn(0.0, 1.0)
    }

    fun decade(year: Int?): Int? = year?.takeIf { it in 1900..2100 }?.let { it / 10 * 10 }

    /** 1 = identical, 0 = at least [tolerance] apart. */
    fun closeness(a: Double, b: Double, tolerance: Double): Double =
        (1.0 - abs(a - b) / tolerance).coerceIn(0.0, 1.0)

    /** (valence, arousal) of the labels produced by AudioAnalyzer; null for unknown labels. */
    fun moodCoordinates(tag: String?): Pair<Double, Double>? = when (tag?.trim()?.lowercase()) {
        "calm" -> 0.55 to 0.15
        "sad" -> 0.10 to 0.25
        "neutral" -> 0.50 to 0.50
        "happy" -> 0.90 to 0.65
        "energetic" -> 0.70 to 0.95
        else -> null
    }

    /** 0..1 similarity of two mood labels via their (valence, arousal) distance. Null if either is unknown. */
    fun moodSimilarity(a: String?, b: String?): Double? {
        val ca = moodCoordinates(a) ?: return null
        val cb = moodCoordinates(b) ?: return null
        val dv = ca.first - cb.first
        val da = ca.second - cb.second
        return (1.0 - kotlin.math.sqrt(dv * dv + da * da) / 1.1).coerceIn(0.0, 1.0)
    }
}

object LanguageDetector {
    fun detect(vararg texts: String?): String? {
        var arabic = 0
        var latin = 0
        for (t in texts) {
            if (t == null) continue
            for (ch in t) {
                when {
                    ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF' -> arabic++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                }
            }
        }
        return when {
            arabic == 0 && latin == 0 -> null
            arabic >= latin -> "fa"
            else -> "latin"
        }
    }
}

class FeatureSet(val vectors: List<TrackFeatureVector>, val signals: ListeningSignals) {
    val byId: Map<Long, TrackFeatureVector> = vectors.associateBy { it.trackId }
    val hasHistory: Boolean get() = signals.hasHistory
}

/** Builds [TrackFeatureVector]s from the library + listening history. */
class FeatureExtractor(private val config: RecommendationConfig = RecommendationConfig()) {
    private val aggregator = SignalAggregator(config)

    fun extract(
        tracks: List<TrackEntity>,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): FeatureSet {
        val signals = aggregator.aggregate(tracks.mapTo(HashSet()) { it.id }, history, nowMs, zone)
        val maxPlays = signals.maxPlays.coerceAtLeast(1)
        val vectors = tracks.map { t -> vectorOf(t, signals[t.id], maxPlays) }
        return FeatureSet(vectors, signals)
    }

    private fun vectorOf(t: TrackEntity, s: TrackSignal?, maxPlays: Int): TrackFeatureVector {
        fun share(bucket: String): Double? {
            if (s == null) return null
            val total = s.daypartCompletions.values.sum()
            return if (total <= 0.0) null else (s.daypartCompletions[bucket] ?: 0.0) / total
        }
        val dayTypeTotal = (s?.weekdayCompletions ?: 0.0) + (s?.weekendCompletions ?: 0.0)
        return TrackFeatureVector(
            trackId = t.id,
            title = t.title,
            artist = t.artist.takeIf { it.isNotBlank() && it != FeatureNormalizer.UNKNOWN_ARTIST },
            album = t.album.takeIf { it.isNotBlank() && it != FeatureNormalizer.UNKNOWN_ALBUM },
            genre = t.genre?.takeIf { it.isNotBlank() },
            year = null,
            durationMs = t.durationMs,
            bpm = t.bpm,
            energy = t.energyLevel?.toDouble(),
            mood = t.moodTag,
            language = LanguageDetector.detect(t.title, t.artist),
            popularity = if (s == null) 0.0 else s.plays.toDouble() / maxPlays,
            playCount = s?.plays ?: 0,
            completionRate = s?.completionRate,
            skipRate = s?.skipRate,
            replayRate = s?.replayRate,
            favorite = t.isFavorite,
            lastPlayedAt = s?.lastPlayedAt,
            dateAdded = t.dateAdded,
            morningAffinity = share(TimeBuckets.MORNING),
            afternoonAffinity = share(TimeBuckets.AFTERNOON),
            eveningAffinity = share(TimeBuckets.EVENING),
            nightAffinity = share(TimeBuckets.NIGHT),
            weekdayAffinity = if (dayTypeTotal > 0.0) s!!.weekdayCompletions / dayTypeTotal else null,
            weekendAffinity = if (dayTypeTotal > 0.0) s!!.weekendCompletions / dayTypeTotal else null,
            notInterested = t.notInterested,
            signal = s
        )
    }
}
