package com.ghadirb.aimusic

import android.app.Application
import com.ghadirb.aimusic.data.local.AppDatabase
import com.ghadirb.aimusic.data.repository.MusicRepository

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
            context = this
        )
    }
}
