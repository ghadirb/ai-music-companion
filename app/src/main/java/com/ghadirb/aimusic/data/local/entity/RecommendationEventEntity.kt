package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spec §18 — one recommendation that was generated for the user. After the track is played the
 * row is updated ([played]/[completed]/[skipped]) so we can tell whether a shown suggestion was
 * actually chosen and consumed. Entirely local; used by RecommendationEvaluation.
 */
@Entity(
    tableName = "recommendation_event",
    indices = [Index("trackId"), Index("timestamp")]
)
data class RecommendationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    /** SectionType.id (for_you, tonight …). */
    val section: String,
    /** Main CandidateSourceId that proposed the track (name), or "unknown". */
    val source: String,
    val position: Int,
    val score: Double,
    val timestamp: Long,
    val algorithmVersion: String,
    /** The track had never been played when it was suggested (a "discovery"). */
    val discovery: Boolean,
    val shown: Boolean = true,
    val played: Boolean = false,
    val completed: Boolean = false,
    val skipped: Boolean = false
)
