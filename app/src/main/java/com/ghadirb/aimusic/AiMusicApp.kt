package com.ghadirb.aimusic

import kotlinx.coroutines.launch
import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
import com.ghadirb.aimusic.billing.PurchaseRepository
import com.ghadirb.aimusic.cloud.CloudApi
import com.ghadirb.aimusic.cloud.CloudConsent
import com.ghadirb.aimusic.data.local.AppDatabase
import com.ghadirb.aimusic.library.LibraryWatchStore
import com.ghadirb.aimusic.library.LibraryWatchWorker
import com.ghadirb.aimusic.music.AhaangScanner
import com.ghadirb.aimusic.music.FileAhaangStore
import com.ghadirb.aimusic.music.FileTextStore
import com.ghadirb.aimusic.music.HttpListingFetcher
import com.ghadirb.aimusic.music.MusicApi
import com.ghadirb.aimusic.music.OnlineDownloader
import com.ghadirb.aimusic.music.OnlineFavorites
import com.ghadirb.aimusic.music.OnlineMusicRepository
import com.ghadirb.aimusic.premium.EntitlementRepository
import com.ghadirb.aimusic.premium.EntitlementTokenVerifier
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.data.repository.VideoRepository
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

    /** Local video library (the "ویدئو" tab). Independent of the music repository. */
    lateinit var videoRepository: VideoRepository
        private set

    /** Gateway client + plan state. Created lazily: nothing here touches the network until the user uses a cloud/premium feature. */
    val cloudApi: CloudApi by lazy { CloudApi(this) }
    val cloudConsent: CloudConsent by lazy { CloudConsent(this) }
    val entitlements: EntitlementRepository by lazy {
        EntitlementRepository(this, cloudApi, EntitlementTokenVerifier(BuildConfig.ENTITLEMENT_PUBLIC_KEY))
    }
    val purchases: PurchaseRepository by lazy { PurchaseRepository(cloudApi, entitlements, this) }

    /** New songs + singer collections from the music sources (the "آهنگ‌های جدید" screen). Nothing runs until that screen is opened. */
    val onlineMusic: OnlineMusicRepository by lazy {
        OnlineMusicRepository(
            MusicApi(cloudApi),
            AhaangScanner(HttpListingFetcher(), FileAhaangStore(java.io.File(filesDir, "ahaang_snapshot.json")))
                // Singer ZIPs sit years back; without this the snapshot cleanup would drop them before the server config is read.
                .also { it.collectionWindowDays = com.ghadirb.aimusic.music.DEFAULT_COLLECTION_WINDOW_DAYS },
        )
    }
    val onlineFavorites: OnlineFavorites by lazy { OnlineFavorites(FileTextStore(java.io.File(filesDir, "online_favorites.json"))) }
    val onlineDownloader: OnlineDownloader by lazy { OnlineDownloader() }

    override fun onCreate() {
        super.onCreate()
        com.ghadirb.aimusic.crash.CrashLogger.install(this)
        database = AppDatabase.getInstance(this)
        repository = MusicRepository(
            trackDao = database.trackDao(),
            historyDao = database.listeningHistoryDao(),
            preferenceDao = database.userPreferenceDao(),
            playlistDao = database.playlistDao(),
            context = this,
            database = database
        )
        videoRepository = VideoRepository(database.videoDao(), this)
        scheduleTasteProfileRefresh()
        scheduleAudioAnalysis()
        // Opt-in only (spec item 7) — nothing scheduled unless the user already turned it on
        // in Settings; re-arming here just survives process death / device reboot.
        if (LibraryWatchStore(this).enabled) {
            LibraryWatchWorker.schedule(this)
            // Also look right now: otherwise songs copied since the last run would wait for the next periodic run.
            LibraryWatchWorker.enqueueNow(this)
        }
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var lastForegroundScan = 0L

    /**
     * Re-indexes songs/videos that are on the phone but unknown to Android's media database (so the app could
     * not see them, even after a reinstall), then refreshes the library. Returns how many files were found,
     * or -1 when storage is not readable yet (no permission). Videos are re-synced when the video tab opens.
     */
    suspend fun rescueUnindexedMedia(): Int {
        val result = com.ghadirb.aimusic.library.MediaRescue.scanAll(this)
        if (!result.readable) return -1
        if (result.found > 0) {
            val added = runCatching { repository.rescanLibrary() }.getOrDefault(emptyList())
            if (added.isNotEmpty()) com.ghadirb.aimusic.analysis.AudioAnalysisWorker.enqueueNow(this)
        }
        return result.found
    }

    /**
     * Cheap on-device MediaStore re-sync whenever the app comes to the foreground (at most once a minute),
     * so a song copied to the phone shows up when the app is opened - no setting needed. New songs are
     * handed to the analyzer like after a manual scan.
     */
    fun rescanLibraryIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundScan < 60_000L) return
        lastForegroundScan = now
        // One time after this update: bring back files that exist on the phone but are missing from MediaStore.
        val rescuePrefs = getSharedPreferences("media_rescue", MODE_PRIVATE)
        if (!rescuePrefs.getBoolean("done_v1", false)) {
            appScope.launch {
                try {
                    if (rescueUnindexedMedia() >= 0) rescuePrefs.edit().putBoolean("done_v1", true).apply()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Try again next time the app comes to the foreground.
                }
            }
        }
        appScope.launch {
            try {
                val added = repository.rescanLibrary()
                if (added.isNotEmpty()) com.ghadirb.aimusic.analysis.AudioAnalysisWorker.enqueueNow(this@AiMusicApp)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No audio permission yet or a provider hiccup: the library screen reports it on manual scan.
            }
        }
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

    /**
     * Feeds Home's "night"/"driving" cards (see AudioAnalyzer). One immediate
     * pass on every app start picks up tracks added since the last run (a
     * fresh library scan or newly-copied files), plus a daily periodic job
     * as a safety net. Entirely on-device — no network constraint needed
     * because this job never uses one.
     */
    private fun scheduleAudioAnalysis() {
        AudioAnalysisWorker.enqueueNow(this)

        val periodicRequest = PeriodicWorkRequestBuilder<AudioAnalysisWorker>(1, TimeUnit.DAYS).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AudioAnalysisWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}
