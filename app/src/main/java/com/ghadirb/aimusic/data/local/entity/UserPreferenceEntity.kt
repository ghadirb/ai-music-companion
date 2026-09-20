package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated, on-device taste profile (single row, id = 0), rebuilt by TasteProfileBuilder.
 * List-like fields are stored as delimited strings (see [com.ghadirb.aimusic.recommendation.ListCodec])
 * to avoid a TypeConverter. Nothing in this table is ever sent off-device.
 */
@Entity(tableName = "user_preference")
data class UserPreferenceEntity(
    @PrimaryKey
    val id: Int = 0,
    val favoriteArtists: String = "",
    val favoriteGenres: String = "",
    val favoriteEnergyLevel: String = "unknown",
    val preferredDurationMs: Long = 0,
    val preferredTimeOfDay: String = "unknown",
    // --- v5 ---
    /** Most listened mood tags (calm/energetic/happy/sad/neutral), best first. */
    val favoriteMoods: String = "",
    /** Weighted average tempo of liked tracks; 0 = unknown. */
    val preferredBpm: Int = 0,
    /** "lo-hi" energy range (20th–80th percentile) of liked tracks; empty = unknown. */
    val energyRange: String = "",
    /** Ids of the user's most-loved tracks, best first. */
    val topTrackIds: String = "",
    /** Fraction (0..1) of listening sessions that were skipped. */
    val skipRate: Float = 0f,
    /** Fraction (0..1) of the library that is marked favourite. */
    val favoriteRatio: Float = 0f,
    /** Up to three hours of day (0..23) when the user listens most. */
    val peakHours: String = "",
    val updatedAt: Long = 0L
)
