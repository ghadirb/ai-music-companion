package com.ghadirb.aimusic.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.data.local.entity.UserPreferenceEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.premium.Entitlement
import com.ghadirb.aimusic.premium.PremiumFeature
import com.ghadirb.aimusic.recommendation.ListCodec
import com.ghadirb.aimusic.stats.StatsCalculator
import com.ghadirb.aimusic.stats.StatsSummary
import com.ghadirb.aimusic.ui.premium.LocalPremiumAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FREE_FLOW = MutableStateFlow(Entitlement.FREE)

enum class StatsRange(val label: String, val days: Int?) { WEEK("۷ روز", 7), MONTH("۳۰ روز", 30), ALL("همه", null) }

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Ready(val summary: StatsSummary, val profile: UserPreferenceEntity?, val range: StatsRange) : StatsUiState
    data object Error : StatsUiState
}

class StatisticsViewModel(private val repository: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val state: StateFlow<StatsUiState> = _state.asStateFlow()
    private var range = StatsRange.MONTH

    init { load() }

    fun setRange(value: StatsRange) { range = value; load() }

    private fun load() {
        viewModelScope.launch {
            _state.value = StatsUiState.Loading
            _state.value = try {
                val tracks = repository.observeTracks().first()
                val history = repository.recentHistory(20_000)
                val profile = repository.getUserPreference()
                val now = System.currentTimeMillis()
                val since = range.days?.let { now - it * 86_400_000L } ?: 0L
                val summary = withContext(Dispatchers.Default) { StatsCalculator.compute(tracks, history, now, since) }
                StatsUiState.Ready(summary, profile, range)
            } catch (e: Exception) {
                StatsUiState.Error
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(repository: MusicRepository) {
    val viewModel: StatisticsViewModel = viewModel(factory = viewModelFactory { initializer { StatisticsViewModel(repository) } })
    val state by viewModel.state.collectAsState()
    val access = LocalPremiumAccess.current
    val plan by (access?.entitlement ?: FREE_FLOW).collectAsState() // subscribes: UI updates when the plan changes
    val advanced = plan.let { access?.isAllowed(PremiumFeature.ADVANCED_STATISTICS) } == true
    val insights = plan.let { access?.isAllowed(PremiumFeature.ADVANCED_INSIGHTS) } == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("آمار شنیدن", style = MaterialTheme.typography.headlineSmall)
        Text("همهٔ آمار فقط روی همین دستگاه از تاریخچهٔ شنیدن شما ساخته می‌شود.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))

        when (val current = state) {
            StatsUiState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            StatsUiState.Error -> Text("خواندن آمار انجام نشد. دوباره تلاش کنید.", color = MaterialTheme.colorScheme.error)
            is StatsUiState.Ready -> {
                Row(Modifier.padding(bottom = 12.dp)) {
                    StatsRange.values().forEach { r ->
                        FilterChip(selected = current.range == r, onClick = { viewModel.setRange(r) }, label = { Text(r.label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                val s = current.summary
                if (s.isEmpty) {
                    Text("هنوز داده‌ای برای این بازه نیست. چند آهنگ گوش کنید تا آمار ساخته شود.", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                StatRow("آهنگ‌های پخش‌شده", "${s.completedPlays} بار (${s.uniqueTracks} آهنگ متفاوت)")
                StatRow("زمان گوش دادن", formatDuration(s.listenMs))
                StatRow("میزان رد کردن", "${(s.skipRate * 100).toInt()}٪")

                SectionTitle("پرشنیده‌ترین آهنگ‌ها")
                s.topTracks.forEachIndexed { i, r -> Text("${i + 1}. ${r.track.title} — ${r.plays} بار", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                SectionTitle("پرشنیده‌ترین خواننده‌ها")
                s.topArtists.forEachIndexed { i, r -> Text("${i + 1}. ${r.name} — ${r.plays} بار", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }

                if (advanced) {
                    SectionTitle("سبک‌های محبوب")
                    s.topGenres.forEach { Text("• ${it.name} — ${it.plays}", style = MaterialTheme.typography.bodyMedium) }
                    if (s.topGenres.isEmpty()) Text("سبکی در فایل‌ها ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
                    SectionTitle("ساعت‌های گوش دادن")
                    Bars(s.playsByHour, labels = List(24) { if (it % 6 == 0) "$it" else "" })
                    SectionTitle("روزهای هفته")
                    Bars(s.playsByWeekday, labels = listOf("ی", "د", "س", "چ", "پ", "ج", "ش"))
                    SectionTitle("روند ۱۴ روز اخیر (دقیقه)")
                    Bars(s.dailyMinutes, labels = List(14) { "" })
                    if (s.moodCounts.isNotEmpty()) {
                        SectionTitle("حال‌وهوای آهنگ‌ها")
                        s.moodCounts.entries.sortedByDescending { it.value }.forEach { Text("• ${moodLabel(it.key)} — ${it.value}", style = MaterialTheme.typography.bodyMedium) }
                    }
                } else {
                    LockedCard("آمار پیشرفته", "سبک‌ها، ساعت‌ها و روزهای هفته، روند شنیدن و حال‌وهوا") {
                        access?.require(PremiumFeature.ADVANCED_STATISTICS) {}
                    }
                }

                if (insights) {
                    SectionTitle("بینش‌های شنیداری")
                    val p = current.profile
                    if (p == null || p.updatedAt == 0L) {
                        Text("پروفایل سلیقه هنوز ساخته نشده است؛ بعد از چند روز استفاده کامل می‌شود.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (p.preferredBpm > 0) StatRow("ضرب رایج (BPM)", "${p.preferredBpm}")
                        if (p.energyRange.isNotBlank()) StatRow("محدودهٔ انرژی", p.energyRange)
                        if (p.peakHours.isNotBlank()) StatRow("ساعت‌های اوج", p.peakHours.split(',').joinToString("، ") { "$it:00" })
                        StatRow("سهم علاقه‌مندی‌ها از کتابخانه", "${(p.favoriteRatio * 100).toInt()}٪")
                        val moods = ListCodec.decode(p.favoriteMoods).map(::moodLabel)
                        if (moods.isNotEmpty()) StatRow("حال‌وهوای محبوب", moods.joinToString("، "))
                    }
                } else {
                    LockedCard("بینش‌های شنیداری", "BPM، محدودهٔ انرژی و ساعت‌های اوج سلیقهٔ شما") {
                        access?.require(PremiumFeature.ADVANCED_INSIGHTS) {}
                    }
                }
            }
        }
    }
}

private fun moodLabel(mood: String) = when (mood) {
    "calm" -> "آرام"; "energetic" -> "پرانرژی"; "happy" -> "شاد"; "sad" -> "غمگین"; "neutral" -> "خنثی"; else -> mood
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} ساعت و ${minutes % 60} دقیقه" else "$minutes دقیقه"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Bars(values: List<Int>, labels: List<String>) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight(v.toFloat() / max * 0.95f + 0.02f)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (v == 0) 0.15f else 0.85f))
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun LockedCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 20.dp), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = "ویژهٔ پرمیوم")
            Column(Modifier.padding(start = 12.dp)) {
                Text("$title (پرمیوم)", style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
