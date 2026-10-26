package com.ghadirb.aimusic.recommendation

import com.ghadirb.aimusic.TestData.DAY
import com.ghadirb.aimusic.TestData.NOW
import com.ghadirb.aimusic.TestData.UTC
import com.ghadirb.aimusic.TestData.play
import com.ghadirb.aimusic.TestData.track
import com.ghadirb.aimusic.data.local.entity.ListeningHistoryEntity
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.recommendation.cache.CachedItem
import com.ghadirb.aimusic.recommendation.cache.RecommendationCacheCodec
import com.ghadirb.aimusic.recommendation.candidate.Candidate
import com.ghadirb.aimusic.recommendation.candidate.CandidateSourceId
import com.ghadirb.aimusic.recommendation.config.DiversityConfig
import com.ghadirb.aimusic.recommendation.config.ExplorationConfig
import com.ghadirb.aimusic.recommendation.config.RecencyConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationConfig
import com.ghadirb.aimusic.recommendation.config.RecommendationWeights
import com.ghadirb.aimusic.recommendation.config.SkipModelConfig
import com.ghadirb.aimusic.recommendation.diversity.DiversityReRanker
import com.ghadirb.aimusic.recommendation.exploration.DiscoveryOutcomes
import com.ghadirb.aimusic.recommendation.exploration.ExplorationManager
import com.ghadirb.aimusic.recommendation.features.FeatureExtractor
import com.ghadirb.aimusic.recommendation.features.TrackFeatureVector
import com.ghadirb.aimusic.recommendation.profile.TasteProfileCalculator
import com.ghadirb.aimusic.recommendation.ranking.ScoreSignal
import com.ghadirb.aimusic.recommendation.ranking.ScoredTrack
import com.ghadirb.aimusic.recommendation.section.SectionType
import com.ghadirb.aimusic.recommendation.session.SessionAnalyzer
import com.ghadirb.aimusic.recommendation.signals.RecencyDecay
import com.ghadirb.aimusic.recommendation.signals.SkipModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec §29 — unit tests of the on-device recommendation engine (v2): skip/completion/recency signals,
 * taste profile, similarity, diversity, exploration, session adaptation, cold start, small and big libraries.
 * Everything runs on the plain JVM with fixed time, so the results are deterministic.
 */
class RecommendationEngineV2Test {

    private val config = RecommendationConfig()
    private val pipeline = RecommendationPipeline(config)
    private val extractor = FeatureExtractor(config)

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private fun input(tracks: List<TrackEntity>, history: List<ListeningHistoryEntity> = emptyList()) =
        PipelineInput(tracks, history, nowMs = NOW, zone = UTC)

    private fun forYou(tracks: List<TrackEntity>, history: List<ListeningHistoryEntity> = emptyList(), limit: Int = 10): SectionResult {
        val run = pipeline.prepare(input(tracks, history))
        return requireNotNull(pipeline.generate(run, SectionType.FOR_YOU, limit)) { "FOR_YOU must exist for a non-empty library" }
    }

    /** NOW is 08:00 UTC; this puts a completed play at [hourUtc] o'clock, [daysAgo] days back (daysAgo >= 1). */
    private fun playAt(trackId: Long, daysAgo: Int, hourUtc: Int) = ListeningHistoryEntity(
        trackId = trackId,
        startTime = NOW - daysAgo * DAY + (hourUtc - 8) * 3_600_000L,
        listenDurationMs = 100_000,
        completedPercentage = 1f,
        skipped = false,
        replayCount = 0
    )

    private fun skipMinutesAgo(trackId: Long, minutesAgo: Int) = ListeningHistoryEntity(
        trackId = trackId,
        startTime = NOW - minutesAgo * 60_000L,
        listenDurationMs = 30_000,
        completedPercentage = 0.1f,
        skipped = true,
        replayCount = 0
    )

    private fun scored(vectors: List<TrackFeatureVector>): List<ScoredTrack> =
        vectors.mapIndexed { i, v ->
            ScoredTrack(v, Candidate(v.trackId, setOf(CandidateSourceId.FALLBACK), 0.0), 1.0 - i * 0.01, emptyMap(), emptyMap())
        }

    private fun vectorsOf(tracks: List<TrackEntity>) = extractor.extract(tracks, emptyList(), NOW, UTC).vectors

    // ------------------------------------------------------------------------------------------
    // §4 smart skip model
    // ------------------------------------------------------------------------------------------

    @Test fun skipTimingDecidesSeverity() {
        val m = SkipModel(config.skip)
        val strong = m.penalty(5_000L, 0.02f)
        val medium = m.penalty(60_000L, 0.20f)
        val weak = m.penalty(150_000L, 0.75f)
        val neutral = m.penalty(190_000L, 0.95f)
        assertEquals(config.skip.strongPenalty, strong, 1e-9)
        assertEquals(config.skip.mediumPenalty, medium, 1e-9)
        assertEquals(config.skip.weakPenalty, weak, 1e-9)
        assertEquals(config.skip.neutralPenalty, neutral, 1e-9)
        assertTrue(strong > medium && medium > weak && weak > neutral)
    }

    @Test fun skipBetweenMediumAndWeakIsInterpolated() {
        val mid = SkipModel(config.skip).penalty(100_000L, 0.5f)
        assertTrue(mid < config.skip.mediumPenalty)
        assertTrue(mid > config.skip.weakPenalty)
    }

    @Test fun skipThresholdsAreConfigurable() {
        val relaxed = SkipModel(SkipModelConfig(strongSkipMaxMs = 30_000L))
        assertEquals(1.0, relaxed.penalty(20_000L, 0.1f), 1e-9)
        val defaults = SkipModel(config.skip)
        assertTrue(defaults.penalty(20_000L, 0.1f) < 1.0)
    }

    @Test fun finishedSessionHasNoPenaltyButEarlySkipDoes() {
        val m = SkipModel(config.skip)
        assertEquals(0.0, m.penalty(play(1, 1.0)), 1e-9)
        assertTrue(m.penalty(play(1, 1.0, completed = 0.05f, skipped = true)) > 0.5)
        assertTrue(m.isCompleted(play(1, 1.0)))
        assertFalse(m.isCompleted(play(1, 1.0, completed = 0.3f, skipped = true)))
    }

    // ------------------------------------------------------------------------------------------
    // §5 completion is independent of play count
    // ------------------------------------------------------------------------------------------

    @Test fun nineCompletionsDifferFromNineSkipsWithSamePlayCount() {
        val tracks = listOf(track(1), track(2))
        val history =
            (1..9).map { play(1, it.toDouble()) } + play(1, 10.0, completed = 0.05f, skipped = true) +
                (1..9).map { play(2, it.toDouble(), completed = 0.05f, skipped = true) } + play(2, 10.0)
        val set = extractor.extract(tracks, history, NOW, UTC)
        val liked = set.byId.getValue(1L)
        val disliked = set.byId.getValue(2L)
        assertEquals(liked.playCount, disliked.playCount)
        assertTrue(liked.completionRate!! > disliked.completionRate!!)
        assertTrue(disliked.skipRate!! > liked.skipRate!!)
        assertTrue(liked.signal!!.completionScore > disliked.signal!!.completionScore)
    }

    // ------------------------------------------------------------------------------------------
    // §6 recency
    // ------------------------------------------------------------------------------------------

    @Test fun recencyDecaysWithAge() {
        val d = RecencyDecay(config.recency)
        assertEquals(1.0, d.weight(0.0), 1e-9)
        assertEquals(1.0, d.weight(-3.0), 1e-9)
        assertTrue(d.weight(0.5) < 1.0 && d.weight(0.5) > d.weight(1.0))
        assertTrue(d.weight(1.0) > d.weight(7.0))
        assertTrue(d.weight(7.0) > d.weight(30.0))
        assertTrue(d.weight(30.0) > d.weight(180.0))
        // very old interest never disappears completely
        assertEquals(0.05, d.weight(5000.0), 1e-9)
        assertTrue(d.weightForAge(2 * DAY) > d.weightForAge(40 * DAY))
    }

    @Test fun recencyAnchorsMustBeSorted() {
        try {
            RecencyConfig(anchors = listOf(5.0 to 1.0, 1.0 to 0.5))
            fail("unsorted anchors must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------------------------------
    // §12 weights
    // ------------------------------------------------------------------------------------------

    @Test fun baselineWeightsSumToOneAndNormalisationKeepsProportions() {
        val w = RecommendationWeights()
        assertEquals(1.0, w.baselineSum(), 1e-9)
        assertEquals(1.0, w.normalized().total(), 1e-9)
        val heavy = RecommendationWeights(taste = 0.5).normalized()
        assertEquals(1.0, heavy.total(), 1e-9)
        assertTrue(heavy.taste > w.normalized().taste)
    }

    // ------------------------------------------------------------------------------------------
    // §10 similarity
    // ------------------------------------------------------------------------------------------

    @Test fun similarityIsBoundedSymmetricAndMeaningful() {
        val tracks = listOf(
            track(1, artist = "Same", genre = "Rock", mood = "calm", energy = 0.2f, bpm = 70),
            track(2, artist = "Same", genre = "Rock", mood = "calm", energy = 0.25f, bpm = 72),
            track(3, artist = "Other", genre = "Metal", mood = "energetic", energy = 0.9f, bpm = 160),
            track(4)
        )
        val v = extractor.extract(tracks, emptyList(), NOW, UTC).byId
        val engine = com.ghadirb.aimusic.recommendation.similarity.SimilarityEngine()
        val close = engine.trackSimilarity(v.getValue(1L), v.getValue(2L))
        val far = engine.trackSimilarity(v.getValue(1L), v.getValue(3L))
        val bare = engine.trackSimilarity(v.getValue(4L), v.getValue(1L))
        assertTrue(close in 0.0..1.0 && far in 0.0..1.0 && bare in 0.0..1.0)
        assertTrue("close=$close far=$far", close > far)
        assertTrue(close > 0.8)
        assertEquals(1.0, engine.trackSimilarity(v.getValue(1L), v.getValue(1L)), 1e-9)
        assertEquals(engine.trackSimilarity(v.getValue(1L), v.getValue(3L)), engine.trackSimilarity(v.getValue(3L), v.getValue(1L)), 1e-9)
        // almost no shared metadata ⇒ low confidence ⇒ never "very similar"
        assertTrue("bare=$bare", bare < 0.5)
    }

    @Test fun moodAndArtistSimilarity() {
        val tracks = listOf(
            track(1, artist = "ArtistA", genre = "Rock", mood = "calm", energy = 0.2f, bpm = 70),
            track(2, artist = "ArtistB", genre = "Rock", mood = "calm", energy = 0.25f, bpm = 75),
            track(3, artist = "ArtistC", genre = "Metal", mood = "energetic", energy = 0.9f, bpm = 160)
        )
        val vectors = vectorsOf(tracks)
        val engine = com.ghadirb.aimusic.recommendation.similarity.SimilarityEngine()
        val byId = vectors.associateBy { it.trackId }
        assertEquals(1.0, engine.moodSimilarity(byId.getValue(1L), byId.getValue(2L)), 1e-9)
        assertTrue(engine.moodSimilarity(byId.getValue(1L), byId.getValue(3L)) < 0.5)

        val profiles = engine.buildArtistProfiles(vectors)
        val a = profiles.getValue("artista")
        val b = profiles.getValue("artistb")
        val c = profiles.getValue("artistc")
        assertTrue(engine.artistSimilarity(a, b) > engine.artistSimilarity(a, c))
    }

    // ------------------------------------------------------------------------------------------
    // §13 diversity
    // ------------------------------------------------------------------------------------------

    @Test fun diversityLimitsTracksPerArtistInTopTen() {
        val tracks = (1L..16L).map { id -> track(id, artist = if (id <= 6L) "X" else "B$id") }
        val result = DiversityReRanker(DiversityConfig()).reRank(scored(vectorsOf(tracks)), limit = 10)
        assertEquals(10, result.size)
        assertTrue(result.count { it.vector.artistId == "x" } <= 2)
        // the best tracks keep their order: the two best "X" tracks lead the list
        assertEquals(listOf(1L, 2L), result.take(2).map { it.trackId })
        assertEquals(result.size, result.map { it.trackId }.distinct().size)
    }

    @Test fun diversityLimitsTracksPerAlbumInTopTen() {
        val tracks = (1L..16L).map { id -> track(id, artist = "Ar$id", album = if (id <= 6L) "SameAlbum" else "Al$id") }
        val result = DiversityReRanker(DiversityConfig()).reRank(scored(vectorsOf(tracks)), limit = 10)
        assertEquals(10, result.size)
        assertTrue(result.count { it.vector.albumId == "samealbum" } <= 2)
    }

    @Test fun diversityAdaptsInSmallLibraries() {
        val tracks = (1L..3L).map { id -> track(id, artist = "Solo", album = "OnlyAlbum") }
        val result = DiversityReRanker(DiversityConfig()).reRank(scored(vectorsOf(tracks)), limit = 10)
        // limits relax instead of returning a short or empty list
        assertEquals(3, result.size)
    }

    // ------------------------------------------------------------------------------------------
    // §14 exploration
    // ------------------------------------------------------------------------------------------

    @Test fun explorationBaselineIsSeventyTwentyTen() {
        val plan = ExplorationManager().plan(10, DiscoveryOutcomes())
        assertEquals(7, plan.exploitation)
        assertEquals(2, plan.similarDiscovery)
        assertEquals(1, plan.exploration)
    }

    @Test fun explorationSlotsAlwaysAddUpToListSize() {
        val manager = ExplorationManager()
        for (size in listOf(1, 3, 5, 8, 10, 20, 50)) {
            for (outcomes in listOf(DiscoveryOutcomes(), DiscoveryOutcomes(30, 0), DiscoveryOutcomes(0, 30))) {
                val p = manager.plan(size, outcomes)
                assertEquals("size=$size $outcomes", size, p.exploitation + p.similarDiscovery + p.exploration)
            }
        }
    }

    @Test fun explorationGrowsWhenNewTracksAreAcceptedAndShrinksWhenSkipped() {
        val manager = ExplorationManager()
        val neutral = manager.plan(20, DiscoveryOutcomes()).rate
        val accepting = manager.plan(20, DiscoveryOutcomes(accepted = 20, rejected = 0)).rate
        val rejecting = manager.plan(20, DiscoveryOutcomes(accepted = 0, rejected = 20)).rate
        assertTrue(accepting > neutral)
        assertTrue(neutral > rejecting)
        assertTrue(rejecting >= config.exploration.minExploration - 1e-9)
        assertTrue(accepting <= config.exploration.maxExploration + 1e-9)
    }

    @Test fun explorationRespectsAnExplicitNoDiscoverySection() {
        val p = ExplorationManager().plan(10, DiscoveryOutcomes(accepted = 20, rejected = 0), ExplorationConfig(1.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(10, p.exploitation)
        assertEquals(0, p.similarDiscovery)
        assertEquals(0, p.exploration)
    }

    @Test fun discoveryOutcomesComeFromTheFirstPlayOfEachTrack() {
        val history = listOf(
            play(1, 5.0),                                           // first play completed  → accepted
            play(2, 5.0, completed = 0.1f, skipped = true),         // first play skipped    → rejected
            play(3, 100.0),                                         // older than the window → ignored
            play(1, 2.0, completed = 0.1f, skipped = true)          // later skip of an accepted track does not matter
        )
        assertEquals(DiscoveryOutcomes(accepted = 1, rejected = 1), ExplorationManager.outcomesFromHistory(history, NOW))
    }

    // ------------------------------------------------------------------------------------------
    // §9 session intelligence
    // ------------------------------------------------------------------------------------------

    @Test fun noHistoryMeansNoSession() {
        val set = extractor.extract(listOf(track(1)), emptyList(), NOW, UTC)
        val s = SessionAnalyzer(config).analyze(set, emptyList(), emptyList(), NOW, UTC)
        assertFalse(s.isActive)
        assertEquals(0.0, s.skipPressure, 1e-9)
        assertEquals(1.0, s.adaptationFactor, 1e-9)
    }

    @Test fun skipStreakIsDetectedAndRaisesAdaptation() {
        val tracks = listOf(
            track(1, energy = 0.9f, bpm = 140, mood = "energetic"),
            track(2, energy = 0.9f, bpm = 140, mood = "energetic"),
            track(3, energy = 0.9f, bpm = 140, mood = "energetic")
        )
        val history = listOf(skipMinutesAgo(1, 30), skipMinutesAgo(2, 20), skipMinutesAgo(3, 10))
        val set = extractor.extract(tracks, history, NOW, UTC)
        val s = SessionAnalyzer(config).analyze(set, history, emptyList(), NOW, UTC)
        assertTrue(s.isActive)
        assertEquals(3, s.consecutiveSkips)
        assertEquals(1.0, s.skipPressure, 1e-9)
        assertEquals(3.0, s.adaptationFactor, 1e-9)
        assertEquals(setOf(1L, 2L, 3L), s.recentlySkipped.toSet())
        assertEquals(0.9, s.skippedEnergyPattern!!, 0.01)
    }

    @Test fun rankingAdaptsToASkipStreak() {
        val tracks = listOf(
            track(1, energy = 0.9f, bpm = 140, mood = "energetic"),
            track(2, energy = 0.9f, bpm = 140, mood = "energetic"),
            track(3, energy = 0.9f, bpm = 140, mood = "energetic"),
            track(4, energy = 0.9f, bpm = 140, mood = "energetic"),   // same kind as what was just skipped
            track(5, energy = 0.2f, bpm = 70, mood = "calm")          // the opposite
        )
        val history = listOf(skipMinutesAgo(1, 30), skipMinutesAgo(2, 20), skipMinutesAgo(3, 10))
        val picks = forYou(tracks, history).picks.associateBy { it.trackId }
        val similarToSkipped = picks.getValue(4L).signals.getValue(ScoreSignal.SESSION)
        val different = picks.getValue(5L).signals.getValue(ScoreSignal.SESSION)
        assertTrue("skipped-like=$similarToSkipped different=$different", similarToSkipped < different)
        assertTrue(similarToSkipped <= -0.9)
        // the skipped tracks themselves are pushed down
        assertEquals(-1.0, picks.getValue(1L).signals.getValue(ScoreSignal.SESSION), 1e-9)
    }

    // ------------------------------------------------------------------------------------------
    // §7 / §8 taste profile & time of day
    // ------------------------------------------------------------------------------------------

    @Test fun profileLearnsCalmTasteFromBehaviour() {
        val tracks = (1L..10L).map { id -> track(id, artist = "C$id", energy = 0.15f, bpm = 70, mood = "calm") } +
            (11L..20L).map { id -> track(id, artist = "E$id", energy = 0.9f, bpm = 150, mood = "energetic") }
        val history = (1L..5L).flatMap { id -> (1..6).map { d -> play(id, d.toDouble()) } }
        val set = extractor.extract(tracks, history, NOW, UTC)
        val profile = TasteProfileCalculator(config).build(set, history, NOW, UTC)
        assertFalse(profile.isEmpty)
        assertTrue(profile.energy!!.mean < 0.3)
        assertEquals(1.0, profile.moods.getValue("calm"), 1e-9)
        assertTrue(profile.bpm!!.mean < 100.0)
    }

    @Test fun onlyCalmListenerGetsCalmTracksRankedAboveEnergeticOnes() {
        val tracks = (1L..10L).map { id -> track(id, artist = "C$id", energy = 0.15f, bpm = 70, mood = "calm") } +
            (11L..20L).map { id -> track(id, artist = "E$id", energy = 0.9f, bpm = 150, mood = "energetic") }
        val history = (1L..5L).flatMap { id -> (1..6).map { d -> play(id, d.toDouble()) } }
        val picks = forYou(tracks, history, limit = 20).picks
        val calmUnplayed = picks.filter { it.trackId in 6L..10L }
        val energeticUnplayed = picks.filter { it.trackId in 11L..20L }
        assertTrue(calmUnplayed.isNotEmpty() && energeticUnplayed.isNotEmpty())
        val tasteCalm = calmUnplayed.map { it.signals.getValue(ScoreSignal.TASTE) }.average()
        val tasteEnergetic = energeticUnplayed.map { it.signals.getValue(ScoreSignal.TASTE) }.average()
        assertTrue("taste calm=$tasteCalm energetic=$tasteEnergetic", tasteCalm > tasteEnergetic)
        assertTrue(calmUnplayed.map { it.score }.average() > energeticUnplayed.map { it.score }.average())
    }

    @Test fun profileLearnsDifferentTasteForNightAndAfternoon() {
        val tracks = (1L..4L).map { id -> track(id, artist = "C$id", energy = 0.15f, mood = "calm") } +
            (5L..8L).map { id -> track(id, artist = "E$id", energy = 0.9f, mood = "energetic") }
        val history = (1L..4L).flatMap { id -> (1..8).map { d -> playAt(id, d, hourUtc = 1) } } +       // 01:00 → night
            (5L..8L).flatMap { id -> (1..8).map { d -> playAt(id, d, hourUtc = 14) } }                  // 14:00 → afternoon
        val set = extractor.extract(tracks, history, NOW, UTC)
        val profile = TasteProfileCalculator(config).build(set, history, NOW, UTC)
        val night = profile.dayparts.getValue(TimeBuckets.NIGHT).energy!!.mean
        val afternoon = profile.dayparts.getValue(TimeBuckets.AFTERNOON).energy!!.mean
        assertTrue("night=$night afternoon=$afternoon", night < afternoon)
        assertTrue(night < 0.3 && afternoon > 0.7)
    }

    // ------------------------------------------------------------------------------------------
    // §15 cold start, §11 candidates & duplicates, small / big / one-artist libraries
    // ------------------------------------------------------------------------------------------

    private fun mixedLibrary(n: Long): List<TrackEntity> {
        val genres = listOf("Rock", "Pop", "Jazz")
        val moods = listOf("calm", "happy", "energetic", "sad")
        return (1L..n).map { id ->
            track(
                id, artist = "Art${id % 7}", genre = genres[(id % 3).toInt()],
                energy = 0.2f + 0.03f * (id % 20), mood = moods[(id % 4).toInt()], bpm = 70 + (id * 3).toInt()
            )
        }
    }

    @Test fun coldStartStillProducesExplainedRecommendations() {
        val tracks = mixedLibrary(30)
        val run = pipeline.prepare(input(tracks))
        assertFalse(run.session.isActive)
        val results = pipeline.generateAll(run)
        assertTrue(results.isNotEmpty())
        val section = results.first { it.section.type == SectionType.FOR_YOU }.section
        assertEquals(10, section.items.size)
        assertTrue(section.items.all { it.reasons.isNotEmpty() })
        for (r in results) {
            val ids = r.section.items.map { it.track.id }
            assertEquals("duplicates in ${r.section.type}", ids.size, ids.distinct().size)
            assertTrue(ids.size <= 10)
        }
    }

    @Test fun singleArtistFlooringIsPreventedWhenThereIsChoice() {
        // 6 artists x 5 tracks; the user only ever listens to artist 0.
        val tracks = (1L..30L).map { id -> track(id, artist = "Artist${(id - 1) / 5}", album = "Album$id", genre = "Pop") }
        val history = (1L..5L).flatMap { id -> (1..6).map { d -> play(id, d.toDouble()) } }
        val items = forYou(tracks, history).section.items
        assertEquals(10, items.size)
        val perArtist = items.groupBy { it.track.artist }.mapValues { it.value.size }
        assertTrue("per artist: $perArtist", perArtist.values.all { it <= 2 })
    }

    @Test fun fiveTrackLibraryDoesNotCrashAndReturnsEverything() {
        val tracks = (1L..5L).map { id -> track(id, artist = "Same", album = "OneAlbum") }
        val section = forYou(tracks, listOf(play(1, 1.0), play(2, 2.0, completed = 0.1f, skipped = true))).section
        assertEquals(5, section.items.size)
        assertEquals(5, section.items.map { it.track.id }.distinct().size)
    }

    @Test fun notInterestedTracksAreNeverRecommended() {
        val tracks = mixedLibrary(20).map { if (it.id == 3L) it.copy(notInterested = true) else it }
        val results = pipeline.generateAll(pipeline.prepare(input(tracks)))
        assertTrue(results.flatMap { r -> r.section.items.map { it.track.id } }.none { it == 3L })
    }

    @Test fun bigLibraryStaysBoundedAndUnique() {
        val tracks = (1L..3000L).map { id ->
            track(id, artist = "Art${id % 150}", album = "Album${id % 400}", genre = "G${id % 12}", energy = 0.1f + 0.008f * (id % 100), bpm = 60 + (id % 100).toInt(), mood = "calm")
        }
        val history = (1L..400L).map { id -> play(id, (id % 50).toDouble() + 1.0) }
        val run = pipeline.prepare(input(tracks, history))
        val results = pipeline.generateAll(run)
        assertTrue(results.isNotEmpty())
        for (r in results) {
            val ids = r.section.items.map { it.track.id }
            assertTrue(ids.size <= 10)
            assertEquals(ids.size, ids.distinct().size)
        }
    }

    @Test fun sameInputGivesSameRecommendations() {
        val tracks = mixedLibrary(40)
        val history = (1L..10L).map { id -> play(id, id.toDouble()) }
        assertEquals(forYou(tracks, history).picks.map { it.trackId }, forYou(tracks, history).picks.map { it.trackId })
    }

    // ------------------------------------------------------------------------------------------
    // §17 sections, §19 cache
    // ------------------------------------------------------------------------------------------

    @Test fun everySectionTypeHasPersianTitleAndReason() {
        assertEquals(6, SectionType.values().size)
        assertTrue(SectionType.values().all { it.titleFa.isNotBlank() && it.defaultReasonFa.isNotBlank() })
        assertEquals(SectionType.TONIGHT, SectionType.fromId("tonight"))
        assertNull(SectionType.fromId("does-not-exist"))
    }

    @Test fun sectionsUseDifferentStrategies() {
        val tracks = mixedLibrary(40)
        val history = (1L..12L).flatMap { id -> (1..3).map { d -> play(id, d.toDouble()) } }
        val run = pipeline.prepare(input(tracks, history))
        val discover = pipeline.generate(run, SectionType.DISCOVER)!!
        // "discover" only offers tracks that were never played
        assertTrue(discover.picks.all { it.vector.isUnplayed })
        val main = pipeline.generate(run, SectionType.FOR_YOU)!!
        assertTrue(main.picks.any { !it.vector.isUnplayed })
    }

    @Test fun cacheFreshnessFollowsTtl() {
        val ttl = config.cache.ttlMs
        assertTrue(RecommendationCacheCodec.isFresh(NOW, NOW + 1_000, ttl))
        assertFalse(RecommendationCacheCodec.isFresh(NOW, NOW + ttl + 1, ttl))
        assertFalse(RecommendationCacheCodec.isFresh(NOW + 10_000, NOW, ttl))
    }

    @Test fun cacheCodecRoundTripsAndSurvivesCorruption() {
        val items = listOf(
            CachedItem(7L, 0.42, "FAVORITE", listOf(Reason(ReasonType.FAVORITE_ARTIST, detail = "X"), Reason(ReasonType.NOT_PLAYED_LONG, number = 45))),
            CachedItem(9L, 0.10, "FALLBACK", emptyList())
        )
        val decoded = RecommendationCacheCodec.decodeItems(RecommendationCacheCodec.encodeItems(items))!!
        assertEquals(listOf(7L, 9L), decoded.map { it.trackId })
        assertEquals("X", decoded[0].reasons[0].detail)
        assertEquals(45, decoded[0].reasons[1].number)
        assertEquals("FALLBACK", decoded[1].source)

        // a reason type that no longer exists is dropped, the rest of the row survives
        val future = RecommendationCacheCodec.decodeItems("""[{"t":1,"s":0.5,"src":"X","r":[{"y":"NOPE_TYPE"},{"y":"FAVORITE"}]}]""")!!
        assertEquals(1, future[0].reasons.size)
        assertEquals(ReasonType.FAVORITE, future[0].reasons[0].type)

        assertNull(RecommendationCacheCodec.decodeItems("not json"))
    }
}
