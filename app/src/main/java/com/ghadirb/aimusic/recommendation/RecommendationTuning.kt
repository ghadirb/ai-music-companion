package com.ghadirb.aimusic.recommendation

import android.content.Context
import com.ghadirb.aimusic.recommendation.config.ExplorationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationWeights

/** How adventurous suggestions should be. Selecting anything other than BALANCED is a Premium feature (ADVANCED_RECOMMENDATION). */
enum class RecommendationTuning(val labelFa: String) {
    FAMILIAR("آشنا"), BALANCED("متعادل"), EXPLORE("کاوشگر");

    fun config(): ScoringConfig = when (this) {
        FAMILIAR -> ScoringConfig(favoriteWeight = 3.5, artistWeight = 3.0, exploreWeight = 0.1, rediscoverWeight = 1.0)
        BALANCED -> ScoringConfig()
        EXPLORE -> ScoringConfig(playWeight = 1.0, artistWeight = 1.8, genreWeight = 1.0, exploreWeight = 1.6, rediscoverWeight = 3.0)
    }

    /** The same three modes for the v2 engine: they move the exploitation/exploration split and a few weights. */
    fun smartConfig(): RecommendationConfig = when (this) {
        FAMILIAR -> RecommendationConfig(
            weights = RecommendationWeights(artist = 0.18, favorite = 0.09, completion = 0.10, discovery = 0.0),
            exploration = ExplorationConfig(0.85, 0.12, 0.03, 0.01, 0.10)
        )
        BALANCED -> RecommendationConfig()
        EXPLORE -> RecommendationConfig(
            weights = RecommendationWeights(favorite = 0.03, discovery = 0.06, similarity = 0.12),
            exploration = ExplorationConfig(0.50, 0.30, 0.20, 0.10, 0.40)
        )
    }
}

class TuningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recommendation_tuning", Context.MODE_PRIVATE)

    var selected: RecommendationTuning
        get() = runCatching { RecommendationTuning.valueOf(prefs.getString("mode", null) ?: "BALANCED") }.getOrDefault(RecommendationTuning.BALANCED)
        set(value) { prefs.edit().putString("mode", value.name).apply() }

    /** Non-entitled users always get the balanced defaults, even if a premium choice is still stored. */
    fun effective(entitled: Boolean): ScoringConfig = if (entitled) selected.config() else RecommendationTuning.BALANCED.config()

    /** Same rule for the v2 engine. */
    fun effectiveSmart(entitled: Boolean): RecommendationConfig =
        if (entitled) selected.smartConfig() else RecommendationTuning.BALANCED.smartConfig()
}
