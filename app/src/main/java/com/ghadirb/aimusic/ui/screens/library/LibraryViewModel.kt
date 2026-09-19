package com.ghadirb.aimusic.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.library.LibraryFilter
import com.ghadirb.aimusic.library.LibraryQuery
import com.ghadirb.aimusic.library.LibrarySort
import com.ghadirb.aimusic.library.TrackStat
import com.ghadirb.aimusic.search.SearchIndex
import com.ghadirb.aimusic.search.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Ready(
        val tracks: List<TrackEntity>,
        val search: SearchResults?,
        val genres: List<String>,
        val moods: List<String>,
        val totalCount: Int,
        val sort: LibrarySort,
        val filter: LibraryFilter
    ) : LibraryUiState
}

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val repository: MusicRepository,
    private val onLibraryChanged: () -> Unit = {}
) : ViewModel() {

    private class Data(
        val tracks: List<TrackEntity>,
        val stats: Map<Long, TrackStat>,
        val index: SearchIndex,
        val genres: List<String>,
        val moods: List<String>
    )

    private class Controls(val query: String, val sort: LibrarySort, val filter: LibraryFilter)

    private val queryFlow = MutableStateFlow("")
    private val sortFlow = MutableStateFlow(LibrarySort.TITLE)
    private val filterFlow = MutableStateFlow(LibraryFilter())

    private val dataFlow = combine(
        repository.observeTracks(),
        repository.observeTrackStats(),
        repository.observePlaylists()
    ) { tracks, stats, playlists ->
        Data(
            tracks = tracks,
            stats = stats.associateBy { it.trackId },
            index = SearchIndex(tracks, playlists),
            genres = LibraryQuery.availableGenres(tracks),
            moods = LibraryQuery.availableMoods(tracks)
        )
    }

    private val controlsFlow = combine(
        queryFlow.debounce { if (it.isEmpty()) 0L else 150L },
        sortFlow,
        filterFlow
    ) { query, sort, filter -> Controls(query, sort, filter) }

    /** Heavy work (indexing, sorting, searching) runs on Dispatchers.Default, never on the main thread. */
    val uiState: StateFlow<LibraryUiState> = combine(dataFlow, controlsFlow) { data, controls ->
        if (data.tracks.isEmpty()) {
            LibraryUiState.Empty
        } else {
            val search = if (controls.query.isBlank()) null else {
                val raw = data.index.search(controls.query)
                raw.copy(tracks = raw.tracks.filter(controls.filter::accepts))
            }
            LibraryUiState.Ready(
                tracks = LibraryQuery.apply(data.tracks, data.stats, controls.sort, controls.filter),
                search = search,
                genres = data.genres,
                moods = data.moods,
                totalCount = data.tracks.size,
                sort = controls.sort,
                filter = controls.filter
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState.Loading)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    fun setQuery(value: String) { queryFlow.value = value }
    fun setSort(value: LibrarySort) { sortFlow.value = value }
    fun setFilter(value: LibraryFilter) { filterFlow.value = value }
    fun dismissScanError() { _scanError.value = null }

    fun scanLibrary() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanError.value = null
            try {
                repository.rescanLibrary()
                // New tracks have no energy/mood yet — kick the (unique, cancellable) analyzer.
                onLibraryChanged()
            } catch (e: SecurityException) {
                _scanError.value = "دسترسی به فایل‌های صوتی داده نشده است. مجوز را در تنظیمات دستگاه فعال کنید."
            } catch (e: Exception) {
                _scanError.value = "اسکن کتابخانه کامل نشد. دوباره تلاش کنید."
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch { repository.setFavorite(track.id, !track.isFavorite) }
    }
}
