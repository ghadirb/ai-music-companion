package com.ghadirb.aimusic.billing

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ghadirb.aimusic.BuildConfig
import ir.myket.billingclient.IabHelper
import kotlinx.coroutines.CompletableDeferred
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

    override suspend fun queryOwned(skus: List<String>): List<OwnedPurchase> {
        // awaitReady() is what actually sets `helper` — call it unconditionally first.
        // `helper?.takeIf { awaitReady() }` looked equivalent but isn't: Kotlin's safe-call
        // skips the takeIf lambda entirely whenever `helper` is still null, so awaitReady()
        // (and the connection attempt inside it) would never run on the very first call.
        val ready = awaitReady()
        val client = helper.takeIf { ready } ?: return emptyList()
        val result = CompletableDeferred<List<OwnedPurchase>>()
        try {
            client.queryInventoryAsync(true, skus) { inventoryResult, inventory ->
                if (!inventoryResult.isSuccess || inventory == null) {
                    Log.w(TAG, "queryInventoryAsync failed: response=${inventoryResult.response} message=${inventoryResult.message}")
                    result.complete(emptyList())
                } else {
                    result.complete(skus.mapNotNull { sku -> inventory.getPurchase(sku)?.let { OwnedPurchase(sku, it.token) } })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryOwned: queryInventoryAsync threw", e)
            result.complete(emptyList())
        }
        return withTimeoutOrNull(QUERY_TIMEOUT_MS) { result.await() } ?: emptyList()
    }

    override suspend fun purchase(sku: String, developerPayload: String): PurchaseOutcome {
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
                        result.response == USER_CANCELED -> PurchaseOutcome.Cancelled
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
        const val USER_CANCELED = 1
    }
}
