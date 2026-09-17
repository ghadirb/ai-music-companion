package com.ghadirb.aimusic.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.recommendation.RecommendationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: MusicRepository) : ViewModel() {

    private val recommendationEngine = RecommendationEngine(repository)

    private val _todaysPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val todaysPicks: StateFlow<List<TrackEntity>> = _todaysPicks.asStateFlow()

    private val _rediscoverPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val rediscoverPicks: StateFlow<List<TrackEntity>> = _rediscoverPicks.asStateFlow()

    private val _hasLibrary = MutableStateFlow(true)
    val hasLibrary: StateFlow<Boolean> = _hasLibrary.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _hasLibrary.value = repository.trackCount() > 0
            _todaysPicks.value = recommendationEngine.topRecommendations(limit = 6)
            _rediscoverPicks.value = repository.rediscoverTracks(limit = 6)
        }
    }
}
