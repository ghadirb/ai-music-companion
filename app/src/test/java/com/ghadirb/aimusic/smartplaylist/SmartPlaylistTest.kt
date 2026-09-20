package com.ghadirb.aimusic.smartplaylist

import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.library.EnergyBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistTest {

    // ---- parser (the doc's example requests) ----
    @Test fun studyRequestIsCalmAndLowEnergy() {
        val i = PlaylistIntentParser.parse("برای مطالعه یک پلی‌لیست آرام بساز")
        assertTrue("calm" in i.moods)
        assertEquals(EnergyBand.LOW, i.energy)
    }

    @Test fun happySongsRequest() {
        val i = PlaylistIntentParser.parse("آهنگ‌های شاد پخش کن")
        assertTrue("happy" in i.moods)
        assertEquals(EnergyBand.HIGH, i.energy)
    }

    @Test fun nightDrivingIsMediumEnergy() {
        val i = PlaylistIntentParser.parse("یک پلی‌لیست برای رانندگی شبانه بساز")
        assertEquals(EnergyBand.MEDIUM, i.energy)
    }

    @Test fun lessPlayedRequestExcludesRecentAndSortsByLeastPlayed() {
        val i = PlaylistIntentParser.parse("از آهنگ‌هایی که کمتر گوش داده‌ام یک پلی‌لیست بساز")
        assertEquals(14, i.excludeRecentDays)
        assertEquals(SmartSort.LEAST_PLAYED, i.sort)
    }

    @Test fun oneHourOfStudyMusic() {
        assertEquals(60, PlaylistIntentParser.parse("یک ساعت موسیقی آرام برای مطالعه می‌خواهم").durationMinutes)
        assertEquals(30, PlaylistIntentParser.parse("نیم ساعت موسیقی آرام").durationMinutes)
        assertEquals(45, PlaylistIntentParser.parse("۴۵ دقیقه آهنگ شاد").durationMinutes)
    }

    @Test fun happyPersianNotRecentlyPlayed() {
        val i = PlaylistIntentParser.parse("چند آهنگ شاد ایرانی که اخیراً زیاد گوش نداده‌ام")
        assertEquals(LanguageFilter.PERSIAN, i.language)
        assertTrue("happy" in i.moods)
        assertEquals(14, i.excludeRecentDays)
    }

    @Test fun similarToCurrentTrack() {
        assertEquals(7L, PlaylistIntentParser.parse("چند آهنگ شبیه این آهنگ پیدا کن", ParseContext(currentTrackId = 7)).similarToTrackId)
        assertNull(PlaylistIntentParser.parse("چند آهنگ شبیه این آهنگ پیدا کن").similarToTrackId)
    }

    @Test fun englishAndKnownArtistsAndCount() {
        val i = PlaylistIntentParser.parse("10 calm songs by Miles Davis", ParseContext(knownArtists = listOf("Miles Davis", "Bob")))
        assertEquals("Miles Davis", i.artist)
        assertEquals(10, i.limit)
        assertTrue("calm" in i.moods)
    }

    @Test fun unknownTextIsUnconstrained() {
        assertTrue(PlaylistIntentParser.parse("سلام").isUnconstrained)
        assertTrue(PlaylistIntentParser.parse("").isUnconstrained)
    }

    @Test fun sanitizeClampsUntrustedValues() {
        val s = PlaylistIntent(moods = setOf("CALM", "evil"), durationMinutes = 99999, limit = 5000, excludeRecentDays = 0).sanitized()
        assertEquals(setOf("calm"), s.moods)
        assertEquals(600, s.durationMinutes)
        assertEquals(100, s.limit)
        assertEquals(1, s.excludeRecentDays)
    }

    // ---- generator ----
    private val tracks = listOf(
        track(1, title = "آرام", artist = "علی", energy = 0.2f, mood = "calm", fav = true, dur = 300_000),
        track(2, title = "Calm Two", artist = "Bob", energy = 0.3f, mood = "calm", dur = 300_000),
        track(3, title = "Loud", artist = "Bob", energy = 0.9f, mood = "energetic", dur = 300_000),
        track(4, title = "NoAnalysis", artist = "Bob"),
        track(5, title = "Calm Five", artist = "Zed", energy = 0.25f, mood = "calm", dur = 300_000)
    )
    private val history = listOf(play(2, 1.0), play(5, 60.0), play(5, 61.0))

    private fun gen(intent: PlaylistIntent) = PlaylistGenerator.generate(intent, tracks, history, NOW, zone = UTC)

    @Test fun filtersByMoodAndIgnoresUnanalysedTracks() {
        val ids = gen(PlaylistIntent(moods = setOf("calm"))).tracks.map { it.track.id }
        assertEquals(setOf(1L, 2L, 5L), ids.toSet())
        assertFalse(4L in ids)
    }

    @Test fun favoriteOnlyAndArtistAndLanguageFilters() {
        assertEquals(listOf(1L), gen(PlaylistIntent(favoriteOnly = true)).tracks.map { it.track.id })
        assertEquals(setOf(2L, 3L, 4L), gen(PlaylistIntent(artist = "bob")).tracks.map { it.track.id }.toSet())
        assertEquals(listOf(1L), gen(PlaylistIntent(language = LanguageFilter.PERSIAN)).tracks.map { it.track.id })
    }

    @Test fun excludeRecentRemovesRecentlyPlayed() {
        val ids = gen(PlaylistIntent(moods = setOf("calm"), excludeRecentDays = 14)).tracks.map { it.track.id }
        assertFalse(2L in ids)
        assertTrue(5L in ids)
    }

    @Test fun leastPlayedComesFirst() {
        val ids = gen(PlaylistIntent(moods = setOf("calm"), sort = SmartSort.LEAST_PLAYED)).tracks.map { it.track.id }
        assertEquals(1L, ids.first())
        assertEquals(5L, ids.last())
    }

    @Test fun durationTargetStopsFilling() {
        val g = gen(PlaylistIntent(moods = setOf("calm"), durationMinutes = 9))
        assertEquals(2, g.tracks.size)
        assertTrue(g.totalDurationMs >= 9 * 60_000L)
    }

    @Test fun limitAndSimilarity() {
        assertEquals(1, gen(PlaylistIntent(limit = 1)).tracks.size)
        val similar = gen(PlaylistIntent(similarToTrackId = 5L)).tracks.map { it.track.id }
        assertFalse(5L in similar)
        assertEquals(setOf(1L, 2L), similar.take(2).toSet()) // the two calm, low-energy neighbours rank first
    }

    @Test fun durationPhraseIsNotMistakenForTrackCount() {
        val i = PlaylistIntentParser.parse("45 دقیقه آهنگ شاد")
        assertEquals(45, i.durationMinutes)
        assertEquals(PlaylistIntent.DEFAULT_LIMIT, i.limit)
    }

    @Test fun emptyLibraryIsSafe() {
        assertTrue(PlaylistGenerator.generate(PlaylistIntent(), emptyList(), emptyList(), NOW).tracks.isEmpty())
    }
}
