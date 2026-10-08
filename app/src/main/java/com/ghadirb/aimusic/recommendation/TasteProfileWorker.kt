package com.ghadirb.aimusic.recommendation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.recommendation.profile.TasteProfileCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Periodic background job (see doc: "بعد از چند روز استفاده، برنامه بتواند
 * پروفایل موسیقی کاربر را بسازد"). Reads tracks + listening history and, entirely on-device
 * (no network), persists:
 *  1. the legacy flat profile (favourite artists/genres/…), still used by backup and the taste screens, and
 *  2. the v2 multi-dimensional [com.ghadirb.aimusic.recommendation.profile.TasteProfile] as JSON
 *     (artist/genre/mood/energy/BPM/duration/era/language + time-of-day and weekday/weekend taste).
 * Afterwards it prunes old recommendation events and pre-computes the Home sections so Home opens instantly.
 * Scheduled from AiMusicApp.onCreate().
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

            val now = System.currentTimeMillis()
            val history = repository.recentHistory(3000)
            val legacy = engine.buildTasteProfile(tracks, history.groupBy { it.trackId })

            // The smart profile is an addition: if it fails, the legacy profile must still be saved.
            val smartJson = try {
                engine.buildSmartProfile(now)?.let(TasteProfileCodec::encode).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            repository.saveUserPreference(legacy.copy(profileJson = smartJson))

            // Housekeeping + warm cache: best effort, never turns a successful profile build into a retry.
            try {
                repository.pruneRecommendationData(now)
                engine.refreshSections(force = true, nowMs = now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ignore
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "taste_profile_refresh"
    }
}
