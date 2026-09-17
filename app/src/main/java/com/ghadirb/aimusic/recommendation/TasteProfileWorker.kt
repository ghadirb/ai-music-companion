package com.ghadirb.aimusic.recommendation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ghadirb.aimusic.AiMusicApp
import kotlinx.coroutines.flow.first

/**
 * Periodic background job (see doc: "بعد از چند روز استفاده، برنامه بتواند
 * پروفایل موسیقی کاربر را بسازد"). Reads tracks + listening history,
 * recomputes the taste profile via RecommendationEngine, and persists it —
 * entirely on-device, no network. Scheduled from AiMusicApp.onCreate().
 */
class TasteProfileWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val repository = (applicationContext as AiMusicApp).repository
            val engine = RecommendationEngine(repository)

            val tracks = repository.observeTracks().first()
            if (tracks.isEmpty()) return Result.success()

            val history = repository.recentHistory(1000)
            val historyByTrack = history.groupBy { it.trackId }

            val profile = engine.buildTasteProfile(tracks, historyByTrack)
            repository.saveUserPreference(profile)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "taste_profile_refresh"
    }
}
