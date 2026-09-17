package com.ghadirb.aimusic

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ghadirb.aimusic.data.local.AppDatabase
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.recommendation.TasteProfileWorker
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Holds simple hand-rolled singletons (DB + repository)
 * instead of a DI framework to keep the MVP small and easy to read/modify.
 */
class AiMusicApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: MusicRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = MusicRepository(
            trackDao = database.trackDao(),
            historyDao = database.listeningHistoryDao(),
            preferenceDao = database.userPreferenceDao(),
            playlistDao = database.playlistDao(),
            context = this
        )
        scheduleTasteProfileRefresh()
    }

    /**
     * Runs roughly once a day, on-device only (NetworkType.NOT_REQUIRED —
     * this job never touches the network). KEEP policy means re-installing
     * or restarting the app doesn't reset the schedule.
     */
    private fun scheduleTasteProfileRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<TasteProfileWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TasteProfileWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
