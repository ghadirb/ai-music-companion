package com.ghadirb.aimusic.ui.screens.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * MVP scope only covers manual playlists (create/name/add tracks) — see README
 * "Feature Status". This screen is currently a placeholder until that CRUD UI
 * and its Room tables (Playlist, PlaylistTrackCrossRef) are added.
 */
@Composable
fun PlaylistsScreen() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("پلی‌لیست‌های دستی به‌زودی — این بخش هنوز پیاده‌سازی نشده (به README مراجعه کنید)")
    }
}
