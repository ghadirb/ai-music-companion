package com.ghadirb.aimusic.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.ghadirb.aimusic.stats.InsightPeriods
import com.ghadirb.aimusic.stats.InsightRange
import com.ghadirb.aimusic.stats.JalaliCalendar
import com.ghadirb.aimusic.stats.PeriodBounds
import com.ghadirb.aimusic.stats.PeriodComparison
import com.ghadirb.aimusic.stats.StatsInsights
import com.ghadirb.aimusic.stats.Streak
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
import java.util.TimeZone

private val FREE_FLOW = MutableStateFlow(Entitlement.FREE)

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Ready(
        val summary: StatsSummary,
        val profile: UserPreferenceEntity?,
        val range: InsightRange,
        val periodLabel: String,
        val comparison: PeriodComparison? = null,
        val streak: Streak = Streak(0, 0, 0),
        val nowMs: Long = 0L
    ) : StatsUiState
    data object Error : StatsUiState
}

/** Jalali label for a period, e.g. "مهر ۱۴۰۵" or "۱ مهر تا ۷ مهر ۱۴۰۵". */
fun periodLabel(range: InsightRange, bounds: PeriodBounds, zone: TimeZone = TimeZone.getDefault()): String {
    fun j(t: Long) = JalaliCalendar.jalaliOf(t, zone)
    val start = j(bounds.startMs)
    return when (range) {
        InsightRange.ALL -> "از ابتدا"
        InsightRange.TODAY -> "${start[2]} ${JalaliCalendar.monthName(start[1])} ${start[0]}"
        InsightRange.MONTH -> "${JalaliCalendar.monthName(start[1])} ${start[0]}"
        InsightRange.YEAR -> "سال ${start[0]}"
        InsightRange.WEEK -> {
            val end = j(bounds.endMs - 1)
            "${start[2]} ${JalaliCalendar.monthName(start[1])} تا ${end[2]} ${JalaliCalendar.monthName(end[1])} ${end[0]}"
        }
    }
}

class StatisticsViewModel(private val repository: MusicRepository) : ViewModel() {
    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val state: StateFlow<StatsUiState> = _state.asStateFlow()
    private var range = InsightRange.MONTH

    init { load() }

    fun setRange(value: InsightRange) { range = value; load() }

    private fun load() {
        viewModelScope.launch {
            _state.value = StatsUiState.Loading
            _state.value = try {
                val tracks = repository.observeTracks().first()
                val history = repository.recentHistory(20_000)
                val profile = repository.getUserPreference()
                val now = System.currentTimeMillis()
                val zone = TimeZone.getDefault()
                val bounds = InsightPeriods.current(range, now, zone, persian = true)
                val previousBounds = InsightPeriods.previous(range, now, zone, persian = true)
                val computed = withContext(Dispatchers.Default) {
                    val current = StatsCalculator.compute(tracks, history, now, sinceMs = bounds.startMs, untilMs = bounds.endMs, zone = zone)
                    val comparison = previousBounds?.let { pb ->
                        StatsInsights.compare(current, StatsCalculator.compute(tracks, history, now, sinceMs = pb.startMs, untilMs = pb.endMs, zone = zone))
                    }
                    val known = tracks.mapTo(HashSet()) { it.id }
                    Triple(current, comparison, StatsInsights.streak(history.filter { it.trackId in known }.map { it.startTime }, now, zone))
                }
                StatsUiState.Ready(computed.first, profile, range, periodLabel(range, bounds, zone), computed.second, computed.third, now)
            } catch (e: Exception) {
                StatsUiState.Error
            }
        }
    }
}

private val WEEKDAYS = listOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")

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
        Text("همهٔ آمار فقط روی همین دستگاه از تاریخچهٔ شنیدن شما ساخته می‌شود؛ هفته از شنبه شروع می‌شود و ماه و سال شمسی است.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))

        when (val current = state) {
            StatsUiState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            StatsUiState.Error -> Text("خواندن آمار انجام نشد. دوباره تلاش کنید.", color = MaterialTheme.colorScheme.error)
            is StatsUiState.Ready -> {
                LazyRow(Modifier.padding(bottom = 4.dp)) {
                    items(InsightRange.values().toList(), key = { it.name }) { r ->
                        FilterChip(selected = current.range == r, onClick = { viewModel.setRange(r) }, label = { Text(r.labelFa) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text(current.periodLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                val s = current.summary
                if (s.isEmpty) {
                    Text("برای این بازه هنوز داده‌ای نیست. چند آهنگ گوش کنید تا آمار ساخته شود.", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                val previousLabel = StatsInsights.previousLabel(current.range)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroCard(
                        "زمان گوش دادن", StatsInsights.duration(s.listenMs),
                        current.comparison?.let { StatsInsights.deltaText(it.listenTime, previousLabel) },
                        Modifier.weight(1f)
                    )
                    HeroCard(
                        "پخش‌ها", "${s.completedPlays}",
                        current.comparison?.let { StatsInsights.deltaText(it.plays, previousLabel) },
                        Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroCard(
                        "روزهای پیاپی 🔥", "${current.streak.current}",
                        if (current.streak.best > 0) "بیشترین: ${current.streak.best} روز" else null,
                        Modifier.weight(1f)
                    )
                    HeroCard(
                        "آهنگ‌های متفاوت", "${s.uniqueTracks}",
                        current.comparison?.let { StatsInsights.deltaText(it.uniqueTracks, previousLabel) },
                        Modifier.weight(1f)
                    )
                }
                val context = LocalContext.current
                TextButton(onClick = {
                    val text = StatsInsights.recapText(current.periodLabel, s, current.streak)
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
                            "اشتراک‌گذاری خلاصه"
                        )
                    )
                }) { Text("اشتراک‌گذاری خلاصه") }

                SectionTitle("روند ۱۴ روز اخیر (دقیقه)")
                Bars(
                    s.dailyMinutes,
                    labels = StatsInsights.lastDaysInitials(current.nowMs, TimeZone.getDefault(), s.dailyMinutes.size),
                    caption = s.dailyMinutes.maxOrNull()?.takeIf { it > 0 }?.let { "بیشترین: $it دقیقه" }
                )
                StatRow("میزان رد کردن", "${(s.skipRate * 100).toInt()}٪")
                StatRow("علاقه‌مندی‌ها در کتابخانه", "${s.favoriteCount}")

                SectionTitle("پرشنیده‌ترین آهنگ‌ها")
                s.topTracks.forEachIndexed { i, r -> Text("${i + 1}. ${r.track.title} — ${r.plays} بار", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                SectionTitle("پرشنیده‌ترین خواننده‌ها")
                s.topArtists.forEachIndexed { i, r -> Text("${i + 1}. ${r.name} — ${r.plays} بار", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }

                if (advanced) {
                    if (s.topAlbums.isNotEmpty()) {
                        SectionTitle("پرشنیده‌ترین آلبوم‌ها")
                        s.topAlbums.forEachIndexed { i, r -> Text("${i + 1}. ${r.name} — ${r.plays} بار", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                    SectionTitle("سبک‌های محبوب")
                    s.topGenres.forEach { Text("• ${it.name} — ${it.plays}", style = MaterialTheme.typography.bodyMedium) }
                    if (s.topGenres.isEmpty()) Text("سبکی در فایل‌ها ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
                    SectionTitle("عادت‌های شنیدن")
                    s.peakHour?.let { StatRow("پرشنیده‌ترین ساعت", "$it تا ${(it + 1) % 24}") }
                    s.peakWeekday?.let { StatRow("پرشنیده‌ترین روز", WEEKDAYS[it]) }
                    StatRow("آهنگ‌های تازه‌کشف‌شده", "${s.discoveredTracks} (${(s.discoveryRate * 100).toInt()}٪ از آهنگ‌های شنیده‌شده)")
                    SectionTitle("ساعت‌های گوش دادن")
                    Bars(s.playsByHour, labels = List(24) { if (it % 6 == 0) "$it" else "" }, caption = s.peakHour?.let { "اوج: ساعت $it" })
                    SectionTitle("روزهای هفته (از یکشنبه)")
                    Bars(s.playsByWeekday, labels = listOf("ی", "د", "س", "چ", "پ", "ج", "ش"), caption = s.peakWeekday?.let { "اوج: ${WEEKDAYS[it]}" })
                    if (s.topSkipped.isNotEmpty()) {
                        SectionTitle("بیشترین ردشده‌ها")
                        s.topSkipped.forEach { Text("• ${it.track.title} — ${it.plays} بار", style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (s.moodCounts.isNotEmpty()) {
                        SectionTitle("حال‌وهوای آهنگ‌ها")
                        s.moodCounts.entries.sortedByDescending { it.value }.forEach { Text("• ${moodLabel(it.key)} — ${it.value}", style = MaterialTheme.typography.bodyMedium) }
                    }
                } else {
                    LockedCard("آمار پیشرفته", "آلبوم‌ها، سبک‌ها، ساعت و روز اوج، کشف موسیقی، ردشده‌ها و روند شنیدن") {
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

internal fun moodLabel(mood: String) = when (mood) {
    "calm" -> "آرام"; "energetic" -> "پرانرژی"; "happy" -> "شاد"; "sad" -> "غمگین"; "neutral" -> "خنثی"; else -> mood
}

internal fun formatDuration(ms: Long): String = StatsInsights.duration(ms)

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
internal fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Bars(values: List<Int>, labels: List<String>, caption: String? = null) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val peak = values.indexOf(values.maxOrNull() ?: 0).takeIf { (values.maxOrNull() ?: 0) > 0 }
    caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp)) }
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Box(
                Modifier.weight(1f).fillMaxHeight(v.toFloat() / max * 0.95f + 0.02f)
                    .background(
                        if (i == peak) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary.copy(alpha = if (v == 0) 0.15f else 0.85f)
                    )
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun HeroCard(title: String, value: String, note: String?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp))
            note?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = if (it.startsWith("▼")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LockedCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = "ویژهٔ پرمیوم")
            Column(Modifier.padding(start = 12.dp)) {
                Text("$title (پرمیوم)", style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
