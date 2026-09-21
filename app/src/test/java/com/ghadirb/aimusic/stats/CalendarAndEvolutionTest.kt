package com.ghadirb.aimusic.stats

import com.ghadirb.aimusic.TestData.DAY
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CalendarAndEvolutionTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) =
        Calendar.getInstance(UTC).apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis

    // ---- Jalali conversion (known dates) ----
    @Test fun convertsKnownDates() {
        assertArrayEquals(intArrayOf(1405, 1, 1), JalaliCalendar.toJalali(2026, 3, 21))   // Nowruz 1405
        assertArrayEquals(intArrayOf(1404, 1, 1), JalaliCalendar.toJalali(2025, 3, 21))   // Nowruz 1404
        assertArrayEquals(intArrayOf(1379, 1, 1), JalaliCalendar.toJalali(2000, 3, 20))   // Nowruz 1379
        assertArrayEquals(intArrayOf(1405, 7, 1), JalaliCalendar.toJalali(2026, 9, 23))   // 1 Mehr 1405
        assertArrayEquals(intArrayOf(1404, 12, 29), JalaliCalendar.toJalali(2026, 3, 20)) // last day of 1404 (non-leap)
        assertArrayEquals(intArrayOf(2026, 3, 21), JalaliCalendar.toGregorian(1405, 1, 1))
        assertEquals("مهر", JalaliCalendar.monthName(7))
    }

    @Test fun roundTripsEveryDayOfSeveralYears() {
        val c = Calendar.getInstance(UTC).apply { clear(); set(2019, 0, 1) }
        repeat(365 * 8) {
            val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
            val j = JalaliCalendar.toJalali(y, m, d)
            assertArrayEquals("$y-$m-$d", intArrayOf(y, m, d), JalaliCalendar.toGregorian(j[0], j[1], j[2]))
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    // ---- periods ----
    @Test fun persianWeekStartsOnSaturday() {
        val wednesday = utc(2026, 9, 23) // a Wednesday
        val week = InsightPeriods.current(InsightRange.WEEK, wednesday, UTC, persian = true)
        assertEquals(utc(2026, 9, 19, 0), week.startMs) // Saturday
        assertEquals(7 * DAY, week.endMs - week.startMs)
        val previous = InsightPeriods.previous(InsightRange.WEEK, wednesday, UTC, persian = true)!!
        assertEquals(week.startMs, previous.endMs)
        assertEquals(7 * DAY, previous.endMs - previous.startMs)
    }

    @Test fun gregorianWeekStartsOnMonday() {
        val week = InsightPeriods.current(InsightRange.WEEK, utc(2026, 9, 23), UTC, persian = false)
        assertEquals(utc(2026, 9, 21, 0), week.startMs)
    }

    @Test fun jalaliMonthAndYearBoundaries() {
        val now = utc(2026, 10, 5) // 13 Mehr 1405
        val month = InsightPeriods.current(InsightRange.MONTH, now, UTC, persian = true)
        assertEquals(utc(2026, 9, 23, 0), month.startMs)          // 1 Mehr
        assertEquals(utc(2026, 10, 23, 0), month.endMs)           // 1 Aban (30-day month)
        val prevMonth = InsightPeriods.previous(InsightRange.MONTH, now, UTC, persian = true)!!
        assertEquals(utc(2026, 8, 23, 0), prevMonth.startMs)      // 1 Shahrivar
        val year = InsightPeriods.current(InsightRange.YEAR, now, UTC, persian = true)
        assertEquals(utc(2026, 3, 21, 0), year.startMs)
        assertEquals(utc(2027, 3, 21, 0), year.endMs)
    }

    @Test fun todayAllAndGregorianMonthAndYear() {
        val now = utc(2026, 9, 23, 15)
        assertEquals(utc(2026, 9, 23, 0), InsightPeriods.current(InsightRange.TODAY, now, UTC, true).startMs)
        assertEquals(0L, InsightPeriods.current(InsightRange.ALL, now, UTC, true).startMs)
        assertNull(InsightPeriods.previous(InsightRange.ALL, now, UTC, true))
        assertEquals(utc(2026, 9, 1, 0), InsightPeriods.current(InsightRange.MONTH, now, UTC, false).startMs)
        assertEquals(utc(2026, 1, 1, 0), InsightPeriods.current(InsightRange.YEAR, now, UTC, false).startMs)
    }

    // ---- taste evolution ----
    private val cur = PeriodBounds(1000 * DAY, 1007 * DAY)
    private val prev = PeriodBounds(993 * DAY, 1000 * DAY)
    private fun play(trackId: Long, at: Long, skipped: Boolean = false) = ListeningHistoryEntity(
        trackId = trackId, startTime = at, listenDurationMs = 60_000, completedPercentage = if (skipped) 0.1f else 1f, skipped = skipped
    )

    @Test fun refusesToInventNumbersWithoutEnoughData() {
        val tracks = listOf(track(1, genre = "Rock"))
        val result = TasteEvolution.compute(tracks, (1..3).map { play(1, cur.startMs + it * 1000L) }, cur, prev)
        assertFalse(result.enoughData)
        assertFalse(result.comparable)
        assertTrue(result.genreChanges.isEmpty())
        assertNull(result.listenTimeChangePercent)
    }

    @Test fun comparesGenreSharesAndDiscovery() {
        val tracks = listOf(
            track(1, artist = "Old", genre = "Pop", energy = 0.4f, mood = "calm", bpm = 90),
            track(2, artist = "New", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 140),
            track(3, artist = "New", genre = "Rock", energy = 0.8f, mood = "energetic", bpm = 150)
        )
        // previous week: 10 plays of the Pop track. current week: 4 Pop, 8 Rock (tracks 2, 3 first heard now)
        val history = (0 until 10).map { play(1, prev.startMs + it * 1000L) } +
            (0 until 4).map { play(1, cur.startMs + it * 1000L) } +
            (0 until 5).map { play(2, cur.startMs + 10_000L + it) } + (0 until 3).map { play(3, cur.startMs + 20_000L + it) }
        val r = TasteEvolution.compute(tracks, history, cur, prev)
        assertTrue(r.enoughData && r.comparable)
        assertEquals("Rock", r.current.topGenre)
        assertEquals(1f, r.previous!!.genreShare["Pop"]!!, 0.001f)
        assertEquals(8f / 12f, r.current.genreShare["Rock"]!!, 0.001f)
        val rock = r.genreChanges.first { it.genre == "Rock" }
        assertEquals(0f, rock.previousShare, 0f)
        assertTrue(rock.delta > 0.6f)
        assertEquals(setOf("New"), r.current.newArtists)
        assertEquals(2, r.current.discoveredTracks)
        assertEquals("energetic", r.current.dominantMood)
        assertTrue(r.bpmChange!! > 30)
        assertTrue(r.energyChange!! > 0.2f)
    }

    @Test fun currentPeriodShownWithoutComparisonWhenPreviousIsTooSmall() {
        val tracks = listOf(track(1, genre = "Rock"))
        val history = (0 until 12).map { play(1, cur.startMs + it * 1000L) } + (0 until 2).map { play(1, prev.startMs + it * 1000L) }
        val r = TasteEvolution.compute(tracks, history, cur, prev)
        assertTrue(r.enoughData)
        assertFalse(r.comparable)
        assertNull(r.skipRateChange)
    }

    // ---- new insight fields ----
    @Test fun discoveryPeakAndSkippedInsights() {
        val tracks = listOf(track(1, album = "Album1", artist = "A"), track(2, album = "Album2", artist = "B"))
        val t0 = utc(2026, 9, 23, 8)
        val history = listOf(
            play(1, t0 - 30 * DAY), play(1, t0), play(1, t0 + 1000), play(2, t0 + 2000),
            play(2, t0 + 3000, skipped = true), play(2, t0 + 4000, skipped = true)
        )
        val s = StatsCalculator.compute(tracks, history, t0 + DAY, sinceMs = t0 - DAY, zone = UTC)
        assertEquals(2, s.uniqueTracks)
        assertEquals(1, s.discoveredTracks)          // track 2 was first heard in this period
        assertEquals(0.5f, s.discoveryRate, 0.001f)
        assertEquals(8, s.peakHour)
        assertEquals("Album1 — A", s.topAlbums.first().name)
        assertEquals(2L, s.topSkipped.first().track.id)
        assertEquals(2, s.topSkipped.first().plays)
    }
}
