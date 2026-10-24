package com.ghadirb.aimusic.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.data.local.entity.VideoPlaylistEntryEntity
import com.ghadirb.aimusic.data.repository.VideoPlaylistRepository
import com.ghadirb.aimusic.data.repository.VideoRepository
import com.ghadirb.aimusic.video.ResolvedVideoPlaylist
import com.ghadirb.aimusic.video.VideoPlaylistLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoPlaylistUiState(
    val playlists: List<ResolvedVideoPlaylist> = emptyList(),
    val entries: List<VideoPlaylistEntryEntity> = emptyList(),
    /** Id of the opened playlist; null shows the list of playlists. */
    val openId: Long? = null
) {
    val open: ResolvedVideoPlaylist? get() = playlists.firstOrNull { it.playlist.id == openId }
    fun playlistIdsOf(videoId: Long): Set<Long> = entries.filter { it.videoId == videoId }.mapTo(HashSet()) { it.playlistId }
}

class VideoPlaylistViewModel(
    private val playlists: VideoPlaylistRepository,
    videos: VideoRepository
) : ViewModel() {

    private val openId = MutableStateFlow<Long?>(null)

    val ui: StateFlow<VideoPlaylistUiState> = combine(
        playlists.observePlaylists(), playlists.observeEntries(), videos.observeVideos(), openId
    ) { pls, entries, vids, open ->
        val resolved = VideoPlaylistLogic.resolve(pls, entries, vids)
        VideoPlaylistUiState(resolved, entries, open.takeIf { id -> resolved.any { it.playlist.id == id } })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoPlaylistUiState())

    fun open(id: Long) = openId.update { id }
    fun close() = openId.update { null }

    fun create(name: String, thenAdd: List<Long> = emptyList()) {
        viewModelScope.launch {
            val id = playlists.create(name)
            if (id > 0 && thenAdd.isNotEmpty()) playlists.add(id, thenAdd)
        }
    }

    fun rename(id: Long, name: String) { viewModelScope.launch { playlists.rename(id, name) } }
    fun delete(id: Long) { viewModelScope.launch { playlists.delete(id) } }
    fun add(playlistId: Long, videoIds: List<Long>) { viewModelScope.launch { playlists.add(playlistId, videoIds) } }
    fun remove(playlistId: Long, videoId: Long) { viewModelScope.launch { playlists.remove(playlistId, listOf(videoId)) } }

    fun move(playlistId: Long, from: Int, to: Int) {
        val current = ui.value.playlists.firstOrNull { it.playlist.id == playlistId } ?: return
        val reordered = VideoPlaylistLogic.move(current.videos.map { it.id }, from, to)
        if (reordered == current.videos.map { it.id }) return
        viewModelScope.launch { playlists.reorder(playlistId, reordered) }
    }
}
