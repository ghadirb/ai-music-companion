package com.ghadirb.aimusic.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.data.repository.MusicRepository

@Composable
fun SettingsScreen(
    repository: MusicRepository,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val profile by repository.observeUserPreferenceFlow().collectAsState(initial = null)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ListItem(
            headlineContent = { Text("ظاهر برنامه") },
            supportingContent = { Text(if (darkTheme) "حالت تاریک" else "حالت روشن") },
            leadingContent = {
                Icon(if (darkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode, contentDescription = null)
            },
            trailingContent = { Switch(checked = darkTheme, onCheckedChange = onThemeChange) }
        )
        HorizontalDivider()
        profile?.let { preference ->
            Text(
                "پروفایل سلیقهٔ شما",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, start = 16.dp)
            )
            if (preference.favoriteArtists.isNotBlank()) {
                ListItem(headlineContent = { Text("خوانندگان محبوب") }, supportingContent = { Text(preference.favoriteArtists) })
            }
            if (preference.favoriteGenres.isNotBlank()) {
                ListItem(headlineContent = { Text("سبک‌های محبوب") }, supportingContent = { Text(preference.favoriteGenres) })
            }
            if (preference.favoriteEnergyLevel != "unknown") {
                ListItem(headlineContent = { Text("انرژی ترجیحی") }, supportingContent = { Text(preference.favoriteEnergyLevel) })
            }
            if (preference.preferredTimeOfDay != "unknown") {
                ListItem(headlineContent = { Text("زمان معمول گوش‌دادن") }, supportingContent = { Text(preference.preferredTimeOfDay) })
            }
            HorizontalDivider()
        }
        ListItem(headlineContent = { Text("نسخه") }, supportingContent = { Text("0.1.0-mvp") })
        ListItem(
            headlineContent = { Text("حریم خصوصی") },
            supportingContent = { Text("تمام پردازش‌ها روی دستگاه انجام می‌شود؛ هیچ فایل یا تاریخچه‌ای ارسال نمی‌شود.") }
        )
    }
}
