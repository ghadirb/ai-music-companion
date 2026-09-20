package com.ghadirb.aimusic.cloud

import android.content.Context
import com.ghadirb.aimusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The single, minimal HTTPS client for the gateway Worker. It holds NO provider secret: it only obtains a
 * short-lived anonymous session token and forwards small JSON requests. Network failures never throw —
 * they surface as [Response.code] == 0 so callers can fall back to on-device behaviour.
 */
class CloudApi(context: Context, private val baseUrl: String = BuildConfig.CLOUD_AI_BASE_URL) {

    data class Response(val code: Int, val body: JSONObject, val dailyRemaining: Int? = null, val dailyLimit: Int? = null) {
        val isSuccess: Boolean get() = code in 200..299
        val isOffline: Boolean get() = code == 0
        val error: String? get() = body.optString("error").takeIf { it.isNotBlank() }
    }

    private val prefs = context.applicationContext.getSharedPreferences(CloudConsent.PREFS, Context.MODE_PRIVATE)
    private val sessionLock = Mutex()

    /** Random per-install identifier (not derived from any device identifier). */
    private fun installationId(): String =
        prefs.getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALLATION_KEY, it).apply() }

    /** The `sub` claim the server will put in this install's tokens. */
    fun subject(): String = "anon:" + installationId()

    suspend fun post(path: String, body: JSONObject): Response = withContext(Dispatchers.IO) {
        try {
            var token = sessionToken(force = false) ?: return@withContext Response(SESSION_FAILED, JSONObject().put("error", "session_unavailable"))
            var response = rawPost(path, body, token)
            if (response.code == 401) {
                token = sessionToken(force = true) ?: return@withContext response
                response = rawPost(path, body, token)
            }
            response
        } catch (_: IOException) {
            Response(0, JSONObject())
        } catch (_: SecurityException) {
            Response(0, JSONObject())
        }
    }

    private suspend fun sessionToken(force: Boolean): String? = sessionLock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = prefs.getString(TOKEN_KEY, null)
            if (!force && !cached.isNullOrBlank() && prefs.getLong(EXPIRES_KEY, 0L) > now + 60_000L) return@withContext cached
            val session = rawPost("/v1/session/anonymous", JSONObject().put("installationId", installationId()), null)
            if (!session.isSuccess) return@withContext null
            val token = session.body.optString("accessToken").takeIf { it.isNotBlank() } ?: return@withContext null
            prefs.edit().putString(TOKEN_KEY, token)
                .putLong(EXPIRES_KEY, now + session.body.optLong("expiresInSeconds", 0L) * 1000L).apply()
            token
        }
    }

    private fun rawPost(path: String, body: JSONObject, token: String?): Response {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(
                code = code,
                body = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrDefault(JSONObject()),
                dailyRemaining = connection.getHeaderField("x-ai-daily-remaining")?.toIntOrNull(),
                dailyLimit = connection.getHeaderField("x-ai-daily-limit")?.toIntOrNull()
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val INSTALLATION_KEY = "installation_id"
        const val TOKEN_KEY = "session_token"
        const val EXPIRES_KEY = "session_expires_at"
        const val SESSION_FAILED = 503
    }
}
