package com.ghadirb.aimusic.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.backup.LocalLibraryBackup
import com.ghadirb.aimusic.billing.MyketBillingClient
import com.ghadirb.aimusic.billing.MyketBillingState
import com.ghadirb.aimusic.data.repository.MusicRepository
import com.ghadirb.aimusic.embedding.OnlineSimilarityRanker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: MusicRepository,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    notificationsNeedPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    myketBillingClient: MyketBillingClient
) {
    val profile by repository.observeUserPreferenceFlow().collectAsState(initial = null)
    val billingState by myketBillingClient.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember(repository) { LocalLibraryBackup(repository) }
    val cloudAiPreferences = remember { context.getSharedPreferences(OnlineSimilarityRanker.PREFS, android.content.Context.MODE_PRIVATE) }
    var cloudAiEnabled by remember { mutableStateOf(cloudAiPreferences.getBoolean(OnlineSimilarityRanker.CONSENT_KEY, false)) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = try {
                backup.exportTo(context.contentResolver, uri)
                "بکاپ محلی با موفقیت ساخته شد."
            } catch (_: Exception) {
                "ساخت بکاپ انجام نشد."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = try {
                val result = backup.restoreFrom(context.contentResolver, uri)
                "بازیابی انجام شد: ${result.favorites} علاقه‌مندی، ${result.playlists} پلی‌لیست و ${result.tracksLinked} آهنگ متصل شد."
            } catch (error: Exception) {
                error.message ?: "بازیابی فایل انجام نشد."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ListItem(
            headlineContent = { Text("ظاهر برنامه") },
            supportingContent = { Text(if (darkTheme) "حالت تاریک" else "حالت روشن") },
            leadingContent = {
                Icon(if (darkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode, contentDescription = null)
            },
            trailingContent = { Switch(checked = darkTheme, onCheckedChange = onThemeChange) }
        )
        HorizontalDivider()

        if (notificationsNeedPermission) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.notifications_title)) },
                supportingContent = { Text(stringResource(R.string.notifications_detail)) },
                trailingContent = {
                    Button(onClick = onRequestNotificationPermission) {
                        Text(stringResource(R.string.enable_notifications))
                    }
                }
            )
            HorizontalDivider()
        }

        ListItem(
            headlineContent = { Text(stringResource(R.string.online_lyrics_title)) },
            supportingContent = { Text(stringResource(R.string.online_lyrics_detail)) }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("AI آنلاین برای پیشنهاد مشابه (بتا)") },
            supportingContent = { Text("با فعال‌سازی، فقط نام آهنگ، خواننده، آلبوم، ژانر، BPM و برچسب حال‌و‌هوا برای رتبه‌بندی شباهت به سرور پروژه فرستاده می‌شود؛ فایل صوتی و متن LRC ارسال نمی‌شود. سهمیهٔ رایگان: ۸ درخواست در روز.") },
            trailingContent = {
                Switch(
                    checked = cloudAiEnabled,
                    onCheckedChange = { enabled ->
                        cloudAiEnabled = enabled
                        cloudAiPreferences.edit().putBoolean(OnlineSimilarityRanker.CONSENT_KEY, enabled).apply()
                    }
                )
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(stringResource(R.string.premium_title)) },
            supportingContent = {
                Text(
                    when (billingState) {
                        MyketBillingState.NotConfigured -> stringResource(R.string.premium_setup_needed)
                        MyketBillingState.Connecting -> "در حال اتصال امن به مایکت…"
                        MyketBillingState.Ready -> "محصول آماده است؛ شروع خرید پس از دریافت توکن یک‌بارمصرفِ سرور فعال می‌شود."
                        MyketBillingState.PurchaseInProgress -> "فرایند پرداخت مایکت در حال انجام است…"
                        MyketBillingState.NeedsSecureCheckout -> "برای جلوگیری از تقلب، ابتدا باید سرویس امن صدور توکن خرید پیکربندی شود."
                        MyketBillingState.RestoreRequiresServerVerification -> "خرید قبلی پیدا شد و برای فعال‌سازی، منتظر تأیید امن سرور است."
                        is MyketBillingState.AwaitingServerVerification -> "پرداخت ثبت شد و در انتظار تأیید امن سرور است."
                        is MyketBillingState.Error -> "اتصال خرید در حال حاضر در دسترس نیست."
                    }
                )
            }
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("بکاپ و بازیابی محلی") },
            supportingContent = { Text("فقط پلی‌لیست‌ها، علاقه‌مندی‌ها و پروفایل سلیقه ذخیره می‌شود؛ هیچ فایل موسیقی یا سابقهٔ شنیدن صادر نمی‌شود.") },
            trailingContent = {
                Column {
                    TextButton(onClick = { exportLauncher.launch("ai-music-companion-backup.json") }) { Text("بکاپ") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("بازیابی") }
                }
            }
        )
        backupMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
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
            supportingContent = { Text("تحلیل موسیقی و LRC محلی روی دستگاه انجام می‌شود. متن آنلاین فقط در صورت فعال‌سازی یک سرویس دارای مجوز و رضایت شما استفاده خواهد شد.") }
        )
    }
}
