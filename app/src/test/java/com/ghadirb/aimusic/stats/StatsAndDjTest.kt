package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.TestData.DAY
import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.library.EnergyBand
import com.ghadirb.aimusic.smartplaylist.DjIntentMapper
import com.ghadirb.aimusic.smartplaylist.LanguageFilter
import com.ghadirb.aimusic.smartplaylist.SmartSort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAndDjTest {

    private val tracks = listOf(
        track(1, artist = "A", genre = "Pop", mood = "calm"),
        track(2, artist = "B", genre = "Rock", mood = "energetic"),
        track(3, artist = "A", genre = "Pop")
    )
    private val history = listOf(
        play(1, 0.1), play(1, 1.0), play(1, 2.0), play(2, 1.5), play(3, 3.0, completed = 0.1f, skipped = true), play(2, 40.0)
    )

    @Test fun basicTotalsTopAndSkipRate() {
        val s = StatsCalculator.compute(tracks, history, NOW, zone = UTC)
        assertEquals(6, s.sessions)
        assertEquals(5, s.completedPlays)
        assertEquals(2, s.uniqueTracks)
        assertEquals(1f / 6f, s.skipRate, 0.001f)
        assertEquals(1L, s.topTracks.first().track.id)
        assertEquals(3, s.topTracks.first().plays)
        assertEquals("A", s.topArtists.first().name)
    }

    @Test fun rangeFilterOnlyCountsRecentSessions() {
        val s = StatsCalculator.compute(tracks, history, NOW, sinceMs = NOW - 30 * DAY, zone = UTC)
        assertEquals(5, s.sessions) // the 40-day-old play is excluded
    }

    @Test fun advancedBreakdowns() {
        val s = StatsCalculator.compute(tracks, history, NOW, zone = UTC)
        assertEquals(24, s.playsByHour.size)
        assertEquals(7, s.playsByWeekday.size)
        assertEquals(s.completedPlays, s.playsByHour.sum())
        assertEquals(s.completedPlays, s.playsByWeekday.sum())
        assertEquals("Pop", s.topGenres.first().name)
        assertEquals(mapOf("calm" to 3, "energetic" to 2), s.moodCounts)
        assertEquals(14, s.dailyMinutes.size)
        assertTrue(s.dailyMinutes.sum() > 0)
    }

    @Test fun emptyHistoryIsSafe() {
        val s = StatsCalculator.compute(tracks, emptyList(), NOW, zone = UTC)
        assertTrue(s.isEmpty)
        assertEquals(0f, s.skipRate, 0f)
    }

    @Test fun ignoresHistoryForDeletedTracks() {
        val s = StatsCalculator.compute(listOf(tracks[0]), history, NOW, zone = UTC)
        assertEquals(3, s.sessions)
    }

    // ---- AI DJ intent mapping (server JSON -> local intent) ----
    @Test fun mapsServerIntentAndSanitises() {
        val json = JSONObject("""{"moods":["calm","hack"],"energy":"low","language":"persian","duration_minutes":60,
            "exclude_recent_days":14,"favorite_only":true,"similar_to_current":true,"sort":"least_played","limit":500,"title":"مطالعه","genre":null}""")
        val i = DjIntentMapper.fromJson(json, currentTrackId = 9L)
        assertEquals(setOf("calm"), i.moods)
        assertEquals(EnergyBand.LOW, i.energy)
        assertEquals(LanguageFilter.PERSIAN, i.language)
        assertEquals(60, i.durationMinutes)
        assertEquals(14, i.excludeRecentDays)
        assertTrue(i.favoriteOnly)
        assertEquals(9L, i.similarToTrackId)
        assertEquals(SmartSort.LEAST_PLAYED, i.sort)
        assertEquals(100, i.limit)
        assertNull(i.genre)
    }

    @Test fun languageFilterOnlyWhenTheUserExplicitlyAsksForIt() {
        val fromModel = DjIntentMapper.fromJson(JSONObject("""{"moods":["calm"],"language":"persian"}"""), null)
        assertNull(DjIntentMapper.withExplicitLanguageOnly(fromModel, "یک ساعت موسیقی آرام برای مطالعه").language)
        assertEquals(LanguageFilter.PERSIAN, DjIntentMapper.withExplicitLanguageOnly(fromModel, "آهنگ آرام ایرانی").language)
        assertEquals(LanguageFilter.NON_PERSIAN, DjIntentMapper.withExplicitLanguageOnly(fromModel, "calm foreign songs").language)
    }

    @Test fun junkIntentBecomesUnconstrainedDefaults() {
        val i = DjIntentMapper.fromJson(JSONObject("""{"energy":"extreme","sort":"???","limit":"x"}"""), null)
        assertTrue(i.isUnconstrained)
        assertEquals(SmartSort.BEST_MATCH, i.sort)
    }
}
