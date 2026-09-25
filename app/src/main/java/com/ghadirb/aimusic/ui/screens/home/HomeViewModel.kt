package com.ghadirb.aimusic.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.mix.SmartMix
import com.ghadirb.aimusic.mix.SmartMixGenerator
import com.ghadirb.aimusic.recommendation.Recommendation
import com.ghadirb.aimusic.recommendation.RecommendationEngine
import com.ghadirb.aimusic.recommendation.ScoringConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val repository: MusicRepository,
    private val configProvider: () -> ScoringConfig = { ScoringConfig() }
) : ViewModel() {

    private var lastCount = 0

    private val _picks = MutableStateFlow<List<Recommendation>>(emptyList())
    /** "Today's picks", each with a short reason ("because you listen to this artist a lot"). */
    val picks: StateFlow<List<Recommendation>> = _picks.asStateFlow()

    private val _mixes = MutableStateFlow<List<SmartMix>>(emptyList())
    val mixes: StateFlow<List<SmartMix>> = _mixes.asStateFlow()

    // Unknown until the first load completes — NOT "true", otherwise the screen flashes the full
    // layout and then collapses to the empty-library message.
    private val _hasLibrary = MutableStateFlow<Boolean?>(null)
    val hasLibrary: StateFlow<Boolean?> = _hasLibrary.asStateFlow()

    private val _isReanalyzing = MutableStateFlow(false)
    val isReanalyzing: StateFlow<Boolean> = _isReanalyzing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** (analysed, total) tracks — drives the progress card. */
    val analysisProgress: StateFlow<Pair<Int, Int>> = repository.observeAnalysisProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    init {
        viewModelScope.launch {
            // New tracks or new listening history => recompute (debounced so a big scan doesn't thrash).
            combine(repository.observeTracks(), repository.observeTrackStats()) { tracks, _ -> tracks.size }
                .debounce(400)
                .collect { count -> refresh(count) }
        }
    }

    /** Re-computes with the current tuning (called when returning to Home). */
    fun reload() { viewModelScope.launch { refresh(lastCount) } }

    private suspend fun refresh(count: Int) {
        lastCount = count
        try {
            val config = configProvider()
            val engine = RecommendationEngine(repository, config)
            if (count == 0) {
                _picks.value = emptyList()
                _mixes.value = emptyList()
            } else {
                val tracks = repository.observeTracks().first()
                val history = repository.recentHistory(3000)
                val now = System.currentTimeMillis()
                _picks.value = engine.recommend(limit = 8, nowMs = now)
                _mixes.value = withContext(Dispatchers.Default) { SmartMixGenerator.generateAll(tracks, history, now, limit = 30, config = config) }
            }
        } catch (e: Exception) {
            // Recommendations are a nice-to-have: never crash the Home screen because of them.
            _picks.value = emptyList()
            _mixes.value = emptyList()
        } finally {
            _hasLibrary.value = count > 0
        }
    }

    fun saveMixAsPlaylist(mix: SmartMix) {
        viewModelScope.launch {
            try {
                repository.createPlaylistWithTracks(mix.type.titleFa, mix.tracks.map { it.track.id })
                _message.value = "پلی‌لیست «${mix.type.titleFa}» ذخیره شد."
            } catch (e: Exception) {
                _message.value = "ذخیرهٔ پلی‌لیست انجام نشد."
            }
        }
    }

    fun dismissMessage() { _message.value = null }
    fun showMessage(text: String) { _message.value = text }

    /**
     * Spec §18: the new-user empty state's CTA does a real library rescan (never a fake/no-op
     * action). [hasLibrary] flips to true automatically once [refresh] observes new tracks.
     */
    fun scanLibraryNow() {
        viewModelScope.launch {
            _isReanalyzing.value = true
            try {
                repository.rescanLibrary()
            } catch (e: Exception) {
                _message.value = "اسکن کتابخانه انجام نشد. دوباره تلاش کنید."
            } finally {
                _isReanalyzing.value = false
            }
        }
    }

    fun reanalyzeLibrary() {
        viewModelScope.launch {
            _isReanalyzing.value = true
            try {
                repository.reanalyzeLibrary()
            } finally {
                _isReanalyzing.value = false
            }
        }
    }
}
