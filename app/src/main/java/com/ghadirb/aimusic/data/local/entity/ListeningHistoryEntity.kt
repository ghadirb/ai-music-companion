package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One listening session for a track. This is the raw behavioral signal the
 * RecommendationEngine and the future taste-profile builder consume.
 * Everything here stays on-device (see README "Privacy").
 */
@Entity(tableName = "listening_history")
data class ListeningHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val startTime: Long,
    val listenDurationMs: Long,
    val completedPercentage: Float,
    val skipped: Boolean,
    val replayCount: Int = 0
)
