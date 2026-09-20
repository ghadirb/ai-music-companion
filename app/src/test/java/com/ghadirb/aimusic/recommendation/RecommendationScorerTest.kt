package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationScorerTest {

    private fun rank(tracks: List<com.ghadirb.aimusic.data.local.entity.TrackEntity>, history: List<com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity>) =
        RecommendationScorer.score(tracks, history, NOW, zone = UTC)

    @Test fun favoriteOutranksUnplainedTrack() {
        val result = rank(listOf(track(1), track(2, fav = true)), emptyList())
        assertEquals(2L, result.first().track.id)
        assertEquals(ReasonType.FAVORITE, result.first().reasons.first().type)
    }

    @Test fun completedPlaysRaiseScoreAndSkipsLowerIt() {
        val tracks = listOf(track(1), track(2), track(3))
        val history = listOf(play(1, 1.0), play(1, 2.0), play(2, 1.0, completed = 0.1f, skipped = true), play(2, 2.0, completed = 0.1f, skipped = true))
        val byId = rank(tracks, history).associateBy { it.track.id }
        assertTrue(byId.getValue(1).score > byId.getValue(3).score)
        assertTrue(byId.getValue(2).score < byId.getValue(3).score)
    }

    @Test fun recentPlaysWeighMoreThanOldOnes() {
        val tracks = listOf(track(1), track(2))
        val history = listOf(play(1, 1.0), play(1, 2.0), play(1, 3.0), play(2, 200.0), play(2, 201.0), play(2, 202.0))
        val byId = rank(tracks, history).associateBy { it.track.id }
        assertTrue(byId.getValue(1).score > byId.getValue(2).score)
    }

    @Test fun replaysAddScore() {
        val tracks = listOf(track(1), track(2))
        val history = listOf(play(1, 1.0, replays = 2), play(2, 1.0))
        val byId = rank(tracks, history).associateBy { it.track.id }
        assertTrue(byId.getValue(1).score > byId.getValue(2).score)
    }

    @Test fun likedArtistBoostsUnplayedTracksAndExplainsWhy() {
        val tracks = listOf(
            track(1, artist = "Liked"), track(2, artist = "Liked"), track(3, artist = "Other"), track(4, artist = "Third")
        )
        val history = (1..6).map { play(1, it.toDouble()) }
        val byId = rank(tracks, history).associateBy { it.track.id }
        assertTrue(byId.getValue(2).score > byId.getValue(3).score)
        val reason = byId.getValue(2).reasons.firstOrNull { it.type == ReasonType.FAVORITE_ARTIST }
        assertEquals("Liked", reason?.detail)
        assertTrue(ReasonText.format(reason!!).contains("Liked"))
    }

    @Test fun genreAffinityIsExplained() {
        val tracks = listOf(track(1, genre = "Jazz"), track(2, genre = "Jazz"), track(3, genre = "Metal"))
        val history = (1..5).map { play(1, it.toDouble()) }
        val r = rank(tracks, history).first { it.track.id == 2L }
        assertTrue(r.reasons.any { it.type == ReasonType.GENRE_MATCH })
    }

    @Test fun oldLikedTrackGetsRediscoveryReason() {
        val tracks = listOf(track(1, fav = true), track(2, fav = true))
        val history = listOf(play(1, 100.0), play(2, 1.0), play(2, 2.0))
        val r = rank(tracks, history).first { it.track.id == 1L }
        val reason = r.reasons.firstOrNull { it.type == ReasonType.NOT_PLAYED_LONG }
        assertEquals(100, reason?.number)
    }

    @Test fun timeOfDayEnergyMatchWins() {
        // NOW is the hour bucket both plays fall in; the track matching the played energy ranks higher.
        val tracks = listOf(track(1, energy = 0.8f), track(2, energy = 0.8f), track(3, energy = 0.1f))
        val history = (1..4).map { play(1, it.toDouble()) }
        val byId = rank(tracks, history).associateBy { it.track.id }
        assertTrue(byId.getValue(2).score > byId.getValue(3).score)
    }

    @Test fun isDeterministic() {
        val tracks = (1L..20L).map { track(it, artist = "A${it % 3}", fav = it % 4 == 0L) }
        val history = (1L..20L).map { play(it, it.toDouble()) }
        assertEquals(rank(tracks, history).map { it.track.id }, rank(tracks, history).map { it.track.id })
    }

    @Test fun reasonTextIsPersian() {
        assertTrue(ReasonText.format(Reason(ReasonType.FAVORITE_ARTIST, detail = "X")).contains("را زیاد گوش می‌دهی"))
        assertTrue(ReasonText.format(Reason(ReasonType.SIMILAR_TO_RECENT)).contains("مشابه"))
        assertTrue(ReasonText.format(Reason(ReasonType.NOT_PLAYED_LONG, number = 45)).contains("45"))
        assertTrue(ReasonText.format(Reason(ReasonType.FAVORITE)).contains("علاقه‌مندی"))
    }
}
