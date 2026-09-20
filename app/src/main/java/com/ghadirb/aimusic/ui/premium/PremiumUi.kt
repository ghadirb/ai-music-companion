package com.ghadirb.aimusic.ui.premium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghadirb.aimusic.billing.PurchaseRepository
import com.ghadirb.aimusic.billing.PurchaseUiState
import com.ghadirb.aimusic.premium.AiQuota
import com.ghadirb.aimusic.premium.Entitlement
import com.ghadirb.aimusic.premium.EntitlementRepository
import com.ghadirb.aimusic.premium.EntitlementSource
import com.ghadirb.aimusic.premium.FeatureGate
import com.ghadirb.aimusic.premium.PremiumFeature
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PremiumViewModel(
    private val entitlements: EntitlementRepository,
    private val purchases: PurchaseRepository
) : ViewModel() {
    val entitlement: StateFlow<Entitlement> = entitlements.entitlement
    val quota: StateFlow<AiQuota?> = entitlements.quota
    val purchaseState: StateFlow<PurchaseUiState> = purchases.state
    val offeredSkus: List<String> get() = purchases.offeredSkus

    fun isAllowed(feature: PremiumFeature): Boolean = entitlements.isAllowed(feature)
    fun refresh() { viewModelScope.launch { entitlements.refresh() } }
    fun buy(sku: String) { viewModelScope.launch { purchases.purchase(sku) } }
    fun restore() { viewModelScope.launch { purchases.restore() } }
    fun resetPurchaseState() = purchases.reset()
}

/** What screens use to gate features: `require(feature) { doPremiumThing() }`. */
@Stable
class PremiumAccess(
    val entitlement: StateFlow<Entitlement>,
    private val isAllowed: (PremiumFeature) -> Boolean,
    private val onNeedUpgrade: (PremiumFeature) -> Unit
) {
    fun isAllowed(feature: PremiumFeature) = isAllowed.invoke(feature)

    /** Runs [action] if the user's plan allows it; otherwise shows the (non-intrusive) upgrade dialog. */
    fun require(feature: PremiumFeature, action: () -> Unit) {
        if (isAllowed(feature)) action() else onNeedUpgrade(feature)
    }
}

val LocalPremiumAccess = staticCompositionLocalOf<PremiumAccess?> { null }

private fun skuLabel(sku: String) = when (sku) {
    "premium_lifetime" -> "خرید نسخهٔ دائمی"
    "premium_monthly" -> "اشتراک ماهانه"
    "premium_yearly" -> "اشتراک سالانه"
    else -> "خرید $sku"
}

/** Shown only when a Free user actually tries a Premium feature. Never blocks the core player. */
@Composable
fun UpgradeDialog(
    feature: PremiumFeature,
    purchaseState: PurchaseUiState,
    skus: List<String>,
    onBuy: (String) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("این قابلیت ویژهٔ پرمیوم است") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(feature.titleFa, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(feature.descriptionFa, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Text("مزایای پرمیوم", style = MaterialTheme.typography.labelLarge)
                FeatureGate.premiumFeatures.filter { it != PremiumFeature.ONLINE_LYRICS }.forEach {
                    Text("• ${it.titleFa}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                Text("• سهمیهٔ روزانهٔ بیشتر برای AI", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                Text(
                    "پخش‌کننده، کتابخانه، علاقه‌مندی‌ها، پلی‌لیست‌ها، پیشنهادهای پایه و بکاپ همیشه رایگان می‌مانند.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)
                )
                PurchaseStatus(purchaseState)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                val busy = purchaseState is PurchaseUiState.Loading
                skus.forEach { sku ->
                    Button(onClick = { onBuy(sku) }, enabled = !busy) { Text(skuLabel(sku)) }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRestore, enabled = purchaseState !is PurchaseUiState.Loading) { Text("بازیابی خرید") }
                TextButton(onClick = onDismiss) { Text("بعداً") }
            }
        }
    )
}

@Composable
fun PurchaseStatus(state: PurchaseUiState) {
    when (state) {
        PurchaseUiState.Idle -> Unit
        PurchaseUiState.NotConfigured -> Text(
            "پرداخت هنوز در این نسخه فعال نشده است.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)
        )
        is PurchaseUiState.Loading -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
        }
        is PurchaseUiState.Success -> Text(state.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        is PurchaseUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun PremiumScreen(viewModel: PremiumViewModel) {
    val entitlement by viewModel.entitlement.collectAsState()
    val quota by viewModel.quota.collectAsState()
    val purchaseState by viewModel.purchaseState.collectAsState()
    val premium = entitlement.isPremiumAt(System.currentTimeMillis())

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("  AI Music Companion Premium", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "آفلاین، خصوصی و شخصی‌سازی‌شده. پرمیوم فقط قابلیت‌های هوشمند پیشرفته را اضافه می‌کند.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (premium) "پلن شما: پرمیوم ✅" else "پلن شما: رایگان", style = MaterialTheme.typography.titleMedium)
                if (premium && entitlement.source == EntitlementSource.SERVER_SESSION) {
                    Text("تأیید امن سرور (فقط برای همین نشست). اتصال به اینترنت لازم است.", style = MaterialTheme.typography.bodySmall)
                }
                entitlement.expiresAtMs?.let {
                    Text("اعتبار تا: ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall)
                }
                quota?.let { Text("سهمیهٔ امروز AI: ${it.remaining} از ${it.limit}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }

        Text("رایگان (همیشه)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        FREE_FEATURES.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

        Text("پرمیوم", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        FeatureGate.premiumFeatures.filter { it != PremiumFeature.ONLINE_LYRICS }.forEach {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("• ${it.titleFa}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(it.descriptionFa, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("• سهمیهٔ روزانهٔ بیشتر برای AI", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

        if (!premium) {
            Spacer(Modifier.height(20.dp))
            val busy = purchaseState is PurchaseUiState.Loading
            viewModel.offeredSkus.forEach { sku ->
                Button(onClick = { viewModel.buy(sku) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text(skuLabel(sku)) }
            }
            Text("قیمت در صفحهٔ پرداخت مایکت نمایش داده می‌شود.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = viewModel::restore,
            enabled = purchaseState !is PurchaseUiState.Loading,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("بازیابی خرید") }
        PurchaseStatus(purchaseState)
        Text(
            "برای فعال‌سازی، خرید شما از طریق سرور با مایکت راستی‌آزمایی می‌شود؛ پرداخت به‌تنهایی پرمیوم را فعال نمی‌کند. " +
                "اگر برنامه را پاک و دوباره نصب کنید، «بازیابی خرید» همان خرید را روی حساب مایکت شما پیدا می‌کند.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)
        )
    }
}

private val FREE_FEATURES = listOf(
    "پخش‌کننده کامل و آفلاین، صف پخش، تایمر خواب",
    "کتابخانه، خواننده‌ها، آلبوم‌ها، پوشه‌ها، جست‌وجو و مرتب‌سازی",
    "علاقه‌مندی‌ها، پلی‌لیست‌ها و تاریخچه",
    "پیشنهادهای پایه همراه با دلیل، میکس‌های هوشمند و کشف مجدد پایه",
    "تحلیل صوتی محلی، متن LRC محلی",
    "بکاپ و بازیابی، آمار پایه"
)
