package com.ghadirb.aimusic

import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import java.util.TimeZone

object TestData {
    const val NOW = 1_800_000_000_000L
    const val DAY = 24L * 60 * 60 * 1000
    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun track(
        id: Long, title: String = "T$id", artist: String = "A$id", album: String = "Al$id", genre: String? = null,
        fav: Boolean = false, energy: Float? = null, mood: String? = null, bpm: Int? = null,
        dur: Long = 200_000, added: Long = NOW - 400 * DAY
    ) = TrackEntity(
        id = id, path = "content://t/$id", title = title, artist = artist, album = album, genre = genre,
        durationMs = dur, dateAdded = added, isFavorite = fav, energyLevel = energy, bpm = bpm, moodTag = mood,
        analyzed = energy != null
    )

    /** One listening session [daysAgo] days before NOW. */
    fun play(trackId: Long, daysAgo: Double, completed: Float = 1f, skipped: Boolean = false, replays: Int = 0) =
        ListeningHistoryEntity(
            trackId = trackId, startTime = NOW - (daysAgo * DAY).toLong(), listenDurationMs = 100_000,
            completedPercentage = completed, skipped = skipped, replayCount = replays
        )
}
