package com.ghadirb.aimusic.analysis

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ghadirb.aimusic.AiMusicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background job that runs AudioAnalyzer (+ LyricsAnalyzer when a sidecar
 * .lrc is found) over tracks that haven't been analyzed yet, a batch at a
 * time, entirely on-device (no network permission exists in the manifest
 * for this job to use even if it wanted to). Scheduled from AiMusicApp
 * right after every library rescan, and periodically as a safety net for
 * tracks a previous run skipped (decode timeout, low battery, etc).
 *
 * This is what makes the Home "مناسب شب"/"مناسب رانندگی" cards real instead
 * of static — see MusicRepository.nightSuitableTracks/drivingSuitableTracks
 * and HomeViewModel.
 */
class AudioAnalysisWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        try {
            val repository = (appContext.applicationContext as AiMusicApp).repository
            val batch = repository.getUnanalyzedTracks(limit = BATCH_SIZE)
            if (batch.isEmpty()) return@withContext Result.success()

            for (track in batch) {
                if (isStopped) break
                val uri = Uri.parse(track.path)
                val analysis = AudioAnalyzer.analyze(appContext, uri)
                if (analysis == null) {
                    repository.markTrackAnalyzedNoResult(track.id)
                    continue
                }

                // Lyrics, when a local .lrc sidecar exists, can override a borderline
                // "neutral" audio-only mood with a clearer signal — never overrides a
                // confident audio-derived CALM/ENERGETIC call.
                var moodTag = analysis.moodTag
                if (moodTag == AudioAnalyzer.MoodTag.NEUTRAL) {
                    when (LyricsAnalyzer.analyze(appContext, uri)) {
                        LyricsAnalyzer.LyricMood.SAD -> moodTag = AudioAnalyzer.MoodTag.CALM
                        LyricsAnalyzer.LyricMood.HAPPY -> moodTag = AudioAnalyzer.MoodTag.ENERGETIC
                        else -> { /* keep neutral */ }
                    }
                }

                repository.saveTrackAnalysis(
                    trackId = track.id,
                    energyLevel = analysis.energyLevel,
                    bpm = analysis.bpm,
                    moodTag = moodTag
                )
            }

            // More tracks may remain — request another run rather than looping here,
            // so we don't block the worker thread pool for a huge library in one shot.
            if (batch.size == BATCH_SIZE) {
                androidx.work.WorkManager.getInstance(appContext).enqueue(
                    androidx.work.OneTimeWorkRequestBuilder<AudioAnalysisWorker>()
                        .setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .build()
                        )
                        .build()
                )
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "audio_analysis"
        private const val BATCH_SIZE = 25
    }
}
