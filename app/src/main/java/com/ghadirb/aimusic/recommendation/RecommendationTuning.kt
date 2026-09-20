package com.ghadirb.aimusic.recommendation

import android.content.Context

/** How adventurous suggestions should be. Selecting anything other than BALANCED is a Premium feature (ADVANCED_RECOMMENDATION). */
enum class RecommendationTuning(val labelFa: String) {
    FAMILIAR("آشنا"), BALANCED("متعادل"), EXPLORE("کاوشگر");

    fun config(): ScoringConfig = when (this) {
        FAMILIAR -> ScoringConfig(favoriteWeight = 3.5, artistWeight = 3.0, exploreWeight = 0.1, rediscoverWeight = 1.0)
        BALANCED -> ScoringConfig()
        EXPLORE -> ScoringConfig(playWeight = 1.0, artistWeight = 1.8, genreWeight = 1.0, exploreWeight = 1.6, rediscoverWeight = 3.0)
    }
}

class TuningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recommendation_tuning", Context.MODE_PRIVATE)

    var selected: RecommendationTuning
        get() = runCatching { RecommendationTuning.valueOf(prefs.getString("mode", null) ?: "BALANCED") }.getOrDefault(RecommendationTuning.BALANCED)
        set(value) { prefs.edit().putString("mode", value.name).apply() }

    /** Non-entitled users always get the balanced defaults, even if a premium choice is still stored. */
    fun effective(entitled: Boolean): ScoringConfig = if (entitled) selected.config() else RecommendationTuning.BALANCED.config()
}
