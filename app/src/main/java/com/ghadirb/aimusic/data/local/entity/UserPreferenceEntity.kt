package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated taste profile. In the MVP this is a single row (id = 0) updated
 * incrementally by RecommendationEngine.refreshTasteProfile(). Lists are stored
 * as comma-separated strings to avoid a TypeConverter for the MVP.
 */
@Entity(tableName = "user_preference")
data class UserPreferenceEntity(
    @PrimaryKey
    val id: Int = 0,
    val favoriteArtists: String = "",
    val favoriteGenres: String = "",
    val favoriteEnergyLevel: String = "unknown",
    val preferredDurationMs: Long = 0,
    val preferredTimeOfDay: String = "unknown"
)
