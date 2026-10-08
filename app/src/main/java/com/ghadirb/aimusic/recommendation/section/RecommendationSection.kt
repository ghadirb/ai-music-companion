package com.ghadirb.aimusic.recommendation.section

import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.recommendation.candidate.CandidateSourceId
import com.ghadirb.aimusic.recommendation.config.ExplorationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationWeights
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.profile.TasteProfile

/** Spec §17 — Home sections. Each one has its OWN strategy (see [SectionStrategies]), not just another title. */
enum class SectionType(val id: String, val titleFa: String, val defaultReasonFa: String) {
    FOR_YOU("for_you", "برای شما", "بر اساس سلیقه و رفتار شنیداری شما"),
    BECAUSE_YOU_LIKE("because_you_like", "چون این‌ها را دوست دارید", "مشابه آهنگ‌هایی که زیاد گوش می‌دهید"),
    TONIGHT("tonight", "مخصوص امشب", "آهنگ‌های مناسب شب، هماهنگ با عادت‌های شما"),
    DRIVING("driving", "برای رانندگی", "پرانرژی و آشنا؛ بدون ریسک برای مسیر"),
    DISCOVER("discover", "کشف جدید", "آهنگ‌هایی که هنوز گوش نداده‌اید ولی به سلیقهٔ شما نزدیک‌اند"),
    SIMILAR_TO_RECENT("similar_recent", "مشابه آهنگ‌های اخیر", "ادامهٔ همان حال‌وهوای شنیده‌های اخیرتان");

    companion object {
        fun fromId(id: String): SectionType? = values().firstOrNull { it.id == id }
    }
}

/** What the UI receives (spec §28): a titled list with a section-level reason; items carry per-track reasons. */
data class RecommendationSection(
    val type: SectionType,
    val title: String,
    val reason: String,
    val items: List<Recommendation>
)

enum class SeedMode { LOVED, RECENT }

/** Concrete settings of one section for one run. */
class SectionPlan(
    val type: SectionType,
    val weights: RecommendationWeights,
    val exploration: ExplorationConfig,
    /** null = all candidate sources. */
    val allowedSources: Set<CandidateSourceId>?,
    val seedMode: SeedMode,
    /** The section is meaningless without seeds (e.g. "because you like these") → hidden in cold start. */
    val requiresSeeds: Boolean,
    /** null = the daypart of "now". */
    val daypart: String?,
    /** null = learned/prior energy of the daypart. */
    val energyTarget: Double?,
    /** Seeds themselves never appear in the section when true. */
    val excludeSeeds: Boolean,
    val eligible: (TrackFeatureVector) -> Boolean
)

object SectionStrategies {

    fun plan(type: SectionType, cfg: RecommendationConfig, profile: TasteProfile): SectionPlan {
        val base = cfg.weights
        return when (type) {
            SectionType.FOR_YOU -> SectionPlan(
                type, base.normalized(), cfg.exploration, null, SeedMode.LOVED,
                requiresSeeds = false, daypart = null, energyTarget = null, excludeSeeds = false, eligible = { true }
            )

            SectionType.BECAUSE_YOU_LIKE -> SectionPlan(
                type,
                base.copy(taste = 0.25, artist = 0.12, genre = 0.10, similarity = 0.28, mood = 0.08, energy = 0.05, bpm = 0.04,
                    completion = 0.08, favorite = 0.04, recency = 0.03, replay = 0.03, discovery = 0.0, session = 0.04, timeOfDay = 0.02).normalized(),
                // Pure exploitation: only things close to what is already loved.
                ExplorationConfig(1.0, 0.0, 0.0, 0.0, 0.0),
                setOf(
                    CandidateSourceId.SIMILAR_TRACK, CandidateSourceId.SAME_GENRE, CandidateSourceId.SIMILAR_ARTIST,
                    CandidateSourceId.HIGH_COMPLETION, CandidateSourceId.RECENT_PREFERENCE
                ),
                SeedMode.LOVED, requiresSeeds = true, daypart = null, energyTarget = null, excludeSeeds = true,
                eligible = { (it.signal?.negativeScore ?: 0.0) < 0.5 }
            )

            SectionType.TONIGHT -> SectionPlan(
                type,
                base.copy(timeOfDay = 0.22, energy = 0.12, mood = 0.12, taste = 0.18, artist = 0.08, genre = 0.06, bpm = 0.04,
                    session = 0.05, similarity = 0.03, discovery = 0.02).normalized(),
                ExplorationConfig(0.85, 0.10, 0.05, 0.02, 0.15),
                null, SeedMode.LOVED, requiresSeeds = false, daypart = TimeBuckets.NIGHT, energyTarget = null, excludeSeeds = false,
                eligible = { v -> v.energy == null || v.energy <= NIGHT_MAX_ENERGY }
            )

            SectionType.DRIVING -> SectionPlan(
                type,
                base.copy(energy = 0.15, bpm = 0.15, completion = 0.14, favorite = 0.08, taste = 0.18, artist = 0.08, genre = 0.05,
                    mood = 0.05, timeOfDay = 0.02, discovery = 0.0, session = 0.06, similarity = 0.02).normalized(),
                // Known, safe tracks: nothing that may force the driver to skip.
                ExplorationConfig(0.92, 0.08, 0.0, 0.0, 0.05),
                null, SeedMode.LOVED, requiresSeeds = false, daypart = null, energyTarget = DRIVING_ENERGY, excludeSeeds = false,
                eligible = { v ->
                    (v.energy == null || v.energy >= DRIVING_MIN_ENERGY) &&
                        (v.bpm == null || v.bpm >= DRIVING_MIN_BPM) &&
                        (v.signal?.negativeScore ?: 0.0) < 0.5
                }
            )

            SectionType.DISCOVER -> SectionPlan(
                type,
                base.copy(discovery = 0.22, similarity = 0.18, taste = 0.20, genre = 0.08, mood = 0.08, energy = 0.06, bpm = 0.04,
                    artist = 0.04, completion = 0.0, favorite = 0.0, replay = 0.0, recency = 0.02, session = 0.05, timeOfDay = 0.03).normalized(),
                ExplorationConfig(0.0, 0.60, 0.40, 0.10, 0.60),
                setOf(
                    CandidateSourceId.DISCOVERY_SIMILAR, CandidateSourceId.DISCOVERY_RANDOM, CandidateSourceId.SIMILAR_ARTIST,
                    CandidateSourceId.SIMILAR_TRACK, CandidateSourceId.SAME_GENRE, CandidateSourceId.FALLBACK
                ),
                SeedMode.LOVED, requiresSeeds = false, daypart = null, energyTarget = null, excludeSeeds = false,
                eligible = { it.isUnplayed && !it.favorite }
            )

            SectionType.SIMILAR_TO_RECENT -> SectionPlan(
                type,
                base.copy(similarity = 0.34, mood = 0.14, energy = 0.10, bpm = 0.08, taste = 0.12, recency = 0.05, artist = 0.05,
                    genre = 0.04, session = 0.06, completion = 0.02, favorite = 0.0, replay = 0.0, discovery = 0.02, timeOfDay = 0.02).normalized(),
                ExplorationConfig(0.5, 0.5, 0.0, 0.0, 0.05),
                setOf(CandidateSourceId.SIMILAR_TRACK, CandidateSourceId.SIMILAR_ARTIST, CandidateSourceId.SAME_GENRE),
                SeedMode.RECENT, requiresSeeds = true, daypart = null, energyTarget = null, excludeSeeds = true, eligible = { true }
            )
        }
    }

    const val NIGHT_MAX_ENERGY = 0.65
    const val DRIVING_ENERGY = 0.70
    const val DRIVING_MIN_ENERGY = 0.45
    const val DRIVING_MIN_BPM = 85
}
