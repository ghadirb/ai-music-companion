package com.ghadirb.aimusic.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.library.ExcludedFolders

/**
 * Pick music folders to leave out of the library. Lists the folders that currently hold library tracks plus any
 * already-excluded folder (so it can be un-excluded). Nothing on the phone is deleted.
 */
@Composable
fun ExcludedFoldersDialog(
    folders: List<Pair<String, Int>>,
    excluded: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(excluded.map { ExcludedFolders.normalize(it) }.toSet()) }
    val shown = remember(folders, excluded) {
        val known = folders.associate { ExcludedFolders.normalize(it.first) to (it.first to it.second) }
        val extra = excluded.filter { ExcludedFolders.normalize(it) !in known }.map { it to 0 }
        (folders + extra).distinctBy { ExcludedFolders.normalize(it.first) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("پوشه‌های نادیده‌گرفته‌شده") },
        text = {
            Column {
                Text(
                    "آهنگ‌های پوشه‌های تیک‌خورده از کتابخانه و پیشنهادها حذف می‌شوند. فایل‌ها از گوشی پاک نمی‌شوند و با برداشتن تیک دوباره اضافه می‌شوند.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (shown.isEmpty()) {
                    Text("پوشه‌ای برای نمایش نیست. ابتدا کتابخانه را اسکن کنید.", Modifier.padding(top = 12.dp))
                } else LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.first }) { (path, count) ->
                        val key = ExcludedFolders.normalize(path)
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (key in selected) selected - key else selected + key },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = key in selected, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(path.trimEnd('/').substringAfterLast('/').ifBlank { path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (count > 0) "$count آهنگ — $path" else path,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keep the original spelling of the path for the chosen folders.
                val originals = (folders.map { it.first } + excluded).associateBy { ExcludedFolders.normalize(it) }
                onSave(selected.mapNotNull { originals[it] ?: it }.toSet())
            }) { Text("ذخیره و اسکن دوباره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
