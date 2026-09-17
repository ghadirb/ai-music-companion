package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-created manual playlist (doc's "ساخت Playlist دستی" MVP item). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
