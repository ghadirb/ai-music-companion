package com.ghadirb.aimusic.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class StatsInsightsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val base = 1_700_000_000_000L - (1_700_000_000_000L % day) // a UTC midnight
    private fun at(dayOffset: Int, hour: Int = 12) = base + dayOffset * day + hour * 3_600_000L

    @Test fun percentChangeIsRoundedAndNullWithoutPrevious() {
        assertEquals(20, Delta(120, 100).percent)
        assertEquals(-50, Delta(50, 100).percent)
        assertEquals(0, Delta(100, 100).percent)
        assertNull(Delta(10, 0).percent)
    }

    @Test fun deltaTextShowsDirectionAndLabel() {
        assertEquals("▲ 20٪ نسبت به ماه قبل", StatsInsights.deltaText(Delta(120, 100), "ماه قبل"))
        assertEquals("▼ 50٪ نسبت به هفتهٔ قبل", StatsInsights.deltaText(Delta(50, 100), "هفتهٔ قبل"))
        assertEquals("بدون تغییر نسبت به دیروز", StatsInsights.deltaText(Delta(5, 5), "دیروز"))
        assertNull(StatsInsights.deltaText(Delta(5, 0), "ماه قبل"))
        assertNull(StatsInsights.deltaText(Delta(5, 5), null))
    }

    @Test fun streakCountsConsecutiveDaysEndingToday() {
        val s = StatsInsights.streak(listOf(at(-3), at(-2), at(-1), at(0, 8)), at(0, 20), utc)
        assertEquals(4, s.current); assertEquals(4, s.best); assertEquals(4, s.activeDays)
    }

    @Test fun streakSurvivesUntilEndOfTheNextDay() {
        // listened yesterday and the day before, nothing yet today
        val s = StatsInsights.streak(listOf(at(-2), at(-1)), at(0, 7), utc)
        assertEquals(2, s.current)
    }

    @Test fun streakBreaksAfterAMissedDayButKeepsBest() {
        val s = StatsInsights.streak(listOf(at(-10), at(-9), at(-8), at(-7), at(-1)), at(0), utc)
        assertEquals(1, s.current); assertEquals(4, s.best); assertEquals(5, s.activeDays)
        val old = StatsInsights.streak(listOf(at(-10), at(-9)), at(0), utc)
        assertEquals(0, old.current); assertEquals(2, old.best)
    }

    @Test fun streakOfEmptyHistoryIsZeroAndManyPlaysPerDayCountOnce() {
        assertEquals(Streak(0, 0, 0), StatsInsights.streak(emptyList(), at(0), utc))
        val s = StatsInsights.streak(listOf(at(0, 1), at(0, 5), at(0, 23)), at(0, 23), utc)
        assertEquals(1, s.current); assertEquals(1, s.activeDays)
    }

    @Test fun streakUsesTheLocalDayNotUtc() {
        val tehran = TimeZone.getTimeZone("Asia/Tehran") // UTC+3:30 (no DST since 2022)
        // 22:00 UTC and 00:30 UTC next day are the same local evening/night? 22:00Z = 01:30 local next day.
        val a = base + 20 * 3_600_000L           // 23:30 local, day 0
        val b = base + 21 * 3_600_000L           // 00:30 local, day 1
        assertEquals(2, StatsInsights.streak(listOf(a, b), b, tehran).current)
    }

    @Test fun weekdayInitialsAreOldestFirst() {
        // 2023-11-14 was a Tuesday (UTC)
        val tuesday = 1_699_963_200_000L
        assertEquals(listOf("ی", "د", "س"), StatsInsights.lastDaysInitials(tuesday, utc, 3))
    }

    @Test fun durationFormatting() {
        assertEquals("45 دقیقه", StatsInsights.duration(45 * 60_000L))
        assertEquals("2 ساعت و 5 دقیقه", StatsInsights.duration(125 * 60_000L))
    }

    @Test fun recapTextMentionsTheKeyFacts() {
        val s = StatsSummary(
            sessions = 10, completedPlays = 8, listenMs = 90 * 60_000L, uniqueTracks = 5, skipRate = 0.1f,
            topTracks = emptyList(), topArtists = listOf(RankedName("Googoosh", 4)),
            topGenres = emptyList(), playsByHour = List(24) { 0 }, playsByWeekday = List(7) { 0 },
            moodCounts = emptyMap(), dailyMinutes = List(14) { 0 }, peakHour = 21
        )
        val text = StatsInsights.recapText("مهر 1405", s, Streak(3, 6, 20))
        assertTrue(text.contains("مهر 1405"))
        assertTrue(text.contains("1 ساعت و 30 دقیقه"))
        assertTrue(text.contains("Googoosh"))
        assertTrue(text.contains("21 تا 22"))
        assertTrue(text.contains("6"))
    }
}
