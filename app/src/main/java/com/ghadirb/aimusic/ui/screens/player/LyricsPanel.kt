package com.ghadirb.aimusic.ui.screens.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghadirb.aimusic.lyrics.LrcParser
import com.ghadirb.aimusic.lyrics.LyricsState
import com.ghadirb.aimusic.lyrics.LyricsTimeline
import com.ghadirb.aimusic.lyrics.StorageAccess

/**
 * Karaoke-style lyrics: the line being sung is highlighted (bigger, coloured), lines already sung fade,
 * and the list scrolls by itself to keep the current line in view. Tap a line to jump to it.
 * Plain (un-timed) lyrics are simply shown as scrolling text.
 */
@Composable
fun LyricsPanel(
    parsed: LrcParser.Parsed,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val lines = parsed.lines
    val active = if (parsed.synced) LyricsTimeline.activeIndex(lines, positionMs) else -1
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -(listState.layoutInfo.viewportSize.height / 3))
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val isActive = index == active
            val isPast = active >= 0 && index < active
            val scale by animateFloatAsState(if (isActive) 1.12f else 1f, label = "lyricScale")
            val color by animateColorAsState(
                when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isPast -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                },
                label = "lyricColor"
            )
            Text(
                text = line.text,
                textAlign = TextAlign.Center,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (parsed.synced) Modifier.clickable { onSeek(line.timeMs) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
            )
        }
    }
}

/** Inline lyrics under the player controls. */
@Composable
fun LyricsCard(
    state: LyricsState,
    positionMs: Long,
    hasFolder: Boolean,
    allFilesAccess: Boolean,
    onExpand: () -> Unit,
    onSeek: (Long) -> Unit,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("متن آهنگ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onExpand) { Icon(Icons.Filled.Fullscreen, contentDescription = "متن آهنگ در تمام صفحه") }
            }
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    LyricsState.Loading -> CircularProgressIndicator()
                    LyricsState.NotFound -> LyricsMissing(hasFolder, allFilesAccess, onImportFile, onPickFolder, compact = true)
                    is LyricsState.Found -> LyricsPanel(state.parsed, positionMs, onSeek, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun LyricsMissing(
    hasFolder: Boolean,
    allFilesAccess: Boolean,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit,
    compact: Boolean
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Text("متن این آهنگ پیدا نشد", style = MaterialTheme.typography.titleSmall)
        Text(
            if (StorageAccess.canRequestAllFilesAccess && !allFilesAccess)
                "برای شناسایی خودکار فایل هم‌نامِ کنار آهنگ (مثلاً «نام آهنگ.lrc»)، یک‌بار دسترسی به فایل‌ها را فعال کنید. فقط روی همین دستگاه خوانده می‌شود."
            else "فایل «نام آهنگ.lrc» را کنار آهنگ بگذارید یا برای همین آهنگ انتخاب کنید.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = if (compact) 4 else 8
        )
        if (StorageAccess.canRequestAllFilesAccess && !allFilesAccess) {
            Button(onClick = { openAllFilesAccess(context) }, modifier = Modifier.fillMaxWidth()) { Text("فعال‌سازی شناسایی خودکار") }
        }
        OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) { Text("انتخاب فایل LRC برای این آهنگ") }
        TextButton(onClick = onPickFolder) { Text(if (hasFolder) "افزودن پوشهٔ دیگر" else "یا انتخاب پوشهٔ موسیقی") }
    }
}

fun openAllFilesAccess(context: Context) {
    try {
        context.startActivity(StorageAccess.settingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* no settings screen available */ }
    }
}

/** Full-screen karaoke view. */
@Composable
fun LyricsFullScreen(
    title: String,
    state: LyricsState,
    positionMs: Long,
    hasFolder: Boolean,
    allFilesAccess: Boolean,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "بستن") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state) {
                        LyricsState.Loading -> CircularProgressIndicator()
                        LyricsState.NotFound -> LyricsMissing(hasFolder, allFilesAccess, onImportFile, onPickFolder, compact = false)
                        is LyricsState.Found -> LyricsPanel(state.parsed, positionMs, onSeek, Modifier.fillMaxSize(), large = true)
                    }
                }
            }
        }
    }
}
