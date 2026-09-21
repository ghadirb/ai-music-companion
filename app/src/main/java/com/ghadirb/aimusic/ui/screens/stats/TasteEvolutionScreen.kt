package com.ghadirb.aimusic.ui.screens.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.stats.EvolutionResult
import com.ghadirb.aimusic.stats.InsightPeriods
import com.ghadirb.aimusic.stats.InsightRange
import com.ghadirb.aimusic.stats.TasteEvolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface EvolutionUiState {
    data object Loading : EvolutionUiState
    data class Ready(val result: EvolutionResult, val range: InsightRange, val currentLabel: String, val previousLabel: String?) : EvolutionUiState
    data object Error : EvolutionUiState
}

class TasteEvolutionViewModel(private val repository: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow<EvolutionUiState>(EvolutionUiState.Loading)
    val state: StateFlow<EvolutionUiState> = _state.asStateFlow()
    private var range = InsightRange.WEEK

    init { load() }
    fun setRange(value: InsightRange) { range = value; load() }

    private fun load() {
        viewModelScope.launch {
            _state.value = EvolutionUiState.Loading
            _state.value = try {
                val tracks = repository.observeTracks().first()
                val history = repository.recentHistory(20_000)
                val now = System.currentTimeMillis()
                val zone = TimeZone.getDefault()
                val current = InsightPeriods.current(range, now, zone, persian = true)
                val previous = InsightPeriods.previous(range, now, zone, persian = true)
                val result = withContext(Dispatchers.Default) { TasteEvolution.compute(tracks, history, current, previous) }
                EvolutionUiState.Ready(result, range, periodLabel(range, current, zone), previous?.let { periodLabel(range, it, zone) })
            } catch (e: Exception) {
                EvolutionUiState.Error
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteEvolutionScreen(repository: MusicRepository) {
    val viewModel: TasteEvolutionViewModel = viewModel(factory = viewModelFactory { initializer { TasteEvolutionViewModel(repository) } })
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("تکامل سلیقهٔ موسیقی", style = MaterialTheme.typography.headlineSmall)
        Text("مقایسهٔ شنیدن این دوره با دورهٔ قبل. فقط از تاریخچهٔ همین دستگاه ساخته می‌شود و هیچ داده‌ای ارسال نمی‌شود.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))

        when (val s = state) {
            EvolutionUiState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            EvolutionUiState.Error -> Text("خواندن داده‌ها انجام نشد. دوباره تلاش کنید.", color = MaterialTheme.colorScheme.error)
            is EvolutionUiState.Ready -> {
                Row(Modifier.padding(bottom = 8.dp)) {
                    listOf(InsightRange.WEEK to "هفته", InsightRange.MONTH to "ماه").forEach { (r, label) ->
                        FilterChip(selected = s.range == r, onClick = { viewModel.setRange(r) }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text(s.currentLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val r = s.result
                val unit = if (s.range == InsightRange.WEEK) "هفتهٔ قبل" else "ماه قبل"
                if (!r.enoughData) {
                    Text(
                        "برای نمایش تحلیل دقیق، هنوز داده کافی ندارید.",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        "در این دوره ${r.current.completedPlays} پخش کامل ثبت شده؛ حداقل ${TasteEvolution.MIN_PLAYS} پخش لازم است.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)
                    )
                    return@Column
                }
                val c = r.current
                SectionTitle("خلاصهٔ این دوره")
                c.topGenre?.let { StatRow("بیشترین شنیدن", it) }
                c.dominantMood?.let { StatRow("حال‌وهوای غالب", moodLabel(it)) }
                StatRow("زمان گوش دادن", formatDuration(c.listenMs) + (r.listenTimeChangePercent?.let { "  (${signed(it)}٪ نسبت به $unit)" } ?: ""))
                StatRow("خواننده‌های جدید", "${c.newArtists.size}")
                if (c.newArtists.isNotEmpty()) Text(c.newArtists.take(5).joinToString("، "), style = MaterialTheme.typography.bodySmall)
                StatRow("آهنگ‌های تازه‌کشف‌شده", "${c.discoveredTracks}")
                StatRow("میزان رد کردن", "${(c.skipRate * 100).roundToInt()}٪" + (r.skipRateChange?.let { "  (${signed((it * 100).roundToInt())} واحد)" } ?: ""))
                c.avgBpm?.let { StatRow("میانگین BPM", "$it" + (r.bpmChange?.let { d -> "  (${signed(d)})" } ?: "")) }

                if (!r.comparable) {
                    Text("برای مقایسه با $unit داده کافی نبود، پس مقایسه‌ای نمایش داده نمی‌شود.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                } else {
                    if (r.genreChanges.isNotEmpty()) {
                        SectionTitle("تغییر سبک‌ها")
                        r.genreChanges.forEach { g ->
                            Text("${g.genre}: $unit ${pct(g.previousShare)} ← این دوره ${pct(g.currentShare)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    r.energyChange?.takeIf { abs(it) >= 0.05f }?.let {
                        Text(if (it > 0) "انرژی آهنگ‌هایتان نسبت به $unit بیشتر شده است." else "آهنگ‌هایتان نسبت به $unit آرام‌تر شده‌اند.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

private fun pct(share: Float) = "${(share * 100).roundToInt()}٪"
private fun signed(n: Int) = if (n > 0) "+$n" else "$n"
