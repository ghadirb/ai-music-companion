package com.ghadirb.aimusic.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.data.repository.VideoRepository
import com.ghadirb.aimusic.video.VideoFilter
import com.ghadirb.aimusic.video.VideoLibraryQuery
import com.ghadirb.aimusic.video.VideoSort
import com.ghadirb.aimusic.video.VideoThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoUiState(
    /** Already searched / filtered / sorted, ready to display. */
    val videos: List<VideoEntity> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: VideoSort = VideoSort.NEWEST,
    val filter: VideoFilter = VideoFilter.ALL,
    val grid: Boolean = true,
    /** True until the first MediaStore sync has finished (or failed). */
    val syncing: Boolean = true,
    /** The last sync could not run (e.g. permission revoked). */
    val syncFailed: Boolean = false
)

class VideoViewModel(private val repository: VideoRepository) : ViewModel() {

    private data class Controls(
        val query: String = "",
        val sort: VideoSort = VideoSort.NEWEST,
        val filter: VideoFilter = VideoFilter.ALL,
        val grid: Boolean = true,
        val syncing: Boolean = true,
        val syncFailed: Boolean = false
    )

    private val controls = MutableStateFlow(Controls())

    val uiState: StateFlow<VideoUiState> = combine(repository.observeVideos(), controls) { all, c ->
        VideoUiState(
            videos = VideoLibraryQuery.apply(all, c.query, c.sort, c.filter),
            totalCount = all.size,
            query = c.query, sort = c.sort, filter = c.filter, grid = c.grid,
            syncing = c.syncing, syncFailed = c.syncFailed
        )
    }.flowOn(Dispatchers.Default) // sorting / searching thousands of rows stays off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoUiState())

    fun setQuery(value: String) = controls.update { it.copy(query = value) }
    fun setSort(value: VideoSort) = controls.update { it.copy(sort = value) }
    fun setFilter(value: VideoFilter) = controls.update { it.copy(filter = value) }
    fun toggleGrid() = controls.update { it.copy(grid = !it.grid) }

    /** Re-syncs with MediaStore (off the main thread inside the repository/scanner). */
    fun refresh() {
        viewModelScope.launch {
            controls.update { it.copy(syncing = true) }
            val ok = repository.sync()
            controls.update { it.copy(syncing = false, syncFailed = !ok) }
        }
    }

    /** The system confirmed that these videos were deleted from the device. */
    fun onDeleted(videos: List<VideoEntity>) {
        viewModelScope.launch {
            repository.remove(videos.map { it.id })
            videos.forEach { VideoThumbnails.evict(it.id) }
        }
    }
}
