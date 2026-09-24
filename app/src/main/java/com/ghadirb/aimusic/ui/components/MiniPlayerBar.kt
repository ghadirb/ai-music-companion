package com.ghadirb.aimusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/** Compact "now playing" bar shown above the bottom navigation on every screen except the player. */
@Composable
fun MiniPlayerBar(
    track: TrackEntity,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "پخش‌کنندهٔ کوچک: ${track.title}" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (track.albumArtUri != null) {
                    AsyncImage(
                        model = track.albumArtUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Filled.MusicNote),
                        modifier = Modifier.size(44.dp)
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold
                )
                if (track.artist != "Unknown artist") {
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "توقف" else "پخش"
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "آهنگ بعدی")
            }
        }
    }
}
