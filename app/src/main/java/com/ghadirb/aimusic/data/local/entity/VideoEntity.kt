package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A video file found on the device through MediaStore.Video.Media (the "ویدئو" tab).
 *
 * Deliberately independent of [TrackEntity]: music and video have separate tables, scanner,
 * repository and player, so nothing here can change how the Music Library behaves.
 *
 * [id] is the MediaStore `_ID` of the file, which is stable for the lifetime of the file and is
 * what makes a re-scan able to keep the user's resume position / last-played time. [contentUri]
 * is the matching content:// uri stored as a String (Room has no Uri type).
 */
@Entity(
    tableName = "videos",
    indices = [Index("lastPlayedAt"), Index("dateAdded")]
)
data class VideoEntity(
    @PrimaryKey
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    /** MediaStore bucket (folder) display name, empty if unknown. */
    val folderName: String,
    /** e.g. "Movies/Trips/" on Android 10+, empty on older versions. */
    val relativePath: String,
    /** Seconds since epoch (MediaStore DATE_ADDED). */
    val dateAdded: Long,
    /** Seconds since epoch (MediaStore DATE_MODIFIED). */
    val dateModified: Long,
    /** Resume position in milliseconds; 0 = start from the beginning. */
    val lastPositionMs: Long = 0L,
    /** Epoch millis of the last time the video was played; 0 = never played. */
    val lastPlayedAt: Long = 0L,
    val isFavorite: Boolean = false
)
