package com.ghadirb.aimusic.ui.screens.smartplaylist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.cloud.CloudApi
import com.ghadirb.aimusic.cloud.CloudConsent
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.premium.Entitlement
import com.ghadirb.aimusic.premium.PremiumFeature
import com.ghadirb.aimusic.recommendation.ReasonText
import com.ghadirb.aimusic.smartplaylist.DjClient
import com.ghadirb.aimusic.smartplaylist.GeneratedPlaylist
import com.ghadirb.aimusic.smartplaylist.ParseContext
import com.ghadirb.aimusic.smartplaylist.PlaylistGenerator
import com.ghadirb.aimusic.smartplaylist.PlaylistIntent
import com.ghadirb.aimusic.smartplaylist.PlaylistIntentParser
import com.ghadirb.aimusic.ui.premium.LocalPremiumAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SmartPlaylistUiState(
    val loading: Boolean = false,
    val result: GeneratedPlaylist? = null,
    val message: String? = null,
    val needsConsent: Boolean = false,
    val totalTracks: Int = 0,
    val analysedTracks: Int = 0
)

class SmartPlaylistViewModel(
    private val repository: MusicRepository,
    private val djClient: DjClient,
    private val consent: CloudConsent
) : ViewModel() {

    private val _state = MutableStateFlow(SmartPlaylistUiState())
    val state: StateFlow<SmartPlaylistUiState> = _state.asStateFlow()
    private var pendingAiText: String? = null
    private var pendingTrackId: Long? = null

    init {
        viewModelScope.launch {
            val (analysed, total) = repository.observeAnalysisProgress().first()
            _state.value = _state.value.copy(totalTracks = total, analysedTracks = analysed)
        }
    }

    /** Rule-based, fully offline. */
    fun generateLocal(text: String, currentTrackId: Long?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null, needsConsent = false)
            val tracks = repository.allTracksSnapshot()
            val intent = PlaylistIntentParser.parse(text, parseContext(tracks, currentTrackId))
            finish(intent, tracks, null)
        }
    }

    /** AI DJ (Premium): only [text] leaves the device; the library never does. Falls back to the local parser. */
    fun generateWithAi(text: String, currentTrackId: Long?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null, needsConsent = false)
            val tracks = repository.allTracksSnapshot()
            when (val result = djClient.intentFor(text, currentTrackId)) {
                is DjClient.Result.Success -> finish(result.intent, tracks, result.remaining?.let { "سهمیهٔ باقی‌ماندهٔ امروز AI: $it" })
                DjClient.Result.ConsentRequired -> {
                    pendingAiText = text; pendingTrackId = currentTrackId
                    _state.value = _state.value.copy(loading = false, needsConsent = true)
                }
                DjClient.Result.QuotaReached -> fallback(text, tracks, currentTrackId, "سهمیهٔ روزانهٔ AI تمام شد؛ از تحلیل محلی استفاده شد.")
                DjClient.Result.PremiumRequired -> fallback(text, tracks, currentTrackId, "پرمیوم شما هنوز روی سرور تأیید نشده؛ از تحلیل محلی استفاده شد.")
                DjClient.Result.Offline -> fallback(text, tracks, currentTrackId, "اینترنت در دسترس نیست؛ از تحلیل محلی استفاده شد.")
                DjClient.Result.Unavailable -> fallback(text, tracks, currentTrackId, "سرویس AI در دسترس نبود؛ از تحلیل محلی استفاده شد.")
            }
        }
    }

    /** Advanced rediscover: liked-and-forgotten music not heard for [days] days. */
    fun generateRediscover(days: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null, needsConsent = false)
            val tracks = repository.allTracksSnapshot()
            finish(
                PlaylistIntent(
                    excludeRecentDays = days, sort = com.ghadirb.aimusic.smartplaylist.SmartSort.LEAST_PLAYED,
                    limit = 30, title = "پلی‌لیست کشف مجدد ($days روز)"
                ),
                tracks, null
            )
        }
    }

    fun grantConsentAndRetry() {
        consent.enabled = true
        val text = pendingAiText ?: return
        generateWithAi(text, pendingTrackId)
    }

    fun dismissConsent() { _state.value = _state.value.copy(needsConsent = false) }

    fun saveResult() {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                message = try {
                    repository.createPlaylistWithTracks(result.name, result.tracks.map { it.track.id })
                    "پلی‌لیست «${result.name}» ذخیره شد."
                } catch (e: Exception) { "ذخیرهٔ پلی‌لیست انجام نشد." }
            )
        }
    }

    private suspend fun fallback(text: String, tracks: List<TrackEntity>, currentTrackId: Long?, note: String) {
        finish(PlaylistIntentParser.parse(text, parseContext(tracks, currentTrackId)), tracks, note)
    }

    private suspend fun finish(intent: PlaylistIntent, tracks: List<TrackEntity>, note: String?) {
        val history = repository.recentHistory(3000)
        val generated = withContext(Dispatchers.Default) { PlaylistGenerator.generate(intent, tracks, history, System.currentTimeMillis()) }
        val message = when {
            intent.isUnconstrained -> "چیز مشخصی متوجه نشدم؛ مثلاً بنویسید «آرام برای مطالعه» یا «شاد برای رانندگی»."
            generated.tracks.isEmpty() -> "آهنگ مناسبی پیدا نشد. اگر تحلیل آهنگ‌ها کامل نیست کمی صبر کنید."
            else -> note
        }
        _state.value = _state.value.copy(loading = false, result = generated.takeIf { it.tracks.isNotEmpty() }, message = message)
    }

    private fun parseContext(tracks: List<TrackEntity>, currentTrackId: Long?) = ParseContext(
        knownArtists = tracks.map { it.artist }.filter { it != "Unknown artist" }.distinct(),
        knownGenres = tracks.mapNotNull { it.genre }.distinct(),
        currentTrackId = currentTrackId
    )
}

private val PRESETS = listOf(
    "مطالعه و تمرکز" to "آرام برای مطالعه",
    "شاد و پرانرژی" to "آهنگ‌های شاد",
    "رانندگی شبانه" to "برای رانندگی شبانه",
    "کمتر شنیده‌ام" to "از آهنگ‌هایی که کمتر گوش داده‌ام",
    "شبیه آهنگ فعلی" to "چند آهنگ شبیه این آهنگ",
    "ورزش" to "برای ورزش"
)
private val FREE_FLOW = MutableStateFlow(Entitlement.FREE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPlaylistScreen(
    repository: MusicRepository,
    cloudApi: CloudApi,
    cloudConsent: CloudConsent,
    currentTrackId: Long?,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val viewModel: SmartPlaylistViewModel = viewModel(
        factory = viewModelFactory { initializer { SmartPlaylistViewModel(repository, DjClient(cloudApi, cloudConsent), cloudConsent) } }
    )
    val state by viewModel.state.collectAsState()
    val access = LocalPremiumAccess.current
    val plan by (access?.entitlement ?: FREE_FLOW).collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var useAi by rememberSaveable { mutableStateOf(false) }
    val aiUnlocked = plan.let { access?.isAllowed(PremiumFeature.AI_DJ) } == true
    // Runs [action] if the plan allows [feature]; otherwise shows the upgrade dialog (or just runs when gating is unavailable).
    fun gate(feature: PremiumFeature, action: () -> Unit) { if (access == null) action() else access.require(feature, action) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Text("پلی‌لیست هوشمند", style = MaterialTheme.typography.headlineSmall)
            Text(
                "درخواستتان به یک ساختار ساده (حال‌وهوا، انرژی، مدت…) تبدیل می‌شود و آهنگ‌ها فقط از کتابخانهٔ خودتان روی همین دستگاه انتخاب می‌شوند.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            if (state.totalTracks > 0 && state.analysedTracks < state.totalTracks) {
                Text("تحلیل صوتی ${state.analysedTracks} از ${state.totalTracks} آهنگ انجام شده؛ با تکمیل آن نتیجه دقیق‌تر می‌شود.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            LazyRow {
                items(PRESETS, key = { it.first }) { (label, prompt) ->
                    AssistChip(
                        onClick = {
                            text = prompt
                            // Basic presets are part of the free "basic smart mix" experience.
                            viewModel.generateLocal(prompt, currentTrackId)
                        },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Text("کشف مجدد پیشرفته (پرمیوم)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            LazyRow {
                items(listOf(30, 60, 90), key = { it }) { days ->
                    AssistChip(
                        onClick = { gate(PremiumFeature.ADVANCED_REDISCOVER) { viewModel.generateRediscover(days) } },
                        label = { Text("نشنیده‌ام: $days روز") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(300) },
                label = { Text("مثلاً: یک ساعت موسیقی آرام برای مطالعه") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (aiUnlocked) Icons.Filled.AutoAwesome else Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  AI DJ (پرمیوم)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = useAi && aiUnlocked,
                    onCheckedChange = { wanted ->
                        if (wanted) gate(PremiumFeature.AI_DJ) { useAi = true } else useAi = false
                    }
                )
            }
            if (useAi && aiUnlocked) {
                Text("فقط متن همین درخواست به سرور فرستاده می‌شود؛ نه آهنگ‌ها و نه تاریخچهٔ شما.", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = text.isNotBlank() && !state.loading,
                onClick = {
                    val run: () -> Unit = {
                        if (useAi && aiUnlocked) viewModel.generateWithAi(text, currentTrackId) else viewModel.generateLocal(text, currentTrackId)
                    }
                    // Free-text requests (local rules or AI DJ) are the Premium "advanced smart playlist / coach".
                    gate(PremiumFeature.SMART_PLAYLIST_GENERATION, run)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("ساخت پلی‌لیست") }

            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
        }

        state.result?.let { result ->
            item {
                Text(result.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                Text("${result.tracks.size} آهنگ · ${result.totalDurationMs / 60_000} دقیقه", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = { onTrackClick(result.tracks.first().track, result.tracks.map { it.track }) }) { Text("پخش") }
                    OutlinedButton(onClick = viewModel::saveResult, modifier = Modifier.padding(start = 8.dp)) { Text("ذخیره به‌عنوان پلی‌لیست") }
                }
            }
            items(result.tracks, key = { it.track.id }) { rec ->
                ListItem(
                    headlineContent = { Text(rec.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            if (rec.track.artist != "Unknown artist") Text(rec.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ReasonText.best(rec)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                )
            }
        }
    }

    if (state.needsConsent) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text("استفاده از AI آنلاین") },
            text = {
                Text(
                    "برای AI DJ فقط متنی که در کادر نوشته‌اید (حداکثر ۳۰۰ نویسه) از طریق سرور امن برنامه به سرویس هوش مصنوعی فرستاده می‌شود تا به یک ساختار ساده تبدیل شود. " +
                        "فایل‌های موسیقی، نام آهنگ‌ها، متن آهنگ و تاریخچهٔ شنیدن هرگز ارسال نمی‌شوند و متن شما ذخیره نمی‌شود. هر زمان از تنظیمات می‌توانید این اجازه را لغو کنید."
                )
            },
            confirmButton = { TextButton(onClick = viewModel::grantConsentAndRetry) { Text("موافقم") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConsent) { Text("فعلاً نه") } }
        )
    }
}
