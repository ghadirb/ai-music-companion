package com.ghadirb.aimusic.ui.screens.folders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.repository.MusicRepository

@Composable
fun FoldersScreen(repository: MusicRepository) {
    val tracks by repository.observeTracks().collectAsState(initial = emptyList())
    val folders = tracks.mapNotNull { it.folderPath?.takeIf(String::isNotBlank) }.distinct().sorted()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (folders.isEmpty()) Text("پس از اسکن کتابخانه، پوشه‌های موسیقی اینجا نمایش داده می‌شوند.")
        else LazyColumn {
            items(folders, key = { it }) { folder ->
                ListItem(
                    headlineContent = { Text(folder.substringAfterLast('/')) },
                    supportingContent = { Text("${tracks.count { it.folderPath == folder }} آهنگ") },
                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) }
                )
            }
        }
    }
}
