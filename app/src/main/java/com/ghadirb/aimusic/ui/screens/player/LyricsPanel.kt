package com.ghadirb.aimusic.ui.screens.player

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
import androidx.compose.material.icons.filled.MoreVert
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
import com.ghadirb.aimusic.lyrics.LyricsOrigin
import com.ghadirb.aimusic.lyrics.LyricsState
import com.ghadirb.aimusic.lyrics.LyricsTimeline

/**
 * Karaoke-style lyrics: the line being sung is highlighted (bigger, coloured), lines already sung fade,
 * and the list scrolls by itself to keep the current line in view. Tap a line to jump to it.
 * Plain (un-timed) lyrics are simply shown as scrolling text.
 */
@Composable
fun LyricsPanel(
    parsed: LrcParser.Parsed,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val lines = parsed.lines
    // Real timestamps drive the highlight; lyrics without timestamps follow the playback position approximately.
    val active = if (parsed.synced) LyricsTimeline.activeIndex(lines, positionMs) else LyricsTimeline.estimatedIndex(lines, positionMs, durationMs)
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -(listState.layoutInfo.viewportSize.height / 3))
    }
    LaunchedEffect(lines) { listState.scrollToItem(0) } // a new song starts at the top
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

private fun originLabel(state: LyricsState.Found): String {
    val origin = when (state.origin) {
        LyricsOrigin.IMPORTED -> "فایل واردشده"
        LyricsOrigin.SIDECAR -> "فایل هم‌نام"
        LyricsOrigin.EMBEDDED -> "تگ فایل صوتی"
    }
    val sync = if (state.parsed.synced) "همگام با آواز" else "بدون زمان‌بندی؛ حرکت تقریبی"
    return "$origin · $sync"
}

/** Inline lyrics under the player controls. */
@Composable
fun LyricsCard(
    state: LyricsState,
    positionMs: Long,
    durationMs: Long,
    hasFolder: Boolean,
    onExpand: () -> Unit,
    onSeek: (Long) -> Unit,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit,
    onDiagnose: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("متن آهنگ", style = MaterialTheme.typography.titleMedium)
                    if (state is LyricsState.Found) Text(originLabel(state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "گزینه‌های متن آهنگ") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("انتخاب فایل LRC (همگام) برای این آهنگ") }, onClick = { menuOpen = false; onImportFile() })
                        DropdownMenuItem(text = { Text(if (hasFolder) "افزودن پوشهٔ متن‌ها/موسیقی" else "انتخاب پوشهٔ موسیقی برای شناسایی خودکار") }, onClick = { menuOpen = false; onPickFolder() })
                        DropdownMenuItem(text = { Text("عیب‌یابی متن آهنگ") }, onClick = { menuOpen = false; onDiagnose() })
                    }
                }
                IconButton(onClick = onExpand) { Icon(Icons.Filled.Fullscreen, contentDescription = "متن آهنگ در تمام صفحه") }
            }
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    LyricsState.Loading -> CircularProgressIndicator()
                    LyricsState.NotFound -> LyricsMissing(hasFolder, onImportFile, onPickFolder, onDiagnose, compact = true)
                    is LyricsState.Found -> LyricsPanel(state.parsed, positionMs, durationMs, onSeek, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun LyricsMissing(
    hasFolder: Boolean,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit,
    onDiagnose: () -> Unit,
    compact: Boolean
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Text("متن این آهنگ پیدا نشد", style = MaterialTheme.typography.titleSmall)
        Text(
            if (!hasFolder) "برای شناسایی خودکار فایل هم‌نامِ کنار آهنگ (مثلاً «نام آهنگ.lrc»)، پوشهٔ موسیقی‌تان (مثلاً Music) را یک‌بار انتخاب کنید. فقط همان پوشه و فقط روی همین دستگاه خوانده می‌شود."
            else "فایل هم‌نامی در پوشه‌های انتخاب‌شده پیدا نشد. می‌توانید فایل را برای همین آهنگ انتخاب کنید یا علت را ببینید.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = if (compact) 5 else 8
        )
        if (!hasFolder) Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) { Text("انتخاب پوشهٔ موسیقی") }
        OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) { Text("انتخاب فایل LRC برای این آهنگ") }
        Row {
            if (hasFolder) TextButton(onClick = onPickFolder) { Text("افزودن پوشهٔ دیگر") }
            TextButton(onClick = onDiagnose) { Text("چرا پیدا نشد؟") }
        }
    }
}

/** Full-screen karaoke view. */
@Composable
fun LyricsFullScreen(
    title: String,
    state: LyricsState,
    positionMs: Long,
    durationMs: Long,
    hasFolder: Boolean,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
    onImportFile: () -> Unit,
    onPickFolder: () -> Unit,
    onDiagnose: () -> Unit
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
                        LyricsState.NotFound -> LyricsMissing(hasFolder, onImportFile, onPickFolder, onDiagnose, compact = false)
                        is LyricsState.Found -> LyricsPanel(state.parsed, positionMs, durationMs, onSeek, Modifier.fillMaxSize(), large = true)
                    }
                }
            }
        }
    }
}
