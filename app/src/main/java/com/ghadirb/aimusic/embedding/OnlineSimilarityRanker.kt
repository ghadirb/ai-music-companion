package com.ghadirb.aimusic.embedding

import android.content.Context
import com.ghadirb.aimusic.cloud.CloudApi
import com.ghadirb.aimusic.cloud.CloudConsent
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Optional, consent-gated semantic reranking for a small local candidate set. */
class OnlineSimilarityRanker(context: Context, private val api: CloudApi = CloudApi(context)) {
    private val consent = CloudConsent(context)

    suspend fun rank(source: TrackEntity, candidates: List<TrackEntity>): Result = withContext(Dispatchers.Default) {
        if (!consent.enabled) return@withContext Result.ConsentRequired
        if (candidates.isEmpty()) return@withContext Result.Success(emptyList(), null)
        val documents = listOf(source) + candidates
        val request = JSONObject()
            .put("model", EmbeddingModel.TEXT_SMALL.id)
            .put("input", JSONArray(documents.map { it.toPrivacySafeEmbeddingDocument().text }))
        val response = api.post("/v1/music-embedding", request)
        when {
            response.code == 429 -> return@withContext Result.QuotaReached
            !response.isSuccess -> return@withContext Result.Error("سرویس هوشمند در دسترس نیست؛ پیشنهاد محلی همچنان فعال است.")
        }
        try {
            val data = response.body.getJSONArray("data")
            val vectors = List(data.length()) { index -> data.getJSONObject(index).getJSONArray("embedding").toFloatArray() }
            if (vectors.size != documents.size) return@withContext Result.Error("پاسخ مدل کامل نیست.")
            val ranked = candidates.zip(vectors.drop(1))
                .sortedByDescending { (_, vector) -> cosine(vectors.first(), vector) }
                .map { it.first }
            Result.Success(ranked, response.dailyRemaining)
        } catch (_: Exception) {
            Result.Error("پاسخ سرویس هوشمند نامعتبر بود.")
        }
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun cosine(left: FloatArray, right: FloatArray): Double {
        if (left.size != right.size) return 0.0
        var dot = 0.0; var leftLength = 0.0; var rightLength = 0.0
        for (i in left.indices) { dot += left[i] * right[i]; leftLength += left[i] * left[i]; rightLength += right[i] * right[i] }
        return if (leftLength == 0.0 || rightLength == 0.0) 0.0 else dot / sqrt(leftLength * rightLength)
    }

    sealed interface Result {
        data class Success(val tracks: List<TrackEntity>, val remaining: Int?) : Result
        data object ConsentRequired : Result
        data object QuotaReached : Result
        data class Error(val message: String) : Result
    }

    companion object {
        const val PREFS = CloudConsent.PREFS
        const val CONSENT_KEY = CloudConsent.KEY
    }
}
