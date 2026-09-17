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
    val isFavorite: Boolean = false
)
