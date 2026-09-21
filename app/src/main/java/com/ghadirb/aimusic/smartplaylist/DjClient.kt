package com.ghadirb.aimusic.smartplaylist

import com.ghadirb.aimusic.cloud.CloudApi
import com.ghadirb.aimusic.cloud.CloudConsent
import com.ghadirb.aimusic.library.EnergyBand
import org.json.JSONObject

/** Maps the gateway's (already server-sanitised) JSON intent into a [PlaylistIntent], sanitising again on-device. */
object DjIntentMapper {
    /**
     * Language models tend to infer "Persian music" from a Persian-language request. The language filter is only kept
     * when the user explicitly asked for Persian/foreign music, which the deterministic local parser detects.
     */
    fun withExplicitLanguageOnly(intent: PlaylistIntent, prompt: String): PlaylistIntent =
        intent.copy(language = PlaylistIntentParser.parse(prompt).language)

    fun fromJson(json: JSONObject, currentTrackId: Long?): PlaylistIntent {
        val moods = json.optJSONArray("moods")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty().toSet()
        val energy = when (json.optString("energy")) {
            "low" -> EnergyBand.LOW
            "medium" -> EnergyBand.MEDIUM
            "high" -> EnergyBand.HIGH
            else -> null
        }
        val language = when (json.optString("language")) {
            "persian" -> LanguageFilter.PERSIAN
            "non_persian" -> LanguageFilter.NON_PERSIAN
            else -> null
        }
        val sort = when (json.optString("sort")) {
            "least_played" -> SmartSort.LEAST_PLAYED
            "most_played" -> SmartSort.MOST_PLAYED
            "recently_added" -> SmartSort.RECENTLY_ADDED
            "random" -> SmartSort.RANDOM
            else -> SmartSort.BEST_MATCH
        }
        fun optIntOrNull(key: String): Int? = if (json.isNull(key) || !json.has(key)) null else json.optInt(key).takeIf { it > 0 }
        fun optStringOrNull(key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
        return PlaylistIntent(
            moods = moods,
            energy = energy,
            genre = optStringOrNull("genre"),
            artist = optStringOrNull("artist"),
            language = language,
            durationMinutes = optIntOrNull("duration_minutes"),
            excludeRecentDays = optIntOrNull("exclude_recent_days"),
            favoriteOnly = json.optBoolean("favorite_only", false),
            similarToTrackId = if (json.optBoolean("similar_to_current", false)) currentTrackId else null,
            sort = sort,
            limit = optIntOrNull("limit") ?: PlaylistIntent.DEFAULT_LIMIT,
            title = optStringOrNull("title"),
            bpmMin = optIntOrNull("bpm_min"),
            bpmMax = optIntOrNull("bpm_max"),
            exploration = when (json.optString("exploration")) {
                "low" -> Exploration.LOW
                "medium" -> Exploration.MEDIUM
                "high" -> Exploration.HIGH
                else -> null
            },
            energyShift = json.optInt("energy_shift", 0)
        ).sanitized()
    }
}

/**
 * AI DJ (Premium): sends ONLY the user's typed request to the gateway and receives a structured intent.
 * Track selection then happens locally, so the AI never sees or chooses the user's files.
 */
class DjClient(private val api: CloudApi, private val consent: CloudConsent) {

    sealed interface Result {
        data class Success(val intent: PlaylistIntent, val remaining: Int?) : Result
        data object ConsentRequired : Result
        data object PremiumRequired : Result
        data object QuotaReached : Result
        data object Offline : Result
        data object Unavailable : Result
    }

    suspend fun intentFor(prompt: String, currentTrackId: Long?): Result {
        if (!consent.enabled) return Result.ConsentRequired
        val response = api.post("/v1/dj/intent", JSONObject().put("prompt", prompt.take(300)))
        return when {
            response.isOffline -> Result.Offline
            response.code == 403 -> Result.PremiumRequired
            response.code == 429 -> Result.QuotaReached
            !response.isSuccess -> Result.Unavailable
            else -> response.body.optJSONObject("intent")
                ?.let { Result.Success(DjIntentMapper.withExplicitLanguageOnly(DjIntentMapper.fromJson(it, currentTrackId), prompt), response.dailyRemaining) }
                ?: Result.Unavailable
        }
    }
}
