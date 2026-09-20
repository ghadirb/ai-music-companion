package com.ghadirb.aimusic.billing

import com.ghadirb.aimusic.BuildConfig
import com.ghadirb.aimusic.cloud.CloudApi
import com.ghadirb.aimusic.premium.EntitlementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed interface PurchaseUiState {
    data object Idle : PurchaseUiState
    /** The store integration or gateway is not configured in this build. */
    data object NotConfigured : PurchaseUiState
    data class Loading(val message: String) : PurchaseUiState
    data class Success(val message: String) : PurchaseUiState
    data class Error(val message: String) : PurchaseUiState
}

/**
 * Orchestrates: gateway nonce -> store checkout -> gateway verification with Myket -> signed entitlement.
 * Premium is switched on ONLY by a verified server response (never by the store callback), and
 * "Restore purchase" re-verifies what the store says the user owns, so it also works after a reinstall.
 */
class PurchaseRepository(
    private val api: CloudApi,
    private val entitlements: EntitlementRepository
) {
    /** Set by the Activity that owns the store connection (billing needs an Activity). */
    @Volatile var gateway: BillingGateway? = null

    private val _state = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    val state: StateFlow<PurchaseUiState> = _state.asStateFlow()

    /** SKUs offered by this build, e.g. "premium_lifetime[,premium_monthly,premium_yearly]". */
    val offeredSkus: List<String> = BuildConfig.MYKET_PREMIUM_SKUS.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun reset() { _state.value = PurchaseUiState.Idle }

    suspend fun purchase(sku: String) {
        val billing = gateway
        if (billing == null || !billing.isConfigured) { _state.value = PurchaseUiState.NotConfigured; return }
        if (sku !in offeredSkus) { _state.value = PurchaseUiState.Error("این بسته در دسترس نیست."); return }
        _state.value = PurchaseUiState.Loading("در حال آماده‌سازی خرید امن…")

        val nonce = api.post("/v1/myket/purchase-nonce", JSONObject().put("sku", sku))
        if (nonce.isOffline) { _state.value = PurchaseUiState.Error("اتصال اینترنت برقرار نیست."); return }
        if (nonce.code == 503) { _state.value = PurchaseUiState.NotConfigured; return }
        val payload = nonce.body.optString("developerPayload")
        if (!nonce.isSuccess || payload.isBlank()) { _state.value = PurchaseUiState.Error("شروع خرید ممکن نشد. کمی بعد دوباره تلاش کنید."); return }

        _state.value = PurchaseUiState.Loading("در حال اتصال به مایکت…")
        when (val outcome = billing.purchase(sku, payload)) {
            PurchaseOutcome.Cancelled -> _state.value = PurchaseUiState.Idle
            is PurchaseOutcome.Failed -> _state.value = PurchaseUiState.Error(outcome.message)
            is PurchaseOutcome.Purchased -> {
                _state.value = PurchaseUiState.Loading("در حال تأیید امن خرید…")
                val verify = api.post(
                    "/v1/myket/verify",
                    JSONObject().put("sku", outcome.sku).put("tokenId", outcome.token).put("developerPayload", outcome.developerPayload)
                )
                _state.value = handleVerification(verify, "پرداخت انجام شد ولی تأیید آن هنوز کامل نشده. با «بازیابی خرید» دوباره امتحان کنید.")
            }
        }
    }

    suspend fun restore() {
        val billing = gateway
        if (billing == null || !billing.isConfigured) { _state.value = PurchaseUiState.NotConfigured; return }
        _state.value = PurchaseUiState.Loading("در حال جست‌وجوی خریدهای شما در مایکت…")
        val owned = billing.queryOwned(offeredSkus)
        if (owned.isEmpty()) { _state.value = PurchaseUiState.Error("خرید قبلی برای این حساب مایکت پیدا نشد."); return }
        for (purchase in owned) {
            val response = api.post("/v1/myket/restore", JSONObject().put("sku", purchase.sku).put("tokenId", purchase.token))
            val result = handleVerification(response, "بازیابی خرید کامل نشد.")
            if (result is PurchaseUiState.Success) { _state.value = result; return }
            _state.value = result
        }
    }

    private fun handleVerification(response: CloudApi.Response, fallbackError: String): PurchaseUiState {
        if (response.isOffline) return PurchaseUiState.Error("اتصال اینترنت برقرار نیست.")
        if (!response.isSuccess) {
            return PurchaseUiState.Error(
                when (response.error) {
                    "purchase_already_claimed", "transfer_limit_reached" -> "این خرید قبلاً برای حساب دیگری فعال شده است."
                    "transfer_cooldown" -> "برای انتقال خرید به دستگاه جدید، کمی بعد دوباره تلاش کنید."
                    "verification_unavailable" -> "تأیید مایکت موقتاً در دسترس نیست؛ کمی بعد «بازیابی خرید» را بزنید."
                    "payment_not_configured" -> return PurchaseUiState.NotConfigured
                    else -> fallbackError
                }
            )
        }
        entitlements.applyServerState(response.body)
        return if (response.body.optBoolean("premium", false)) PurchaseUiState.Success("پرمیوم فعال شد. ممنون از حمایت شما!")
        else PurchaseUiState.Error(fallbackError)
    }
}
