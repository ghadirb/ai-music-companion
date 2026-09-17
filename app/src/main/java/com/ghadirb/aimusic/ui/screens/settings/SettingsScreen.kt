package com.ghadirb.aimusic.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ListItem(headlineContent = { Text("نسخه") }, supportingContent = { Text("0.1.0-mvp") })
        ListItem(
            headlineContent = { Text("حریم خصوصی") },
            supportingContent = { Text("تمام پردازش‌ها روی دستگاه انجام می‌شود؛ هیچ فایل یا تاریخچه‌ای ارسال نمی‌شود.") }
        )
    }
}
