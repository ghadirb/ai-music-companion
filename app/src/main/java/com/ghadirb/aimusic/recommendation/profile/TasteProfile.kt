package com.ghadirb.aimusic.recommendation.profile

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.features.FeatureNormalizer
import com.ghadirb.aimusic.recommendation.features.FeatureSet
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.signals.RecencyDecay
import com.ghadirb.aimusic.recommendation.signals.SkipModel
import com.ghadirb.aimusic.recommendation.signals.TimeContext
import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Weighted normal distribution of a numeric preference (energy, BPM, duration …). */
data class Gaussian(val mean: Double, val spread: Double, val weight: Double) {
    /** 1 at the mean, falling off with distance; [minSpread] keeps a single-sample profile from being a spike. */
    fun match(x: Double, minSpread: Double): Double {
        val s = max(spread, minSpread)
        val z = (x - mean) / s
        return exp(-0.5 * z * z)
    }
}

/** What the user listens to in one slice of time (a daypart, weekdays or weekends). */
data class SliceTaste(
    /** Recency-decayed number of completed plays in the slice — the evidence behind the rest. */
    val weight: Double,
    /** Share of all completed listening (0..1). */
    val share: Double,
    val energy: Gaussian?,
    /** Distributions (sum = 1). */
    val moods: Map<String, Double>,
    val genres: Map<String, Double>
)

/**
 * Spec §7 — multi-dimensional taste profile learned from real behaviour. Affinity maps are scaled so
 * the strongest entry is 1.0 (keys are normalised lower-case names).
 */
data class TasteProfile(
    val artists: Map<String, Double> = emptyMap(),
    val genres: Map<String, Double> = emptyMap(),
    val moods: Map<String, Double> = emptyMap(),
    val languages: Map<String, Double> = emptyMap(),
    /** Decade → affinity. Empty until release years are available. */
    val eras: Map<Int, Double> = emptyMap(),
    val energy: Gaussian? = null,
    val bpm: Gaussian? = null,
    /** Preferred length in minutes. */
    val durationMin: Gaussian? = null,
    /** Keyed by TimeBuckets.MORNING/AFTERNOON/EVENING/NIGHT. */
    val dayparts: Map<String, SliceTaste> = emptyMap(),
    val weekday: SliceTaste? = null,
    val weekend: SliceTaste? = null,
    /** Number of tracks the user demonstrably likes. 0 = nothing learned yet (cold start). */
    val sampleSize: Int = 0,
    val updatedAt: Long = 0L,
    val algorithmVersion: String = ""
) {
    val isEmpty: Boolean get() = sampleSize == 0

    fun artistAffinity(v: TrackFeatureVector): Double = v.artistId?.let { artists[it] } ?: 0.0
    fun genreAffinity(v: TrackFeatureVector): Double = v.genreId?.let { genres[it] } ?: 0.0
}

/** How much the user "loves" a track (>0) — shared by profile building and seed selection. */
object TrackAffinity {
    fun love(v: TrackFeatureVector): Double {
        if (v.notInterested) return 0.0
        var w = if (v.favorite) 1.5 else 0.0
        val s = v.signal
        if (s != null) {
            w += min(s.decayedCompletions, 6.0) * 0.5
            w += s.replayScore * 0.8
            w -= s.negativeScore * 2.0
        }
        return max(0.0, w)
    }
}

/** Spec §7/§8: builds a [TasteProfile] from features + history. Pure, deterministic, on-device. */
class TasteProfileCalculator(private val config: RecommendationConfig = RecommendationConfig()) {
    private val decay = RecencyDecay(config.recency)
    private val skipModel = SkipModel(config.skip)

    private class Stats {
        var w = 0.0
        var sum = 0.0
        var sum2 = 0.0
        fun add(x: Double, weight: Double) { w += weight; sum += weight * x; sum2 += weight * x * x }
        fun gaussian(): Gaussian? {
            if (w <= 0.0) return null
            val mean = sum / w
            val variance = max(0.0, sum2 / w - mean * mean)
            return Gaussian(mean, sqrt(variance), w)
        }
    }

    private class Slice {
        var weight = 0.0
        val energy = Stats()
        val moods = HashMap<String, Double>()
        val genres = HashMap<String, Double>()
    }

    fun build(
        features: FeatureSet,
        history: List<ListeningHistoryEntity>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): TasteProfile {
        val loved = features.vectors.map { it to TrackAffinity.love(it) }.filter { it.second > 0.0 }

        val artists = HashMap<String, Double>()
        val genres = HashMap<String, Double>()
        val moods = HashMap<String, Double>()
        val languages = HashMap<String, Double>()
        val eras = HashMap<String, Double>()
        val energy = Stats()
        val bpm = Stats()
        val duration = Stats()
        for ((v, w) in loved) {
            v.artistId?.let { artists.merge(it, w, Double::plus) }
            v.genreId?.let { genres.merge(it, w, Double::plus) }
            v.mood?.let { moods.merge(FeatureNormalizer.key(it), w, Double::plus) }
            v.language?.let { languages.merge(it, w, Double::plus) }
            FeatureNormalizer.decade(v.year)?.let { eras.merge(it.toString(), w, Double::plus) }
            v.energy?.let { energy.add(it, w) }
            v.bpm?.let { bpm.add(it.toDouble(), w) }
            duration.add(v.durationMs / 60_000.0, w)
        }

        // ---- time-of-day / weekday-weekend, learned from completed plays in the history ----
        val dayparts = HashMap<String, Slice>()
        val weekday = Slice()
        val weekend = Slice()
        var total = 0.0
        for (e in history) {
            val v = features.byId[e.trackId] ?: continue
            if (v.notInterested || !skipModel.isCompleted(e)) continue
            val w = decay.weightForAge((nowMs - e.startTime).coerceAtLeast(0L))
            total += w
            val daypart = dayparts.getOrPut(TimeBuckets.bucketOf(e.startTime, zone)) { Slice() }
            val dayType = if (TimeContext.isWeekend(e.startTime, zone, config.context.weekendDays)) weekend else weekday
            for (p in listOf(daypart, dayType)) {
                p.weight += w
                v.energy?.let { p.energy.add(it, w) }
                v.mood?.let { p.moods.merge(FeatureNormalizer.key(it), w, Double::plus) }
                v.genreId?.let { p.genres.merge(it, w, Double::plus) }
            }
        }
        val totalWeight = total
        fun finish(s: Slice): SliceTaste? =
            if (s.weight <= 0.0) null
            else SliceTaste(
                s.weight, if (totalWeight > 0.0) s.weight / totalWeight else 0.0,
                s.energy.gaussian(), sumToOne(s.moods), sumToOne(s.genres)
            )

        val daypartTastes = HashMap<String, SliceTaste>()
        for ((k, s) in dayparts) finish(s)?.let { daypartTastes[k] = it }

        return TasteProfile(
            artists = scaleToMax(artists), genres = scaleToMax(genres), moods = scaleToMax(moods),
            languages = scaleToMax(languages),
            eras = scaleToMax(eras).mapKeys { it.key.toInt() },
            energy = energy.gaussian(), bpm = bpm.gaussian(), durationMin = duration.gaussian(),
            dayparts = daypartTastes,
            weekday = finish(weekday), weekend = finish(weekend),
            sampleSize = loved.size, updatedAt = nowMs, algorithmVersion = config.algorithmVersion
        )
    }

    private fun scaleToMax(m: Map<String, Double>): Map<String, Double> {
        val top = m.values.maxOrNull() ?: return emptyMap()
        return if (top <= 0.0) emptyMap() else m.mapValues { it.value / top }
    }

    private fun sumToOne(m: Map<String, Double>): Map<String, Double> {
        val t = m.values.sum()
        return if (t <= 0.0) emptyMap() else m.mapValues { it.value / t }
    }
}

/** Compact JSON (de)serialisation so the profile can live in one `user_preference.profileJson` column. */
object TasteProfileCodec {
    fun encode(p: TasteProfile): String {
        val o = JSONObject()
        o.put("v", p.algorithmVersion)
        o.put("n", p.sampleSize)
        o.put("t", p.updatedAt)
        o.put("ar", mapJson(p.artists))
        o.put("ge", mapJson(p.genres))
        o.put("mo", mapJson(p.moods))
        o.put("la", mapJson(p.languages))
        o.put("er", mapJson(p.eras.mapKeys { it.key.toString() }))
        p.energy?.let { o.put("en", gJson(it)) }
        p.bpm?.let { o.put("bp", gJson(it)) }
        p.durationMin?.let { o.put("du", gJson(it)) }
        val dp = JSONObject()
        for ((k, s) in p.dayparts) dp.put(k, sJson(s))
        o.put("dp", dp)
        p.weekday?.let { o.put("wd", sJson(it)) }
        p.weekend?.let { o.put("we", sJson(it)) }
        return o.toString()
    }

    fun decode(raw: String): TasteProfile? {
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val dpObj = o.optJSONObject("dp")
            val dayparts = HashMap<String, SliceTaste>()
            if (dpObj != null) {
                for (k in dpObj.keys()) sFrom(dpObj.optJSONObject(k))?.let { dayparts[k] = it }
            }
            TasteProfile(
                artists = mapFrom(o.optJSONObject("ar")),
                genres = mapFrom(o.optJSONObject("ge")),
                moods = mapFrom(o.optJSONObject("mo")),
                languages = mapFrom(o.optJSONObject("la")),
                eras = mapFrom(o.optJSONObject("er")).mapKeys { it.key.toInt() },
                energy = gFrom(o.optJSONObject("en")),
                bpm = gFrom(o.optJSONObject("bp")),
                durationMin = gFrom(o.optJSONObject("du")),
                dayparts = dayparts,
                weekday = sFrom(o.optJSONObject("wd")),
                weekend = sFrom(o.optJSONObject("we")),
                sampleSize = o.optInt("n", 0),
                updatedAt = o.optLong("t", 0L),
                algorithmVersion = o.optString("v", "")
            )
        }.getOrNull()
    }

    private fun mapJson(m: Map<String, Double>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun mapFrom(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Double>()
        for (k in o.keys()) out[k] = o.optDouble(k, 0.0)
        return out
    }

    private fun gJson(g: Gaussian): JSONObject = JSONObject().put("m", g.mean).put("s", g.spread).put("w", g.weight)

    private fun gFrom(o: JSONObject?): Gaussian? =
        if (o == null) null else Gaussian(o.optDouble("m", 0.0), o.optDouble("s", 0.0), o.optDouble("w", 0.0))

    private fun sJson(s: SliceTaste): JSONObject {
        val o = JSONObject().put("w", s.weight).put("sh", s.share)
        s.energy?.let { o.put("e", gJson(it)) }
        o.put("mo", mapJson(s.moods))
        o.put("ge", mapJson(s.genres))
        return o
    }

    private fun sFrom(o: JSONObject?): SliceTaste? =
        if (o == null) null
        else SliceTaste(
            o.optDouble("w", 0.0), o.optDouble("sh", 0.0), gFrom(o.optJSONObject("e")),
            mapFrom(o.optJSONObject("mo")), mapFrom(o.optJSONObject("ge"))
        )
}
