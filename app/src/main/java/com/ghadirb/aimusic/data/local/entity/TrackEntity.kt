package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One scanned audio file on the device. `path` (MediaStore content URI) is
 * unique per track and is used to detect duplicates on re-scan.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String? = null,
    val durationMs: Long,
    val albumArtUri: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    // --- v3: on-device audio analysis (see analysis/AudioAnalyzer.kt) ---
    /** 0f (calm) .. 1f (energetic), derived from decoded-PCM RMS loudness. Null = not analyzed yet. */
    val energyLevel: Float? = null,
    /** Estimated tempo in beats-per-minute from the energy envelope autocorrelation. Null = not analyzed / inconclusive. */
    val bpm: Int? = null,
    /** One of MoodTag.* (see AudioAnalyzer) — heuristic label combining energy + tempo (+ lyrics if found). */
    val moodTag: String? = null,
    /** True once AudioAnalyzer has processed this file (success or inconclusive) — avoids re-analyzing every worker run. */
    val analyzed: Boolean = false
)
