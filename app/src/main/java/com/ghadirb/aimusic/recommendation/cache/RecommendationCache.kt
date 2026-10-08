package com.ghadirb.aimusic.recommendation.cache

import com.ghadirb.aimusic.data.local.entity.RecommendationCacheEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.Reason
import com.ghadirb.aimusic.recommendation.ReasonType
import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.section.RecommendationSection
import com.ghadirb.aimusic.recommendation.section.SectionType
import org.json.JSONArray
import org.json.JSONObject

/** One cached suggestion: just ids and the explanation, never the track metadata (that comes from Room). */
data class CachedItem(val trackId: Long, val score: Double, val source: String, val reasons: List<Reason>)

/** Spec §19 — what is persisted for one Home section. */
data class CachedSection(
    val type: SectionType,
    val items: List<CachedItem>,
    val generatedAt: Long,
    val algorithmVersion: String
)

/**
 * Spec §19/§20 — (de)serialisation of [CachedSection] to a Room row, plus the helpers that turn a
 * cached section back into a UI [RecommendationSection]. Corrupt or unknown rows are skipped (null),
 * never thrown, so a bad cache can only ever cost one recomputation.
 */
object RecommendationCacheCodec {

    fun toEntity(section: CachedSection): RecommendationCacheEntity = RecommendationCacheEntity(
        section = section.type.id,
        trackIds = section.items.joinToString(",") { it.trackId.toString() },
        itemsJson = encodeItems(section.items),
        generatedAt = section.generatedAt,
        algorithmVersion = section.algorithmVersion
    )

    fun fromEntity(entity: RecommendationCacheEntity): CachedSection? {
        val type = SectionType.fromId(entity.section) ?: return null
        val items = decodeItems(entity.itemsJson) ?: return null
        return CachedSection(type, items, entity.generatedAt, entity.algorithmVersion)
    }

    /** Rebuilds the UI model; tracks that no longer exist in the library are dropped. Null if nothing is left. */
    fun rebuild(cached: CachedSection, tracksById: Map<Long, TrackEntity>): RecommendationSection? {
        val items = cached.items.mapNotNull { item ->
            val track = tracksById[item.trackId] ?: return@mapNotNull null
            if (track.notInterested) null else Recommendation(track, item.score, item.reasons)
        }
        if (items.isEmpty()) return null
        return RecommendationSection(cached.type, cached.type.titleFa, cached.type.defaultReasonFa, items)
    }

    fun encodeItems(items: List<CachedItem>): String {
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject()
            o.put("t", item.trackId)
            o.put("s", item.score)
            o.put("src", item.source)
            val reasons = JSONArray()
            for (r in item.reasons) {
                val ro = JSONObject().put("y", r.type.name)
                r.detail?.let { ro.put("d", it) }
                r.number?.let { ro.put("n", it) }
                reasons.put(ro)
            }
            o.put("r", reasons)
            arr.put(o)
        }
        return arr.toString()
    }

    fun decodeItems(raw: String): List<CachedItem>? = runCatching {
        val arr = JSONArray(raw)
        val out = ArrayList<CachedItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val reasons = ArrayList<Reason>()
            val ra = o.optJSONArray("r")
            if (ra != null) {
                for (j in 0 until ra.length()) {
                    val ro = ra.getJSONObject(j)
                    // A reason type removed in a later app version just disappears instead of failing the whole row.
                    val type = runCatching { ReasonType.valueOf(ro.getString("y")) }.getOrNull() ?: continue
                    reasons += Reason(
                        type,
                        detail = if (ro.has("d")) ro.getString("d") else null,
                        number = if (ro.has("n")) ro.getInt("n") else null
                    )
                }
            }
            out += CachedItem(o.getLong("t"), o.optDouble("s", 0.0), o.optString("src", "UNKNOWN"), reasons)
        }
        out
    }.getOrNull()

    /** Cache validity (spec §19): same algorithm version, younger than [ttlMs], never from the future. */
    fun isFresh(generatedAt: Long, nowMs: Long, ttlMs: Long): Boolean = nowMs - generatedAt in 0..ttlMs
}
