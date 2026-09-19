package com.ghadirb.aimusic.embedding

import android.content.Context
import com.ghadirb.aimusic.BuildConfig
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.sqrt

/** Optional, consent-gated semantic reranking for a small local candidate set. */
class OnlineSimilarityRanker(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun rank(source: TrackEntity, candidates: List<TrackEntity>): Result = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean(CONSENT_KEY, false)) return@withContext Result.ConsentRequired
        if (candidates.isEmpty()) return@withContext Result.Success(emptyList(), null)
        try {
            val documents = listOf(source) + candidates
            val token = sessionToken()
            val request = JSONObject()
                .put("model", EmbeddingModel.TEXT_SMALL.id)
                .put("input", JSONArray(documents.map { it.toPrivacySafeEmbeddingDocument().text }))
            val response = post("/v1/music-embedding", request, token)
            if (response.code == 429) return@withContext Result.QuotaReached
            if (response.code !in 200..299) return@withContext Result.Error("سرویس هوشمند در دسترس نیست.")
            val vectors = response.body.getJSONArray("data").let { data ->
                List(data.length()) { index ->
                    data.getJSONObject(index).getJSONArray("embedding").toFloatArray()
                }
            }
            if (vectors.size != documents.size) return@withContext Result.Error("پاسخ مدل کامل نیست.")
            val ranked = candidates.zip(vectors.drop(1))
                .sortedByDescending { (_, vector) -> cosine(vectors.first(), vector) }
                .map { it.first }
            Result.Success(ranked, response.remaining)
        } catch (_: Exception) {
            Result.Error("ارتباط با AI انجام نشد؛ پیشنهاد محلی همچنان در دسترس است.")
        }
    }

    private fun sessionToken(): String {
        val now = System.currentTimeMillis()
        val cached = preferences.getString(TOKEN_KEY, null)
        val expiresAt = preferences.getLong(EXPIRES_KEY, 0L)
        if (!cached.isNullOrBlank() && expiresAt > now + 60_000L) return cached
        val installationId = preferences.getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also { preferences.edit().putString(INSTALLATION_KEY, it).apply() }
        val session = post("/v1/session/anonymous", JSONObject().put("installationId", installationId), null)
        check(session.code in 200..299) { "Session unavailable" }
        val token = session.body.getString("accessToken")
        val expires = session.body.optLong("expiresInSeconds", 0L)
        preferences.edit().putString(TOKEN_KEY, token).putLong(EXPIRES_KEY, now + expires * 1000L).apply()
        return token
    }

    private fun post(path: String, body: JSONObject, token: String?): HttpResponse {
        val connection = (URL(BuildConfig.CLOUD_AI_BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val remaining = connection.getHeaderField("x-ai-daily-remaining")?.toIntOrNull()
        connection.disconnect()
        return HttpResponse(code, JSONObject(text.ifBlank { "{}" }), remaining)
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun cosine(left: FloatArray, right: FloatArray): Double {
        var dot = 0.0; var leftLength = 0.0; var rightLength = 0.0
        for (i in left.indices) { dot += left[i] * right[i]; leftLength += left[i] * left[i]; rightLength += right[i] * right[i] }
        return if (leftLength == 0.0 || rightLength == 0.0) 0.0 else dot / sqrt(leftLength * rightLength)
    }

    private data class HttpResponse(val code: Int, val body: JSONObject, val remaining: Int?)
    sealed interface Result {
        data class Success(val tracks: List<TrackEntity>, val remaining: Int?) : Result
        data object ConsentRequired : Result
        data object QuotaReached : Result
        data class Error(val message: String) : Result
    }
    companion object {
        const val PREFS = "cloud_ai_preferences"
        const val CONSENT_KEY = "cloud_ai_consent"
        private const val INSTALLATION_KEY = "installation_id"
        private const val TOKEN_KEY = "session_token"
        private const val EXPIRES_KEY = "session_expires_at"
    }
}
