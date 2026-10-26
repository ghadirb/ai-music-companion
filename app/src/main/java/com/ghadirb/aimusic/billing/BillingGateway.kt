package com.ghadirb.aimusic.billing

/** Store-agnostic billing abstraction (Myket today; Google Play or others can implement the same interface). */
interface BillingGateway {
    /** False when the store credentials (e.g. the Myket IAB public key) are not configured in this build. */
    val isConfigured: Boolean

    /** Connects to the store; false if the store app is missing or setup failed. */
    suspend fun awaitReady(): Boolean

    /**
     * Purchases the user already owns in the store (used by "Restore purchase").
     * A connection/SDK/timeout problem is reported as [OwnedResult.Unavailable] and must never be treated as
     * "the user owns nothing"; only [OwnedResult.Success] with an empty list means the store answered "no purchases".
     * [freshConnection] discards any cached store connection first (used to double-check an empty answer).
     */
    suspend fun queryOwned(skus: List<String>, freshConnection: Boolean = false): OwnedResult

    /** Starts checkout. [developerPayload] must be the server-issued single-use payload. */
    suspend fun purchase(sku: String, developerPayload: String): PurchaseOutcome
}

data class OwnedPurchase(val sku: String, val token: String)

enum class BillingFailure {
    /** The IAB public key is missing in this build. */
    NOT_CONFIGURED,
    /** The Myket app is not installed (or not visible) on this device. */
    STORE_NOT_INSTALLED,
    /** Myket is installed but the billing service did not connect/answer in time. */
    STORE_UNAVAILABLE,
    /** Connected, but the inventory request failed, threw, or timed out. */
    QUERY_FAILED,
    /** Another billing operation (e.g. a purchase) is already running. */
    BUSY
}

sealed interface OwnedResult {
    /** The store answered. [purchases] may legitimately be empty. */
    data class Success(val purchases: List<OwnedPurchase>) : OwnedResult
    /** The store could not be asked or did not answer; says nothing about what the user owns. */
    data class Unavailable(val reason: BillingFailure) : OwnedResult
}

sealed interface PurchaseOutcome {
    data class Purchased(val sku: String, val token: String, val developerPayload: String) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}
