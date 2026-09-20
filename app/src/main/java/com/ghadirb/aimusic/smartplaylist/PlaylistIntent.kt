package com.ghadirb.aimusic.smartplaylist

import com.ghadirb.aimusic.library.EnergyBand

enum class SmartSort { BEST_MATCH, LEAST_PLAYED, MOST_PLAYED, RECENTLY_ADDED, RANDOM }

enum class LanguageFilter { PERSIAN, NON_PERSIAN }

/**
 * A structured description of the playlist the user wants. The natural-language layer (local rule-based
 * parser today, cloud LLM for AI DJ) only ever produces THIS structure; the app itself then selects
 * tracks from the local library. The AI never sees or chooses the user's files.
 */
data class PlaylistIntent(
    val moods: Set<String> = emptySet(),
    val energy: EnergyBand? = null,
    val genre: String? = null,
    val artist: String? = null,
    val language: LanguageFilter? = null,
    val durationMinutes: Int? = null,
    val excludeRecentDays: Int? = null,
    val favoriteOnly: Boolean = false,
    val similarToTrackId: Long? = null,
    val sort: SmartSort = SmartSort.BEST_MATCH,
    val limit: Int = DEFAULT_LIMIT,
    val title: String? = null
) {
    /** Clamps/whitelists every field so untrusted input (e.g. LLM output) can never produce a harmful query. */
    fun sanitized(): PlaylistIntent = copy(
        moods = moods.map { it.lowercase() }.filter { it in ALLOWED_MOODS }.toSet(),
        genre = genre?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() },
        artist = artist?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() },
        durationMinutes = durationMinutes?.coerceIn(5, 600),
        excludeRecentDays = excludeRecentDays?.coerceIn(1, 365),
        limit = limit.coerceIn(1, MAX_LIMIT),
        title = title?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
    )

    /** True when nothing constrains the result (the caller should ask the user to be more specific). */
    val isUnconstrained: Boolean
        get() = moods.isEmpty() && energy == null && genre == null && artist == null && language == null &&
            durationMinutes == null && excludeRecentDays == null && !favoriteOnly && similarToTrackId == null

    companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        private const val MAX_TEXT = 60
        val ALLOWED_MOODS = setOf("calm", "energetic", "neutral", "sad", "happy")
    }
}
