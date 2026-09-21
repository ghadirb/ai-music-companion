package com.ghadirb.aimusic.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.local.entity.TrackEntity

/** "Start radio" entry point used by artist / album / playlist / favourites screens. Gating happens in [QueueActions]. */
@Composable
fun RadioStartButton(seeds: List<TrackEntity>, label: String, modifier: Modifier = Modifier) {
    val actions = LocalQueueActions.current ?: return
    if (seeds.isEmpty()) return
    OutlinedButton(
        onClick = { actions.startRadio(seeds, label) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text("📻 شروع رادیو: $label") }
}
