package com.ghadirb.aimusic.billing

/** Store-agnostic billing abstraction (Myket today; Google Play or others can implement the same interface). */
interface BillingGateway {
    /** False when the store credentials (e.g. the Myket IAB public key) are not configured in this build. */
    val isConfigured: Boolean

    /** Connects to the store; false if the store app is missing or setup failed. */
    suspend fun awaitReady(): Boolean

    /** Purchases the user already owns in the store (used by "Restore purchase"). */
    suspend fun queryOwned(skus: List<String>): List<OwnedPurchase>

    /** Starts checkout. [developerPayload] must be the server-issued single-use payload. */
    suspend fun purchase(sku: String, developerPayload: String): PurchaseOutcome
}

data class OwnedPurchase(val sku: String, val token: String)

sealed interface PurchaseOutcome {
    data class Purchased(val sku: String, val token: String, val developerPayload: String) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}
