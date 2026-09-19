package com.ghadirb.aimusic.analysis

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.lyrics.LyricsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background job that runs AudioAnalyzer (+ LyricsAnalyzer when a sidecar .lrc exists) over tracks
 * that have not been analysed yet, entirely on-device.
 *
 * - Runs batch after batch until the library is done, checking [isStopped] between files so the
 *   user can cancel from the UI (WorkManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)).
 * - A corrupt/unsupported file is marked "analysed without result" and skipped; it never stops the run.
 * - Progress is observable through the DB (analysed vs total tracks), see MusicRepository.observeAnalysisProgress.
 */
class AudioAnalysisWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repository = (appContext.applicationContext as AiMusicApp).repository
            while (!isStopped) {
                val batch = repository.getUnanalyzedTracks(limit = BATCH_SIZE)
                if (batch.isEmpty()) break
                for (track in batch) {
                    if (isStopped) break
                    analyzeOne(repository, track)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient DB problem: retry a couple of times, then give up until the next schedule.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun analyzeOne(
        repository: com.ghadirb.aimusic.data.repository.MusicRepository,
        track: com.ghadirb.aimusic.data.local.entity.TrackEntity
    ) {
        val trackId = track.id
        try {
            val uri = Uri.parse(track.path)
            val analysis = AudioAnalyzer.analyze(appContext, uri)
            if (analysis == null) {
                repository.markTrackAnalyzedNoResult(trackId)
                return
            }
            // A user-supplied local LRC gives an explicit semantic signal, so it takes precedence
            // over the coarse audio-energy heuristic.
            var moodTag = analysis.moodTag
            when (LyricsAnalyzer.analyzeText(LyricsSource(appContext).readRawText(track))) {
                LyricsAnalyzer.LyricMood.SAD -> moodTag = AudioAnalyzer.MoodTag.SAD
                LyricsAnalyzer.LyricMood.HAPPY -> moodTag = AudioAnalyzer.MoodTag.HAPPY
                else -> Unit
            }
            repository.saveTrackAnalysis(trackId, analysis.energyLevel, analysis.bpm, moodTag)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // One broken/unsupported media item must not block every later song.
            runCatching { repository.markTrackAnalyzedNoResult(trackId) }
        }
    }

    companion object {
        const val WORK_NAME = "audio_analysis"
        const val IMMEDIATE_WORK_NAME = "audio_analysis_immediate"
        private const val BATCH_SIZE = 25
        private const val MAX_ATTEMPTS = 3

        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AudioAnalysisWorker>().build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
