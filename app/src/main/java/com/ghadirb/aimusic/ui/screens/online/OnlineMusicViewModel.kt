package com.ghadirb.aimusic.ui.screens.online

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ghadirb.aimusic.AiMusicApp
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.music.ArchiveOutcome
import com.ghadirb.aimusic.music.ArchiveImporter
import com.ghadirb.aimusic.music.ArchiveTrack
import com.ghadirb.aimusic.music.CollectionCopies
import com.ghadirb.aimusic.music.DownloadKind
import com.ghadirb.aimusic.music.DownloadResult
import com.ghadirb.aimusic.music.OnlineArtist
import com.ghadirb.aimusic.music.OnlineCollection
import com.ghadirb.aimusic.music.OnlineDownloader
import com.ghadirb.aimusic.music.OnlineTrack
import com.ghadirb.aimusic.music.PublicMusicStore
import com.ghadirb.aimusic.music.ZipFetch
import com.ghadirb.aimusic.playback.PlayerController
import com.ghadirb.aimusic.playback.onlineTrackIdOf
import com.ghadirb.aimusic.playback.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class OnlineTab { NEW, SINGERS, FAVORITES }

/** A one-shot message for the user: a string resource plus its arguments (resolved by the screen). */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

data class ArchiveState(
    val collection: OnlineCollection,
    val loading: Boolean = true,
    val entries: List<ArchiveTrack> = emptyList(),
    val error: UiMessage? = null,
)

data class OnlineUiState(
    val tab: OnlineTab = OnlineTab.NEW,
    val windowDays: Int = 90,
    val query: String = "",
    val tracks: List<OnlineTrack> = emptyList(),
    val loading: Boolean = true,
    val serverReachable: Boolean = true,
    val artists: List<OnlineArtist> = emptyList(),
    val artistsLoading: Boolean = false,
    /** Days of old ZIP history still being read in the background (0 = finished). */
    val scanRemaining: Int = 0,
    val openArtist: OnlineArtist? = null,
    val collections: List<OnlineCollection> = emptyList(),
    val archive: ArchiveState? = null,
    val favorites: List<OnlineTrack> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val nowTitle: String? = null,
    val nowArtist: String? = null,
    val isPlaying: Boolean = false,
    /** 0..100 while a download runs, -1 = running without a known size, null = idle. */
    val downloadPercent: Int? = null,
    val message: UiMessage? = null,
)

@UnstableApi
class OnlineMusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AiMusicApp
    private val repo = app.onlineMusic
    private val favorites = app.onlineFavorites
    private val downloader: OnlineDownloader = app.onlineDownloader
    private val importer = ArchiveImporter(downloader, File(app.cacheDir, "zipcache"))
    private val controller = PlayerController(application)

    private val _ui = MutableStateFlow(OnlineUiState())
    val ui: StateFlow<OnlineUiState> = _ui.asStateFlow()

    private val failedUrls = HashMap<String, MutableSet<String>>()
    private val queued = HashMap<String, OnlineTrack>()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var singersJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownload = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncNowPlaying()
            if (isPlaying) onlineTrackIdOf(controller.currentMediaId())?.let { id ->
                val uri = controller.currentUri()
                queued[id]?.versions?.firstOrNull { it.url == uri }?.let { repo.reportSuccess(it) }
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncNowPlaying()
        override fun onPlayerError(error: PlaybackException) {
            val id = onlineTrackIdOf(controller.currentMediaId()) ?: return
            val track = queued[id] ?: return
            val index = controller.currentIndex()
            val badUri = controller.currentUri()
            viewModelScope.launch { recover(track, index, badUri) }
        }
    }

    init {
        controller.addListener(listener)
        controller.connect { syncNowPlaying() }
        refreshFavorites()
        reload(refreshSources = true)
        // Songs downloaded by earlier versions sit in the app's private folder; move them into Music/Navasa.
        viewModelScope.launch {
            val old = withContext(Dispatchers.IO) { PublicMusicStore.legacyFiles(musicDir()) }
            if (old.isNotEmpty()) addToLibrary(old)
        }
    }

    // ------------------------------------------------------------------ list of new songs

    fun setTab(tab: OnlineTab) {
        _ui.update { it.copy(tab = tab, openArtist = null, collections = emptyList(), archive = null) }
        if (tab == OnlineTab.SINGERS && _ui.value.artists.isEmpty()) loadSingers()
    }

    fun setWindow(days: Int) {
        if (days == _ui.value.windowDays) return
        _ui.update { it.copy(windowDays = days) }
        reload(refreshSources = true)
    }

    fun setQuery(text: String) {
        _ui.update { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(350); reload(refreshSources = false) }
    }

    fun reload(refreshSources: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            try {
                val s = _ui.value
                if (refreshSources) repo.refresh(s.windowDays)
                val feed = repo.loadTracks(s.windowDays, _ui.value.query)
                _ui.update { it.copy(tracks = feed.tracks, serverReachable = feed.serverReachable, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(loading = false, serverReachable = false) }
            }
        }
    }

    // ------------------------------------------------------------------ singer collections

    private fun loadSingers() {
        singersJob?.cancel()
        singersJob = viewModelScope.launch {
            _ui.update { it.copy(artistsLoading = true) }
            try {
                _ui.update { it.copy(artists = repo.artists(it.windowDays), artistsLoading = false) }
                // Old day folders are walked in small resumable steps; the list grows while it runs.
                var guard = 0
                while (guard < 40) {
                    guard += 1
                    val r = repo.scanCollections(_ui.value.windowDays) { _, remaining -> _ui.update { it.copy(scanRemaining = remaining) } }
                    _ui.update { it.copy(artists = repo.artists(it.windowDays), scanRemaining = r.remainingDays) }
                    if (r.remainingDays <= 0 || r.stoppedEarly || r.scannedDays == 0) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep whatever was found
            } finally {
                _ui.update { it.copy(artistsLoading = false) }
            }
        }
    }

    fun openArtist(artist: OnlineArtist) {
        _ui.update { it.copy(openArtist = artist, collections = emptyList(), archive = null) }
        viewModelScope.launch {
            val list = try { repo.collections(artist.key, _ui.value.windowDays) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            _ui.update { if (it.openArtist?.key == artist.key) it.copy(collections = CollectionCopies.group(list, repo.priority)) else it }
        }
    }

    fun openCollection(c: OnlineCollection) {
        _ui.update { it.copy(archive = ArchiveState(c)) }
        viewModelScope.launch {
            val outcome = try { repo.archive(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { ArchiveOutcome.Offline }
            val state = when (outcome) {
                is ArchiveOutcome.Ok -> ArchiveState(c, false, outcome.entries)
                is ArchiveOutcome.Offline -> ArchiveState(c, false, error = UiMessage(R.string.online_archive_offline))
                is ArchiveOutcome.Unavailable ->
                    ArchiveState(c, false, error = UiMessage(if (outcome.temporary) R.string.online_archive_temporary else R.string.online_archive_permanent))
            }
            _ui.update { if (it.archive?.collection?.url == c.url) it.copy(archive = state) else it }
        }
    }

    /** Back one level: archive -> collections -> singers. Returns false when already at the top. */
    fun back(): Boolean {
        val s = _ui.value
        return when {
            s.archive != null -> { _ui.update { it.copy(archive = null) }; true }
            s.openArtist != null -> { _ui.update { it.copy(openArtist = null, collections = emptyList()) }; true }
            else -> false
        }
    }

    // ------------------------------------------------------------------ playback

    fun playFrom(list: List<OnlineTrack>, index: Int) {
        val window = list.drop((index - 20).coerceAtLeast(0)).take(100)
        val start = window.indexOf(list.getOrNull(index)).coerceAtLeast(0)
        val items = ArrayList<MediaItem>(); var startItem = 0
        for ((i, t) in window.withIndex()) {
            val v = repo.pickVersion(t) ?: continue
            if (i == start) startItem = items.size
            queued[t.id] = t
            items += t.toMediaItem(v)
        }
        if (items.isEmpty()) { say(UiMessage(R.string.online_no_link)); return }
        controller.playItems(items, startItem)
    }

    fun addNext(t: OnlineTrack) = enqueue(t) { controller.addItemNext(it) }
    fun addLast(t: OnlineTrack) = enqueue(t) { controller.addItemLast(it) }

    private fun enqueue(t: OnlineTrack, add: (MediaItem) -> Unit) {
        val v = repo.pickVersion(t) ?: run { say(UiMessage(R.string.online_no_link)); return }
        queued[t.id] = t
        add(t.toMediaItem(v))
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun skipNext() = controller.skipNext()

    /** Another source's copy of the SAME song, or a skip when nobody has one that works. */
    private suspend fun recover(track: OnlineTrack, index: Int, badUri: String?) {
        val failed = failedUrls.getOrPut(track.id) { HashSet() }
        if (badUri != null) {
            failed += badUri
            track.versions.firstOrNull { it.url == badUri }?.let { repo.reportFailure(it) }
        }
        val next = repo.pickVersion(track, failed) ?: repo.resolveFromServer(track, failed)?.takeIf { it.url !in failed }
        if (next != null) {
            controller.replaceItemAt(index, track.toMediaItem(next))
            say(UiMessage(R.string.online_fallback_used, listOf(next.source)))
        } else {
            say(UiMessage(R.string.online_all_failed))
            if (controller.hasNext()) controller.skipNext()
        }
    }

    private fun syncNowPlaying() {
        val online = onlineTrackIdOf(controller.currentMediaId()) != null || controller.currentMediaId()?.startsWith("zip:") == true
        _ui.update {
            it.copy(
                nowTitle = if (online) controller.currentTitle() else null,
                nowArtist = if (online) controller.currentArtist() else null,
                isPlaying = online && controller.isPlaying(),
            )
        }
    }

    // ------------------------------------------------------------------ favourites

    fun toggleFavorite(t: OnlineTrack) { favorites.toggle(t); refreshFavorites() }

    private fun refreshFavorites() {
        val all = favorites.all()
        _ui.update { it.copy(favorites = all, favoriteKeys = all.map { t -> t.key }.toSet()) }
    }

    // ------------------------------------------------------------------ download / add to library

    private fun musicDir(): File =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: app.filesDir, "Navasa")

    fun cancel() { cancelDownload = true }

    private fun busy() = downloadJob?.isActive == true

    fun downloadSong(t: OnlineTrack) {
        if (busy()) return
        cancelDownload = false
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloadPercent = -1) }
            val result = withContext(Dispatchers.IO) {
                val failed = HashSet<String>()
                var outcome: DownloadResult? = null
                while (outcome !is DownloadResult.Ok) {
                    val v = repo.pickVersion(t, failed) ?: break
                    val dest = File(musicDir(), OnlineDownloader.safeFileName(t.artist, t.title, v.url))
                    outcome = downloader.download(v.url, dest, DownloadKind.AUDIO, OnlineDownloader.MAX_SONG_BYTES, { cancelDownload }, ::progress)
                    if (outcome is DownloadResult.Failed) { failed += v.url; repo.reportFailure(v); if (outcome.reason == "cancelled") break }
                }
                outcome
            }
            _ui.update { it.copy(downloadPercent = null) }
            when (result) {
                is DownloadResult.Ok -> { addToLibrary(listOf(result.file), t.title, t.artist); say(UiMessage(R.string.online_downloaded_one, listOf(t.display))) }
                is DownloadResult.Failed -> say(UiMessage(R.string.online_download_failed, listOf(result.reason)))
                null -> say(UiMessage(R.string.online_no_link))
            }
        }
    }

    /** [paths] = one song of the archive, null = the whole collection. */
    fun downloadFromArchive(c: OnlineCollection, paths: Set<String>?) {
        if (busy()) return
        cancelDownload = false
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloadPercent = -1) }
            val report = withContext(Dispatchers.IO) {
                when (val z = importer.fetchZip(c, { cancelDownload }, ::progress)) {
                    is ZipFetch.Failed -> z.reason to null
                    is ZipFetch.Ok -> null to importer.extract(z.zip, paths, File(musicDir(), c.collection.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "collection" }))
                }
            }
            _ui.update { it.copy(downloadPercent = null) }
            val (error, extracted) = report
            if (extracted == null) { say(UiMessage(R.string.online_download_failed, listOf(error ?: "?"))); return@launch }
            if (extracted.files.isNotEmpty()) addToLibrary(extracted.files)
            say(
                when {
                    extracted.files.isEmpty() -> UiMessage(R.string.online_download_failed, listOf(extracted.failed.firstOrNull() ?: "empty"))
                    extracted.failed.isNotEmpty() -> UiMessage(R.string.online_downloaded_partial, listOf(extracted.files.size, extracted.failed.size))
                    extracted.files.size == 1 -> UiMessage(R.string.online_downloaded_one, listOf(extracted.files[0].nameWithoutExtension))
                    else -> UiMessage(R.string.online_downloaded_many, listOf(extracted.files.size))
                },
            )
        }
    }

    /** Plays one song of an archive: the archive is downloaded first (cached for an hour), the song extracted, then played as a local file. */
    fun playFromArchive(c: OnlineCollection, entry: ArchiveTrack) {
        if (busy()) return
        cancelDownload = false
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(downloadPercent = -1) }
            val file = withContext(Dispatchers.IO) {
                val z = importer.fetchZip(c, { cancelDownload }, ::progress) as? ZipFetch.Ok ?: return@withContext null
                importer.extract(z.zip, setOf(entry.path), File(app.cacheDir, "zipplay")).files.firstOrNull()
            }
            _ui.update { it.copy(downloadPercent = null) }
            if (file == null) { say(UiMessage(R.string.online_download_failed, listOf("zip"))); return@launch }
            val meta = MediaMetadata.Builder().setTitle(file.nameWithoutExtension).setArtist(c.artist).setAlbumTitle(c.collection).setIsPlayable(true).build()
            controller.playItems(listOf(MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId("zip:" + file.name).setMediaMetadata(meta).build()), 0)
        }
    }

    private fun progress(done: Long, total: Long) {
        val p = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        if (p != _ui.value.downloadPercent) _ui.update { it.copy(downloadPercent = p) }
    }

    /**
     * Moves downloaded files into the public Music folder (so they are indexed, visible in the file manager and
     * deletable), then refreshes the Library. Files that cannot be published stay where they are and are indexed in place.
     */
    private suspend fun addToLibrary(files: List<File>, title: String? = null, artist: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val root = musicDir()
                val single = files.size == 1
                val unpublished = files.filter {
                    PublicMusicStore.publish(app, it, root, if (single) title else null, if (single) artist else null) == null
                }
                if (unpublished.isNotEmpty()) {
                    MediaScannerConnection.scanFile(app, unpublished.map { it.absolutePath }.toTypedArray(), null, null)
                }
                delay(800) // let MediaStore index the new files before the library re-reads it
                app.repository.rescanLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the files exist; the next library scan will pick them up
            }
        }
    }

    private fun say(m: UiMessage) { _ui.update { it.copy(message = m) } }
    fun messageShown() { _ui.update { it.copy(message = null) } }

    override fun onCleared() {
        controller.release()
        runCatching { File(app.cacheDir, "zipplay").listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }?.forEach { it.delete() } }
        super.onCleared()
    }
}
