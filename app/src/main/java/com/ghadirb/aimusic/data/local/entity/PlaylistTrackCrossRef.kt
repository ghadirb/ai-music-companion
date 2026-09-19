package com.ghadirb.aimusic.data.local.entity

import androidx.room.Entity

/**
 * Many-to-many join between playlists and tracks, with an explicit
 * `position` so track order within a playlist is preserved and editable.
 * Composite primary key (playlistId, trackId) means a track can't be added
 * to the same playlist twice.
 */
@Entity(tableName = "playlist_track_cross_ref", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    val position: Int
)
