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

    private val _nightPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val nightPicks: StateFlow<List<TrackEntity>> = _nightPicks.asStateFlow()

    private val _drivingPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val drivingPicks: StateFlow<List<TrackEntity>> = _drivingPicks.asStateFlow()

    private val _focusPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val focusPicks: StateFlow<List<TrackEntity>> = _focusPicks.asStateFlow()

    private val _workoutPicks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val workoutPicks: StateFlow<List<TrackEntity>> = _workoutPicks.asStateFlow()

    // Unknown until the first trackCount() check completes — NOT "true", otherwise the
    // screen briefly renders the full card layout on the very first frame and then
    // flashes to the empty-library message once the real (often 0) count comes back.
    private val _hasLibrary = MutableStateFlow<Boolean?>(null)
    val hasLibrary: StateFlow<Boolean?> = _hasLibrary.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val count = repository.trackCount()
            _todaysPicks.value = recommendationEngine.topRecommendations(limit = 6)
            _rediscoverPicks.value = repository.rediscoverTracks(limit = 6)
            _nightPicks.value = repository.nightSuitableTracks(limit = 6)
            _drivingPicks.value = repository.drivingSuitableTracks(limit = 6)
            _focusPicks.value = repository.focusSuitableTracks(limit = 6)
            _workoutPicks.value = repository.workoutSuitableTracks(limit = 6)
            _hasLibrary.value = count > 0
        }
    }
}
