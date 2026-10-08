package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spec §3 — an explicit user action that is not derivable from a listening session
 * (FAVORITED, UNFAVORITED, ADDED_TO_PLAYLIST, REMOVED_FROM_PLAYLIST). One small row per action,
 * never per second. [behavior] holds a `ListeningBehavior.name`. Local only.
 */
@Entity(
    tableName = "behavior_event",
    indices = [Index("trackId"), Index("timestamp")]
)
data class BehaviorEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val behavior: String,
    val timestamp: Long
)
