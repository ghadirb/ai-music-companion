package com.ghadirb.aimusic.billing

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ghadirb.aimusic.BuildConfig
import ir.myket.billingclient.IabHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Myket implementation of [BillingGateway]. A successful store callback is NOT a premium entitlement:
 * the token is returned to [PurchaseRepository], which has the gateway server verify it with Myket.
 * The IAB public key comes from a Gradle property (it is a public key, not a secret).
 */
class MyketBillingGateway(private val activity: Activity) : BillingGateway {

    override val isConfigured: Boolean = BuildConfig.IAB_PUBLIC_KEY.isNotBlank()

    private var helper: IabHelper? = null
    private var setup: CompletableDeferred<Boolean>? = null
    private var pendingPurchase: CompletableDeferred<PurchaseOutcome>? = null

    /** One store operation at a time: the SDK throws if a second async call starts while one is running. */
    private val opLock = Mutex()

    override suspend fun awaitReady(): Boolean {
        if (!isConfigured) {
            Log.w(TAG, "awaitReady: skipped — BuildConfig.IAB_PUBLIC_KEY is blank in this build")
            return false
        }
        setup?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>().also { setup = it }
        try {
            logStoreAvailability()
            val client = IabHelper(activity, BuildConfig.IAB_PUBLIC_KEY)
            helper = client
            client.enableDebugLogging(true) // temporary: on to diagnose the Myket rejection; the library only logs bind/response state, no tokens
            client.startSetup { result ->
                if (!result.isSuccess) {
                    // No token/PII here — just the store's own response code, safe to log in any build.
                    Log.w(TAG, "startSetup failed: response=${result.response} message=${result.message}")
                }
                deferred.complete(result.isSuccess)
            }
        } catch (e: Exception) {
            Log.e(TAG, "awaitReady: constructing/starting IabHelper threw", e)
            deferred.complete(false)
        }
        val ok = withTimeoutOrNull(SETUP_TIMEOUT_MS) { deferred.await() } ?: false
        if (!ok) {
            Log.w(TAG, "awaitReady: setup did not finish successfully within ${SETUP_TIMEOUT_MS}ms")
            // The SDK may be waiting for a Service/Broadcast response that never arrives.
            // Dispose this incomplete connection before retrying; otherwise later taps can
            // leave orphaned bindings and make the original failure harder to diagnose.
            helper?.dispose()
            helper = null
            setup = null // allow another attempt (e.g. the user installs/updates Myket)
        }
        return ok
    }

    override suspend fun queryOwned(skus: List<String>, freshConnection: Boolean): OwnedResult {
        if (!isConfigured) return OwnedResult.Unavailable(BillingFailure.NOT_CONFIGURED)
        if (!opLock.tryLock()) {
            Log.i(TAG, "queryOwned: skipped, another billing operation is running")
            return OwnedResult.Unavailable(BillingFailure.BUSY)
        }
        try {
            if (freshConnection) {
                Log.i(TAG, "queryOwned: discarding the cached store connection")
                resetConnection()
            }
            var failure = BillingFailure.STORE_UNAVAILABLE
            // Bounded: attempt 1 re-uses a live connection, attempt 2 (only if 1 failed) rebuilds it. Never more than two.
            for (attempt in 1..MAX_ATTEMPTS) {
                Log.i(TAG, "queryOwned: attempt=$attempt connecting")
                if (!awaitReady()) {
                    failure = if (isStoreInstalled()) BillingFailure.STORE_UNAVAILABLE else BillingFailure.STORE_NOT_INSTALLED
                    Log.w(TAG, "queryOwned: attempt=$attempt connection failed reason=$failure")
                    if (failure == BillingFailure.STORE_NOT_INSTALLED) break
                    resetConnection()
                    continue
                }
                Log.i(TAG, "queryOwned: attempt=$attempt connected, querying inventory")
                val owned = runInventoryQuery(skus)
                if (owned != null) {
                    Log.i(TAG, "queryOwned: attempt=$attempt answered, ownedCount=${owned.size}")
                    return OwnedResult.Success(owned)
                }
                failure = BillingFailure.QUERY_FAILED
                Log.w(TAG, "queryOwned: attempt=$attempt inventory query failed, rebuilding the connection")
                resetConnection()
            }
            return OwnedResult.Unavailable(failure)
        } finally {
            opLock.unlock()
        }
    }

    /** Null = the query failed, threw or timed out (NOT "nothing owned"); an empty list = the store answered with no purchases. */
    private suspend fun runInventoryQuery(skus: List<String>): List<OwnedPurchase>? {
        val client = helper ?: return null
        val result = CompletableDeferred<List<OwnedPurchase>?>()
        try {
            client.queryInventoryAsync(true, skus) { inventoryResult, inventory ->
                if (!inventoryResult.isSuccess || inventory == null) {
                    Log.w(TAG, "queryInventoryAsync failed: response=${inventoryResult.response} message=${inventoryResult.message}")
                    result.complete(null)
                } else {
                    result.complete(skus.mapNotNull { sku -> inventory.getPurchase(sku)?.let { OwnedPurchase(sku, it.token) } })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryOwned: queryInventoryAsync threw", e)
            result.complete(null)
        }
        val answer = withTimeoutOrNull(QUERY_TIMEOUT_MS) { result.await() }
        if (answer == null) Log.w(TAG, "queryOwned: no usable answer from the store (failure or ${QUERY_TIMEOUT_MS}ms timeout)")
        return answer
    }

    /** Drops the current store connection so the next call builds a fresh one. Does not touch a pending purchase. */
    private fun resetConnection() {
        try { helper?.dispose() } catch (e: Exception) { Log.w(TAG, "resetConnection: dispose threw (${e.javaClass.simpleName})") }
        helper = null
        setup = null
    }

    private fun isStoreInstalled(): Boolean = try {
        activity.packageManager.getPackageInfo(MYKET_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        true // can't tell: assume installed so the user sees the generic "try again" message, not "not installed"
    }

    override suspend fun purchase(sku: String, developerPayload: String): PurchaseOutcome =
        opLock.withLock { purchaseLocked(sku, developerPayload) }

    private suspend fun purchaseLocked(sku: String, developerPayload: String): PurchaseOutcome {
        // Same fix as queryOwned(): must call awaitReady() unconditionally, not inside
        // helper?.takeIf{}, or a still-null `helper` skips the connection attempt entirely.
        val ready = awaitReady()
        val client = helper.takeIf { ready } ?: run {
            Log.w(TAG, "purchase($sku): not attempted — awaitReady() returned false, see prior log line for why")
            return PurchaseOutcome.Failed("مایکت در دسترس نیست.")
        }
        val outcome = CompletableDeferred<PurchaseOutcome>().also { pendingPurchase = it }
        try {
            client.launchPurchaseFlow(activity, sku, { result, purchase ->
                if (!result.isSuccess) {
                    Log.w(TAG, "purchase($sku) result: response=${result.response} message=${result.message}")
                }
                outcome.complete(
                    when {
                        result.isSuccess && purchase != null -> PurchaseOutcome.Purchased(purchase.sku, purchase.token, purchase.developerPayload ?: developerPayload)
                        result.response == USER_CANCELED || result.response == IABHELPER_USER_CANCELLED -> PurchaseOutcome.Cancelled
                        else -> PurchaseOutcome.Failed(result.message ?: "خرید کامل نشد.")
                    }
                )
            }, developerPayload)
        } catch (e: Exception) {
            Log.e(TAG, "purchase($sku): launchPurchaseFlow threw", e)
            outcome.complete(PurchaseOutcome.Failed("شروع پرداخت ممکن نشد."))
        }
        return outcome.await().also { pendingPurchase = null }
    }

    fun dispose() {
        pendingPurchase?.complete(PurchaseOutcome.Cancelled)
        helper?.dispose()
        helper = null
        setup = null
    }

    /**
     * Logs only device/package discovery state — never the IAB key, purchase token, or user data.
     * This distinguishes a missing/invisible Myket installation from a Myket-side setup response
     * that is blocked by the app/product configuration in the developer panel.
     */
    private fun logStoreAvailability() {
        val packageManager = activity.packageManager
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(MYKET_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") packageManager.getPackageInfo(MYKET_PACKAGE, 0)
            }
            val serviceIntent = Intent(MYKET_BIND_ACTION).setPackage(MYKET_PACKAGE)
            val matchingServices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentServices(
                    serviceIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
                )
            } else {
                @Suppress("DEPRECATION") packageManager.queryIntentServices(
                    serviceIntent,
                    PackageManager.MATCH_DISABLED_COMPONENTS
                )
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            }
            Log.i(TAG, "store diagnostic: installed=true version=$versionCode services=${matchingServices.size}")
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(TAG, "store diagnostic: installed=false")
        } catch (error: Exception) {
            Log.w(TAG, "store diagnostic: package check failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TAG = "MyketBilling"
        const val MYKET_PACKAGE = "ir.mservices.market"
        const val MYKET_BIND_ACTION = "ir.mservices.market.InAppBillingService.BIND"
        const val SETUP_TIMEOUT_MS = 15_000L
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_ATTEMPTS = 2
        const val USER_CANCELED = 1
        // IabHelper.IABHELPER_USER_CANCELLED: what Myket actually returns when the user backs out (seen in logcat as -1005).
        const val IABHELPER_USER_CANCELLED = -1005
    }
}
