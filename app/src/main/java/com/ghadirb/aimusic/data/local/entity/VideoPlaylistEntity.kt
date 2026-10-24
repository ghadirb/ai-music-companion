package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-made video playlist. Completely separate from the music [PlaylistEntity]: deleting a video
 * playlist can never touch music playlists, and it never deletes video files from the phone.
 */
@Entity(tableName = "video_playlists")
data class VideoPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One video inside a playlist. [videoId] is the MediaStore id also used by [VideoEntity]. Entries are
 * deliberately NOT removed when a video disappears from the library: they are just hidden (join) and
 * come back if the file returns. [position] keeps the user's manual order.
 */
@Entity(
    tableName = "video_playlist_entries",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("videoId")]
)
data class VideoPlaylistEntryEntity(
    val playlistId: Long,
    val videoId: Long,
    val position: Int,
    val addedAt: Long
)
