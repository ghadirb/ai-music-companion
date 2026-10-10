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

    sealed interface TrialResult {
        data object Started : TrialResult
        data object Offline : TrialResult
        data object NotAvailable : TrialResult
        data class Failed(val error: String?) : TrialResult
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _entitlement = MutableStateFlow(loadCached())
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /** Whether the one-time free trial can still be started (server is the authority; default true until told otherwise). */
    private val _trial = MutableStateFlow(
        TrialInfo(
            eligible = prefs.getBoolean(KEY_TRIAL_ELIGIBLE, true),
            expiresAtMs = prefs.getLong(KEY_TRIAL_EXPIRES, 0L).takeIf { it > 0 }
        )
    )
    val trial: StateFlow<TrialInfo> = _trial.asStateFlow()

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

    /**
     * Starts the one-time free trial. Only the server can grant it (and only once per install); premium
     * switches on from the verified response/token, never from this call succeeding by itself.
     */
    suspend fun startTrial(): TrialResult {
        val response = api.post("/v1/trial/start", JSONObject())
        if (response.isOffline) return TrialResult.Offline
        if (response.code == 503) return TrialResult.NotAvailable
        if (!response.isSuccess) return TrialResult.Failed(response.error)
        applyServerState(response.body)
        return if (_entitlement.value.isPremiumAt(clock())) TrialResult.Started else TrialResult.Failed("trial_not_granted")
    }

    /** Applies an `/entitlements/me`, `/myket/verify`, `/myket/restore` or `/trial/start` response. */
    fun applyServerState(body: JSONObject) {
        body.optJSONObject("trial")?.let { t ->
            val expires = if (t.isNull("expiresAt")) null else t.optLong("expiresAt").takeIf { it > 0 }
            val info = TrialInfo(eligible = t.optBoolean("eligible", true), active = t.optBoolean("active", false), expiresAtMs = expires)
            prefs.edit().putBoolean(KEY_TRIAL_ELIGIBLE, info.eligible).putLong(KEY_TRIAL_EXPIRES, info.expiresAtMs ?: 0L).apply()
            _trial.value = info
        }
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
        const val KEY_TRIAL_ELIGIBLE = "trial_eligible"
        const val KEY_TRIAL_EXPIRES = "trial_expires_at"
    }
}
