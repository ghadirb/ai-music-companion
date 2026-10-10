package com.ghadirb.aimusic.ui.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.local.entity.VideoEntity
import com.ghadirb.aimusic.video.VideoLibraryQuery
import com.ghadirb.aimusic.video.VideoResume
import android.graphics.Bitmap

/** Lazy, cached, bounded thumbnail (see [VideoThumbnails]); a placeholder is shown until/unless it loads. */
@Composable
fun VideoThumbnail(video: VideoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = com.ghadirb.aimusic.video.VideoThumbnails.peek(video.id), video.id) {
        if (value == null) value = com.ghadirb.aimusic.video.VideoThumbnails.load(context, video.id, video.contentUri)
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = video.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        // Duration badge
        Text(
            VideoLibraryQuery.formatDuration(video.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (VideoResume.hasResume(video.lastPositionMs, video.durationMs) && video.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (video.lastPositionMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            )
        }
    }
}

private fun meta(video: VideoEntity): String = listOfNotNull(
    video.folderName.takeIf { it.isNotBlank() },
    VideoLibraryQuery.formatSize(video.sizeBytes),
    VideoLibraryQuery.formatResolution(video.width, video.height)
).joinToString(" • ")

@Composable
private fun VideoMenu(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "گزینه‌ها") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("حذف") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

/** Grid cell. */
@Composable
fun VideoGridItem(video: VideoEntity, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        VideoThumbnail(video, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(video.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(video), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VideoMenu(onDelete)
        }
    }
}

/** List row. */
@Composable
fun VideoListItem(video: VideoEntity, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VideoThumbnail(video, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(video.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(2.dp))
            Text(meta(video), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        VideoMenu(onDelete)
    }
}
