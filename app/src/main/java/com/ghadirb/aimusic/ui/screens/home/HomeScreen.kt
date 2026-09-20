package com.ghadirb.aimusic.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.ghadirb.aimusic.lyrics.StorageAccess
import com.ghadirb.aimusic.mix.MixType
import com.ghadirb.aimusic.recommendation.TimeBuckets
import com.ghadirb.aimusic.ui.screens.player.openAllFilesAccess
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ghadirb.aimusic.premium.PremiumFeature
import com.ghadirb.aimusic.recommendation.TuningStore
import com.ghadirb.aimusic.ui.premium.LocalPremiumAccess
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.analysis.AudioAnalysisWorker
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.mix.SmartMix
import com.ghadirb.aimusic.recommendation.ReasonText
import com.ghadirb.aimusic.recommendation.Recommendation

/**
 * Home: greeting, analysis progress, "today's picks" with reasons, and the smart mixes
 * (favourites, recently loved, rediscover, chill, energetic, focus, night, random-from-taste).
 * Everything is computed on-device from the user's own library and listening history.
 */
@Composable
fun HomeScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit,
    onOpenSmartPlaylist: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenPremium: () -> Unit = {}
) {
    val context = LocalContext.current
    val access = LocalPremiumAccess.current
    val tuning = remember { TuningStore(context) }
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(repository) { tuning.effective(access?.isAllowed(PremiumFeature.ADVANCED_RECOMMENDATION) == true) }
            }
        }
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val picks by viewModel.picks.collectAsState()
    val mixes by viewModel.mixes.collectAsState()
    val hasLibrary by viewModel.hasLibrary.collectAsState()
    val isReanalyzing by viewModel.isReanalyzing.collectAsState()
    val progress by viewModel.analysisProgress.collectAsState()
    val message by viewModel.message.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.home_question),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // null = first load still running: render nothing rather than flashing the wrong layout.
        when (hasLibrary) {
            null -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            false -> {
                Text(stringResource(R.string.empty_library), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            true -> Unit
        }

        Row(Modifier.padding(bottom = 16.dp)) {
            AssistChip(onClick = onOpenSmartPlaylist, label = { Text("پلی‌لیست هوشمند") }, modifier = Modifier.padding(end = 8.dp))
            AssistChip(onClick = onOpenStats, label = { Text("آمار") }, modifier = Modifier.padding(end = 8.dp))
            AssistChip(onClick = onOpenPremium, label = { Text("پرمیوم") })
        }

        message?.let {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::dismissMessage) { Text("باشه") }
                }
            }
        }

        // One-time hint: lets the app find "<song name>.lrc" files next to the songs automatically.
        val prefs = remember { context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE) }
        var hintDismissed by remember { mutableStateOf(prefs.getBoolean("lyrics_hint_dismissed", false)) }
        var allFilesGranted by remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) allFilesGranted = StorageAccess.hasAllFilesAccess(context)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (StorageAccess.canRequestAllFilesAccess && !allFilesGranted && !hintDismissed) {
            Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("🎤 شناسایی خودکار متن آهنگ‌ها", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "اگر کنار آهنگ‌ها فایل هم‌نام با پسوند lrc دارید (مثلاً «نام آهنگ.lrc»)، با یک‌بار دسترسی به فایل‌ها متن همگام با آواز خودکار نشان داده می‌شود. فقط روی همین دستگاه خوانده می‌شود.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                    )
                    Row {
                        TextButton(onClick = { openAllFilesAccess(context) }) { Text("فعال‌سازی") }
                        TextButton(onClick = { prefs.edit().putBoolean("lyrics_hint_dismissed", true).apply(); hintDismissed = true }) { Text("بعداً") }
                    }
                }
            }
        }

        val (analysed, total) = progress
        if (total > 0 && analysed < total) {
            Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("در حال تحلیل آهنگ‌ها روی دستگاه: $analysed از $total", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { analysed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Row {
                        TextButton(onClick = { AudioAnalysisWorker.cancel(context.applicationContext) }) { Text("توقف") }
                        TextButton(onClick = { AudioAnalysisWorker.enqueueNow(context.applicationContext) }) { Text("ادامه") }
                    }
                }
            }
        }

        SectionHeader(emoji = "🎧", title = stringResource(R.string.card_today_pick))
        if (picks.isEmpty()) {
            Text(stringResource(R.string.mood_cards_analyzing), style = MaterialTheme.typography.bodySmall)
        } else {
            RecommendationRail(picks) { onTrackClick(it, picks.map { r -> r.track }) }
        }

        // ---- occasions: "what do I want to listen to now?" ----
        val occasionMixes = mixes.filter { it.type.occasion }
        val personalMixes = mixes.filter { !it.type.occasion }
        Spacer(Modifier.height(24.dp))
        SectionHeader(emoji = "🎯", title = "برای هر موقعیت")
        if (occasionMixes.isEmpty()) {
            Text(stringResource(R.string.mood_cards_analyzing), style = MaterialTheme.typography.bodySmall)
        } else {
            val suggested = remember { MixType.suggestedFor(TimeBuckets.bucketOf(System.currentTimeMillis())) }
            var chosen by rememberSaveable { mutableStateOf<String?>(null) }
            val selected = occasionMixes.firstOrNull { it.type.name == chosen }
                ?: occasionMixes.firstOrNull { it.type == suggested }
                ?: occasionMixes.first()
            LazyRow(Modifier.padding(bottom = 8.dp)) {
                items(MixType.values().filter { it.occasion }, key = { it.name }) { type ->
                    val count = occasionMixes.firstOrNull { it.type == type }?.tracks?.size ?: 0
                    FilterChip(
                        selected = selected.type == type,
                        enabled = count > 0,
                        onClick = { chosen = type.name },
                        label = { Text("${type.emoji} ${type.titleFa} ($count)") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            SectionHeader(
                emoji = selected.type.emoji,
                title = selected.type.titleFa,
                actions = {
                    TextButton(onClick = { selected.tracks.firstOrNull()?.let { onTrackClick(it.track, selected.trackList) } }) { Text("پخش همه") }
                    TextButton(onClick = { viewModel.saveMixAsPlaylist(selected) }) { Text("ذخیره") }
                }
            )
            RecommendationRail(selected.tracks) { onTrackClick(it, selected.trackList) }
        }

        // ---- personal mixes ----
        personalMixes.forEach { mix ->
            Spacer(Modifier.height(24.dp))
            SectionHeader(
                emoji = mix.type.emoji,
                title = mix.type.titleFa,
                actions = {
                    TextButton(onClick = { mix.tracks.firstOrNull()?.let { onTrackClick(it.track, mix.trackList) } }) { Text("پخش همه") }
                    TextButton(onClick = { viewModel.saveMixAsPlaylist(mix) }) { Text("ذخیره") }
                }
            )
            RecommendationRail(mix.tracks) { onTrackClick(it, mix.trackList) }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = viewModel::reanalyzeLibrary,
            enabled = !isReanalyzing,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text(if (isReanalyzing) "در صف تحلیل…" else "تحلیل دوبارهٔ پیشنهادهای هوشمند")
        }
    }
}

@Composable
private fun SectionHeader(emoji: String, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$emoji  $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun RecommendationRail(items: List<Recommendation>, onTrackClick: (TrackEntity) -> Unit) {
    if (items.isEmpty()) return
    LazyRow {
        items(items, key = { it.track.id }) { rec ->
            Card(
                modifier = Modifier.padding(end = 12.dp).width(168.dp).height(118.dp),
                shape = RoundedCornerShape(16.dp),
                onClick = { onTrackClick(rec.track) }
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(rec.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        if (rec.track.artist != "Unknown artist") {
                            Text(rec.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ReasonText.best(rec)?.let {
                        Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
