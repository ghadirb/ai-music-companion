package com.ghadirb.aimusic.billing

import android.app.Activity
import com.ghadirb.aimusic.BuildConfig
import ir.myket.billingclient.IabHelper
import ir.myket.billingclient.util.IabResult
import ir.myket.billingclient.util.Inventory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin, store-specific client for Myket's non-consumable premium product.
 *
 * A completed client callback is deliberately not treated as a premium
 * entitlement. The server must first verify its token with Myket and persist
 * the entitlement for the authenticated user. This prevents a modified APK
 * from simply granting paid features locally.
 */
class MyketBillingClient(private val activity: Activity) {
    private val _state = MutableStateFlow<MyketBillingState>(
        if (BuildConfig.IAB_PUBLIC_KEY.isBlank()) MyketBillingState.NotConfigured else MyketBillingState.Connecting
    )
    val state: StateFlow<MyketBillingState> = _state.asStateFlow()

    private var helper: IabHelper? = null

    init {
        if (BuildConfig.IAB_PUBLIC_KEY.isNotBlank()) connect()
    }

    private fun connect() {
        val client = IabHelper(activity, BuildConfig.IAB_PUBLIC_KEY)
        helper = client
        client.enableDebugLogging(BuildConfig.DEBUG)
        client.startSetup { result ->
            if (helper !== client) return@startSetup
            if (!result.isSuccess) {
                _state.value = MyketBillingState.Error(result.message ?: "Myket setup failed")
                return@startSetup
            }
            client.queryInventoryAsync(true, listOf(BuildConfig.MYKET_PREMIUM_SKU)) { inventoryResult, inventory ->
                if (helper !== client) return@queryInventoryAsync
                updateInventory(inventoryResult, inventory)
            }
        }
    }

    /**
     * Starts payment only after the app receives a one-time developer payload
     * from its authenticated backend. Never generate that payload in the APK.
     */
    fun launchPremiumPurchase(serverIssuedPayload: String) {
        val client = helper ?: return
        if (serverIssuedPayload.isBlank()) {
            _state.value = MyketBillingState.NeedsSecureCheckout
            return
        }
        _state.value = MyketBillingState.PurchaseInProgress
        client.launchPurchaseFlow(
            activity,
            BuildConfig.MYKET_PREMIUM_SKU,
            { result, purchase ->
                if (helper !== client) return@launchPurchaseFlow
                if (!result.isSuccess || purchase == null) {
                    _state.value = MyketBillingState.Error(result.message ?: "Purchase was not completed")
                } else {
                    // Send purchase.token, originalJson and signature to the authenticated Worker.
                    _state.value = MyketBillingState.AwaitingServerVerification(purchase.token)
                }
            },
            serverIssuedPayload
        )
    }

    private fun updateInventory(result: IabResult, inventory: Inventory?) {
        if (!result.isSuccess || inventory == null) {
            _state.value = MyketBillingState.Error(result.message ?: "Could not restore Myket purchases")
            return
        }
        val owned = inventory.getPurchase(BuildConfig.MYKET_PREMIUM_SKU) != null
        _state.value = if (owned) MyketBillingState.RestoreRequiresServerVerification else MyketBillingState.Ready
    }

    fun dispose() {
        helper?.dispose()
        helper = null
    }
}

sealed interface MyketBillingState {
    data object NotConfigured : MyketBillingState
    data object Connecting : MyketBillingState
    data object Ready : MyketBillingState
    data object PurchaseInProgress : MyketBillingState
    data object NeedsSecureCheckout : MyketBillingState
    data object RestoreRequiresServerVerification : MyketBillingState
    data class AwaitingServerVerification(val purchaseToken: String) : MyketBillingState
    data class Error(val message: String) : MyketBillingState
}
