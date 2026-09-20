package com.ghadirb.aimusic.premium

import android.content.Context
import com.ghadirb.aimusic.cloud.CloudApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Single source of truth for the user's plan. Premium comes only from the gateway:
 * a signed token is cached and re-verified locally (works offline until it expires), and every online
 * refresh re-reads the server state, so a refund/expiry/transfer downgrades the user automatically.
 */
class EntitlementRepository(
    context: Context,
    private val api: CloudApi,
    private val verifier: EntitlementTokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed interface RefreshResult {
        data object Updated : RefreshResult
        data object Offline : RefreshResult
        data class Failed(val error: String?) : RefreshResult
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _entitlement = MutableStateFlow(loadCached())
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private val _quota = MutableStateFlow<AiQuota?>(null)
    val quota: StateFlow<AiQuota?> = _quota.asStateFlow()

    private fun loadCached(): Entitlement {
        val token = prefs.getString(KEY_TOKEN, null) ?: return Entitlement.FREE
        return verifier.verify(token, api.subject(), clock()) ?: Entitlement.FREE.also { prefs.edit().remove(KEY_TOKEN).apply() }
    }

    /** True when the cached token is missing or will expire within [withinMs]. */
    fun needsRefresh(withinMs: Long = 2 * 24 * 3600_000L): Boolean {
        val current = _entitlement.value
        return current.plan == Plan.PREMIUM && (current.expiresAtMs?.let { it - clock() < withinMs } ?: false)
    }

    suspend fun refresh(): RefreshResult {
        val response = api.post("/v1/entitlements/me", JSONObject())
        if (response.isOffline) return RefreshResult.Offline
        if (!response.isSuccess) return RefreshResult.Failed(response.error)
        applyServerState(response.body)
        return RefreshResult.Updated
    }

    /** Applies an `/entitlements/me`, `/myket/verify` or `/myket/restore` response. */
    fun applyServerState(body: JSONObject) {
        if (body.has("aiDailyLimit")) {
            _quota.value = AiQuota(body.optInt("aiDailyLimit"), body.optInt("aiDailyRemaining"))
        }
        if (!body.optBoolean("premium", false)) {
            prefs.edit().remove(KEY_TOKEN).apply()
            _entitlement.value = Entitlement.FREE
            return
        }
        val token = if (body.isNull("entitlementToken")) null else body.optString("entitlementToken").takeIf { it.isNotBlank() }
        val verified = token?.let { verifier.verify(it, api.subject(), clock()) }
        if (verified != null) {
            prefs.edit().putString(KEY_TOKEN, token).apply()
            _entitlement.value = verified
        } else {
            // Server says premium but there is no verifiable token (e.g. signing key not configured yet):
            // honour it for this session only, never persist an unverifiable claim.
            val skus = body.optJSONArray("skus")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val expires = if (body.isNull("expiresAt")) null else body.optLong("expiresAt").takeIf { it > 0 }
            _entitlement.value = Entitlement(Plan.PREMIUM, skus, expires, EntitlementSource.SERVER_SESSION)
        }
    }

    fun isAllowed(feature: PremiumFeature): Boolean = FeatureGate.isAllowed(feature, _entitlement.value, clock())

    private companion object {
        const val PREFS = "entitlement_cache"
        const val KEY_TOKEN = "token"
    }
}
