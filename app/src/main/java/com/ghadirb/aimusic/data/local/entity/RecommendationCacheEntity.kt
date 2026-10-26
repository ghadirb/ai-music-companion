package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spec §19/§20 — cached result of one Home section. [trackIds] is a comma-separated id list,
 * [itemsJson] carries per-item score, main source and reasons (see RecommendationCacheCodec).
 * A row whose [algorithmVersion] differs from the running algorithm is ignored and deleted.
 */
@Entity(tableName = "recommendation_cache")
data class RecommendationCacheEntity(
    @PrimaryKey
    val section: String,
    val trackIds: String,
    val itemsJson: String,
    val generatedAt: Long,
    val algorithmVersion: String
)
