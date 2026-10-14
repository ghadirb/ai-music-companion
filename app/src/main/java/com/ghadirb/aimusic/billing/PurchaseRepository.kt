package com.ghadirb.aimusic.billing

import android.content.Context
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
    private val entitlements: EntitlementRepository,
    context: Context? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * A paid purchase whose server verification has not completed yet. It is kept on disk so a lost network,
     * a recreated Activity or a killed process never leaves a paying user without Premium: [recover] retries it.
     * Holds the store purchase token only (no personal data); removed as soon as the server confirms.
     */
    private val pendingPrefs = context?.applicationContext?.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastPendingTryAt = 0L
    @Volatile private var lastSilentRestoreAt = 0L

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
                // Persist BEFORE talking to the server: the money is already spent at this point.
                savePending(outcome.sku, outcome.token, outcome.developerPayload)
                _state.value = PurchaseUiState.Loading("در حال تأیید امن خرید…")
                val verify = api.post(
                    "/v1/myket/verify",
                    JSONObject().put("sku", outcome.sku).put("tokenId", outcome.token).put("developerPayload", outcome.developerPayload)
                )
                val result = handleVerification(verify, "پرداخت انجام شد ولی تأیید آن هنوز کامل نشده؛ برنامه خودکار دوباره تلاش می‌کند. می‌توانید «بازیابی خرید» را هم بزنید.")
                if (result is PurchaseUiState.Success || verify.error in TERMINAL_ERRORS) clearPending()
                _state.value = result
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

    /**
     * Self-healing, called when the app opens/resumes. (1) Re-verifies a paid-but-unconfirmed purchase,
     * (2) if the user is still not Premium, silently asks Myket what they own and restores it.
     * Never shows an error (the user did nothing); only a successful activation updates [state].
     */
    suspend fun recover() {
        val current = entitlements.entitlement.value
        if (current.isPremiumAt(clock()) && !current.isTrial) return
        if (_state.value is PurchaseUiState.Loading) return
        if (!recovering.compareAndSet(false, true)) return
        try {
            val now = clock()
            val pending = readPending()
            if (pending != null && now - lastPendingTryAt > PENDING_RETRY_MS) {
                lastPendingTryAt = now
                val verify = api.post(
                    "/v1/myket/verify",
                    JSONObject().put("sku", pending.sku).put("tokenId", pending.token).put("developerPayload", pending.payload)
                )
                if (verify.isOffline) return
                val result = handleVerification(verify, "")
                if (result is PurchaseUiState.Success) { clearPending(); _state.value = result; return }
                if (verify.error in TERMINAL_ERRORS) clearPending()
                // Not confirmed (e.g. the single-use nonce was already redeemed): fall through to restore, which needs no nonce.
            }
            val billing = gateway ?: return
            if (!billing.isConfigured || offeredSkus.isEmpty()) return
            if (now - lastSilentRestoreAt < SILENT_RESTORE_MS) return
            lastSilentRestoreAt = now
            for (purchase in billing.queryOwned(offeredSkus)) {
                val response = api.post("/v1/myket/restore", JSONObject().put("sku", purchase.sku).put("tokenId", purchase.token))
                if (response.isOffline) return
                val result = handleVerification(response, "")
                if (result is PurchaseUiState.Success) { clearPending(); _state.value = result; return }
            }
        } finally {
            recovering.set(false)
        }
    }

    private data class Pending(val sku: String, val token: String, val payload: String)

    private fun savePending(sku: String, token: String, payload: String) {
        pendingPrefs?.edit()?.putString(KEY_SKU, sku)?.putString(KEY_TOKEN, token)?.putString(KEY_PAYLOAD, payload)?.apply()
    }

    private fun readPending(): Pending? {
        val p = pendingPrefs ?: return null
        val sku = p.getString(KEY_SKU, null) ?: return null
        val token = p.getString(KEY_TOKEN, null) ?: return null
        return Pending(sku, token, p.getString(KEY_PAYLOAD, null).orEmpty())
    }

    private fun clearPending() { pendingPrefs?.edit()?.clear()?.apply() }

    private companion object {
        const val PENDING_PREFS = "pending_purchase"
        const val KEY_SKU = "sku"
        const val KEY_TOKEN = "token"
        const val KEY_PAYLOAD = "payload"
        const val PENDING_RETRY_MS = 15_000L
        const val SILENT_RESTORE_MS = 5 * 60_000L
        /** Server answers that will never succeed on retry for this purchase. */
        val TERMINAL_ERRORS = setOf("purchase_already_claimed", "transfer_limit_reached")
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
