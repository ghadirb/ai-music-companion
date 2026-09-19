package com.ghadirb.aimusic.library

import com.ghadirb.aimusic.data.local.entity.TrackEntity
import java.text.Collator
import java.util.Locale

enum class LibrarySort {
    RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED, TITLE, ARTIST, ALBUM, DURATION, FAVORITE
}

enum class EnergyBand {
    LOW, MEDIUM, HIGH;

    fun contains(energy: Float?): Boolean = when {
        energy == null -> false
        this == LOW -> energy < LOW_MAX
        this == MEDIUM -> energy >= LOW_MAX && energy < HIGH_MIN
        else -> energy >= HIGH_MIN
    }

    companion object {
        const val LOW_MAX = 0.4f
        const val HIGH_MIN = 0.65f
    }
}

data class LibraryFilter(
    val favoritesOnly: Boolean = false,
    val genre: String? = null,
    val mood: String? = null,
    val energy: EnergyBand? = null
) {
    val isActive: Boolean get() = favoritesOnly || genre != null || mood != null || energy != null

    fun accepts(track: TrackEntity): Boolean =
        (!favoritesOnly || track.isFavorite) &&
            (genre == null || track.genre.equals(genre, ignoreCase = true)) &&
            (mood == null || track.moodTag == mood) &&
            (energy == null || energy.contains(track.energyLevel))
}

/** Per-track listening aggregate used for "Recently played" / "Most played" sorting. */
data class TrackStat(val trackId: Long, val playCount: Int, val lastPlayedAt: Long)

object LibraryQuery {

    fun apply(
        tracks: List<TrackEntity>,
        stats: Map<Long, TrackStat>,
        sort: LibrarySort,
        filter: LibraryFilter
    ): List<TrackEntity> {
        val filtered = if (filter.isActive) tracks.filter(filter::accepts) else tracks
        val collator = Collator.getInstance(Locale("fa"))
        val byTitle = Comparator<TrackEntity> { a, b -> collator.compare(a.title, b.title) }
        return when (sort) {
            LibrarySort.TITLE -> filtered.sortedWith(byTitle)
            LibrarySort.ARTIST -> filtered.sortedWith(
                Comparator<TrackEntity> { a, b -> collator.compare(a.artist, b.artist) }
                    .thenComparing { a, b -> collator.compare(a.album, b.album) }
                    .thenComparing(byTitle)
            )
            LibrarySort.ALBUM -> filtered.sortedWith(
                Comparator<TrackEntity> { a, b -> collator.compare(a.album, b.album) }.thenComparing(byTitle)
            )
            LibrarySort.DURATION -> filtered.sortedWith(compareBy<TrackEntity> { it.durationMs }.thenComparing(byTitle))
            LibrarySort.RECENTLY_ADDED -> filtered.sortedWith(
                compareByDescending<TrackEntity> { it.dateAdded }.thenComparing(byTitle)
            )
            LibrarySort.RECENTLY_PLAYED -> filtered.sortedWith(
                compareByDescending<TrackEntity> { stats[it.id]?.lastPlayedAt ?: 0L }.thenComparing(byTitle)
            )
            LibrarySort.MOST_PLAYED -> filtered.sortedWith(
                compareByDescending<TrackEntity> { stats[it.id]?.playCount ?: 0 }.thenComparing(byTitle)
            )
            LibrarySort.FAVORITE -> filtered.sortedWith(
                compareByDescending<TrackEntity> { it.isFavorite }.thenComparing(byTitle)
            )
        }
    }

    /** Distinct, sorted non-blank values available for a filter menu. */
    fun availableGenres(tracks: List<TrackEntity>): List<String> =
        tracks.mapNotNull { it.genre?.takeIf(String::isNotBlank) }.distinct().sorted()

    fun availableMoods(tracks: List<TrackEntity>): List<String> =
        tracks.mapNotNull { it.moodTag }.distinct().sorted()
}
