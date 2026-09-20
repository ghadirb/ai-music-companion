package com.ghadirb.aimusic.billing

import android.app.Activity
import android.content.Intent
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
        if (!isConfigured) return false
        setup?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>().also { setup = it }
        try {
            val client = IabHelper(activity, BuildConfig.IAB_PUBLIC_KEY)
            helper = client
            client.enableDebugLogging(BuildConfig.DEBUG)
            client.startSetup { result -> deferred.complete(result.isSuccess) }
        } catch (e: Exception) {
            deferred.complete(false)
        }
        val ok = withTimeoutOrNull(SETUP_TIMEOUT_MS) { deferred.await() } ?: false
        if (!ok) setup = null // allow another attempt (e.g. the user installs/updates Myket)
        return ok
    }

    override suspend fun queryOwned(skus: List<String>): List<OwnedPurchase> {
        val client = helper?.takeIf { awaitReady() } ?: return emptyList()
        val result = CompletableDeferred<List<OwnedPurchase>>()
        try {
            client.queryInventoryAsync(true, skus) { inventoryResult, inventory ->
                if (!inventoryResult.isSuccess || inventory == null) {
                    result.complete(emptyList())
                } else {
                    result.complete(skus.mapNotNull { sku -> inventory.getPurchase(sku)?.let { OwnedPurchase(sku, it.token) } })
                }
            }
        } catch (e: Exception) {
            result.complete(emptyList())
        }
        return withTimeoutOrNull(QUERY_TIMEOUT_MS) { result.await() } ?: emptyList()
    }

    override suspend fun purchase(sku: String, developerPayload: String): PurchaseOutcome {
        val client = helper?.takeIf { awaitReady() } ?: return PurchaseOutcome.Failed("مایکت در دسترس نیست.")
        val outcome = CompletableDeferred<PurchaseOutcome>().also { pendingPurchase = it }
        try {
            client.launchPurchaseFlow(activity, sku, { result, purchase ->
                outcome.complete(
                    when {
                        result.isSuccess && purchase != null -> PurchaseOutcome.Purchased(purchase.sku, purchase.token, purchase.developerPayload ?: developerPayload)
                        result.response == USER_CANCELED -> PurchaseOutcome.Cancelled
                        else -> PurchaseOutcome.Failed(result.message ?: "خرید کامل نشد.")
                    }
                )
            }, developerPayload)
        } catch (e: Exception) {
            outcome.complete(PurchaseOutcome.Failed("شروع پرداخت ممکن نشد."))
        }
        return outcome.await().also { pendingPurchase = null }
    }

    /** Must be forwarded from Activity.onActivityResult. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        helper?.handleActivityResult(requestCode, resultCode, data) ?: false

    fun dispose() {
        pendingPurchase?.complete(PurchaseOutcome.Cancelled)
        helper?.dispose()
        helper = null
        setup = null
    }

    private companion object {
        const val SETUP_TIMEOUT_MS = 15_000L
        const val QUERY_TIMEOUT_MS = 15_000L
        const val USER_CANCELED = 1
    }
}
