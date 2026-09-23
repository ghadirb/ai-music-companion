package com.ghadirb.aimusic.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.analysis.AudioAnalyzer
import com.ghadirb.aimusic.analysis.LyricsAnalyzer
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.lyrics.LyricsSource
import com.ghadirb.aimusic.recommendation.TrackSimilarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Spec item 7 — "Smart Library Watch". Entirely optional (off by default, one switch in
 * Settings): periodically re-scans MediaStore for files the user hasn't seen yet, using the
 * same on-device [MusicRepository.rescanLibrary] the Library screen's manual refresh already
 * uses — no extra permission, no [android.os.FileObserver], no background folder access beyond
 * what the app already has.
 *
 * New tracks are analyzed immediately (small batch — reuses [AudioAnalyzer], the same on-device
 * analysis [com.ghadirb.aimusic.analysis.AudioAnalysisWorker] runs for the whole library) so the
 * result message can be genuinely specific ("2 مناسب Workout") instead of guessed; if analysis is
 * inconclusive for a track it's simply left out of those categories, never fabricated.
 */
class LibraryWatchWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val store = LibraryWatchStore(appContext)
            if (!store.enabled) return@withContext Result.success()

            val repository = (appContext.applicationContext as AiMusicApp).repository
            val newTracks = repository.rescanLibrary()
            if (newTracks.isEmpty()) return@withContext Result.success()

            val analyzed = newTracks.map { analyzeOne(repository, it) }
            store.pendingSummary = buildSummary(repository, analyzed)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Mirrors AudioAnalysisWorker's per-track analysis so a freshly-found file is categorized right away. */
    private suspend fun analyzeOne(repository: MusicRepository, track: TrackEntity): TrackEntity {
        return try {
            val uri = android.net.Uri.parse(track.path)
            val analysis = AudioAnalyzer.analyze(appContext, uri) ?: run {
                repository.markTrackAnalyzedNoResult(track.id)
                return track
            }
            var moodTag = analysis.moodTag
            when (LyricsAnalyzer.analyzeText(LyricsSource(appContext).readRawText(track))) {
                LyricsAnalyzer.LyricMood.SAD -> moodTag = AudioAnalyzer.MoodTag.SAD
                LyricsAnalyzer.LyricMood.HAPPY -> moodTag = AudioAnalyzer.MoodTag.HAPPY
                else -> Unit
            }
            repository.saveTrackAnalysis(track.id, analysis.energyLevel, analysis.bpm, moodTag)
            track.copy(energyLevel = analysis.energyLevel, bpm = analysis.bpm, moodTag = moodTag, analyzed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            runCatching { repository.markTrackAnalyzedNoResult(track.id) }
            track
        }
    }

    private suspend fun buildSummary(repository: MusicRepository, newTracks: List<TrackEntity>): String {
        val favorites = repository.observeFavorites().first()
        val workoutCount = newTracks.count { isWorkoutFit(it) }
        val similarCount = if (favorites.isEmpty()) 0 else newTracks.count { track ->
            favorites.any { fav -> TrackSimilarity.score(fav, track) >= SIMILAR_TO_FAVORITE_THRESHOLD }
        }
        return buildString {
            append("${newTracks.size} آهنگ جدید پیدا شد")
            if (workoutCount > 0) append("، $workoutCount آهنگ مناسب ورزش")
            if (similarCount > 0) append("، $similarCount آهنگ شبیه موردعلاقه‌های شما")
            append(".")
        }
    }

    private fun isWorkoutFit(track: TrackEntity): Boolean {
        val bpm = track.bpm ?: return false
        return EnergyBand.HIGH.contains(track.energyLevel) && bpm in WORKOUT_BPM_RANGE
    }

    companion object {
        const val WORK_NAME = "library_watch"
        const val IMMEDIATE_WORK_NAME = "library_watch_immediate"
        private const val MAX_ATTEMPTS = 3
        private const val SIMILAR_TO_FAVORITE_THRESHOLD = 6.0
        private val WORKOUT_BPM_RANGE = 120..160

        /** Enables the periodic check (call after the user turns the setting on, and once at app start if it's already on). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<LibraryWatchWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /** Disables the periodic check (call when the user turns the setting off). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** One immediate check, e.g. right after the user enables the setting. */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LibraryWatchWorker>().build()
            )
        }
    }
}
