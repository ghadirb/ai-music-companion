package com.ghadirb.aimusic.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: MusicRepository,
    private val workManager: WorkManager? = null
) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> =
        repository.observeTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    fun scanLibrary() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                repository.rescanLibrary()
                // Newly-found tracks have no energy/mood yet — kick the analyzer
                // so Home's "night"/"driving" cards pick them up soon, not just
                // on the next app start or daily periodic run.
                workManager?.enqueue(
                    OneTimeWorkRequestBuilder<AudioAnalysisWorker>()
                        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .build()
                )
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun toggleFavorite(track: TrackEntity) {
        viewModelScope.launch {
            repository.setFavorite(track.id, !track.isFavorite)
        }
    }
}
