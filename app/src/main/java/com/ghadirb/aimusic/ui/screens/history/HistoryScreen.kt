package com.ghadirb.aimusic.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.stats.HistoryAggregator
import com.ghadirb.aimusic.stats.HistoryEntry
import com.ghadirb.aimusic.stats.JalaliCalendar
import com.ghadirb.aimusic.ui.components.EmptyState
import com.ghadirb.aimusic.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

private enum class HistoryTab(val label: String) {
    RECENT("آخرین پخش‌ها"),
    TOP("بیشترین شنیده‌شده")
}

private sealed interface HistoryState {
    data object Loading : HistoryState
    data object Error : HistoryState
    data class Ready(val recent: List<HistoryEntry>, val top: List<HistoryEntry>) : HistoryState
}

private suspend fun loadHistory(repository: MusicRepository): HistoryState.Ready {
    val tracks = repository.observeTracks().first().associateBy { it.id }
    val history = repository.recentHistory(10_000)
    return withContext(Dispatchers.Default) {
        HistoryState.Ready(
            recent = HistoryAggregator.recent(history, tracks),
            top = HistoryAggregator.top(history, tracks)
        )
    }
}

/**
 * Advanced listening history (spec v1.1 item 4): latest plays with time, per-track play counts and
 * the most-played tracks. Everything is read from the on-device Room history; nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: MusicRepository,
    currentTrack: TrackEntity?,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    var state by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
    var tab by remember { mutableStateOf(HistoryTab.RECENT) }
    val currentTrackId = currentTrack?.id

    LaunchedEffect(Unit) {
        state = try {
            loadHistory(repository)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HistoryState.Error
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            HistoryTab.values().forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(t.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        when (val s = state) {
            HistoryState.Loading -> LoadingState()
            HistoryState.Error -> EmptyState(
                Icons.Filled.ErrorOutline,
                "بارگذاری تاریخچه ممکن نشد",
                "لطفاً صفحه را ببندید و دوباره باز کنید."
            )
            is HistoryState.Ready -> {
                val rows = if (tab == HistoryTab.RECENT) s.recent else s.top
                // The track being played right now is only written to history when its session ends
                // (next track / finished / skipped), so show it live at the top of the recent list.
                val live = if (tab == HistoryTab.RECENT) currentTrack else null
                if (rows.isEmpty() && live == null) {
                    EmptyState(
                        Icons.Filled.History,
                        "هنوز آهنگی پخش نکرده‌اید",
                        "با گوش دادن به موسیقی، تاریخچهٔ شنیدن شما اینجا ساخته می‌شود؛ فقط روی همین دستگاه ذخیره می‌شود."
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val queue = rows.map { it.track }.distinctBy { it.id }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (live != null) {
                            item(key = "live") {
                                ListItem(
                                    headlineContent = { Text(live.title, maxLines = 1) },
                                    supportingContent = { Text("اکنون در حال پخش • پس از پایان یا رد کردن در تاریخچه ثبت می‌شود", maxLines = 2) },
                                    leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = "در حال پخش") },
                                    modifier = Modifier.clickable { onTrackClick(live, listOf(live)) }
                                )
                            }
                        }
                        itemsIndexed(rows, key = { index, row -> "${index}_${row.track.id}" }) { _, row ->
                            HistoryRow(
                                row = row,
                                now = now,
                                showPlayCount = true,
                                isCurrent = row.track.id == currentTrackId,
                                onClick = { onTrackClick(row.track, queue) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(row: HistoryEntry, now: Long, showPlayCount: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    val track = row.track
    val details = listOfNotNull(
        track.artist.takeUnless { it == "Unknown artist" },
        if (showPlayCount) "${row.plays} بار پخش".faDigits() else null,
        if (row.partial) "${(row.completedPercentage * 100).toInt()}٪ شنیده شد".faDigits() else null,
        "آخرین پخش: ${formatWhen(row.lastPlayedAt, now)}"
    ).joinToString(" • ")
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1) },
        supportingContent = { Text(details, maxLines = 2) },
        leadingContent = {
            if (track.albumArtUri != null) {
                AsyncImage(track.albumArtUri, contentDescription = track.album, modifier = Modifier.size(48.dp))
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
            }
        },
        trailingContent = {
            if (isCurrent) Icon(Icons.Filled.PlayArrow, contentDescription = "در حال پخش")
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** "امروز، ۱۴:۰۵" / "۱۲ مهر، ۰۹:۳۰" (Jalali date, 24h time, Persian digits). */
private fun formatWhen(timeMs: Long, nowMs: Long): String {
    val zone = TimeZone.getDefault()
    val j = JalaliCalendar.jalaliOf(timeMs, zone)
    val n = JalaliCalendar.jalaliOf(nowMs, zone)
    val cal = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
    val time = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    val day = when {
        j[0] == n[0] && j[1] == n[1] && j[2] == n[2] -> "امروز"
        j[0] == n[0] -> "${j[2]} ${JalaliCalendar.monthName(j[1])}"
        else -> "${j[2]} ${JalaliCalendar.monthName(j[1])} ${j[0]}"
    }
    return "$day، $time".faDigits()
}

private fun String.faDigits(): String = buildString {
    for (c in this@faDigits) append(if (c in '0'..'9') '۰' + (c - '0') else c)
}
