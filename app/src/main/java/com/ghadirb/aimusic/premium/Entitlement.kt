package com.ghadirb.aimusic.premium

enum class Plan { FREE, PREMIUM }

/** Where the current premium state came from (only VERIFIED_TOKEN survives an app restart / offline use). */
enum class EntitlementSource { NONE, VERIFIED_TOKEN, SERVER_SESSION }

/**
 * What the user is entitled to. It is only ever built from a server-verified purchase:
 *  - an ES256 token signed by the gateway and verified locally with the embedded PUBLIC key, or
 *  - a fresh authenticated server response (this session only, never persisted).
 * There is no "isPremium = true" switch anywhere in the app.
 */
data class Entitlement(
    val plan: Plan = Plan.FREE,
    val skus: Set<String> = emptySet(),
    val expiresAtMs: Long? = null,
    val source: EntitlementSource = EntitlementSource.NONE
) {
    fun isPremiumAt(nowMs: Long): Boolean = plan == Plan.PREMIUM && (expiresAtMs == null || nowMs < expiresAtMs)

    companion object { val FREE = Entitlement() }
}

/** Server-reported daily AI quota (informational; the server enforces it). */
data class AiQuota(val limit: Int, val remaining: Int)

enum class PremiumFeature(val titleFa: String, val descriptionFa: String) {
    AI_DJ("AI DJ", "درخواست طبیعی مثل «یک ساعت موسیقی آرام برای مطالعه» را به یک پلی‌لیست از کتابخانهٔ خودتان تبدیل می‌کند."),
    NATURAL_LANGUAGE_SEARCH("جست‌وجوی زبان طبیعی", "با جمله‌های فارسی یا انگلیسی آهنگ‌های مناسب را از کتابخانه پیدا کنید."),
    SMART_PLAYLIST_GENERATION("ساخت پلی‌لیست هوشمند", "پلی‌لیست‌های آماده برای مطالعه، رانندگی شبانه، آهنگ‌های کم‌شنیده و مشابه آهنگ فعلی."),
    SMART_RADIO("رادیوی هوشمند", "از یک آهنگ، خواننده، آلبوم، پلی‌لیست، علاقه‌مندی‌ها یا میکس، رادیویی بی‌پایان از کتابخانهٔ خودتان بسازید که به‌مرور با سلیقه‌تان هماهنگ می‌شود."),
    TASTE_EVOLUTION("تکامل سلیقه", "ببینید سلیقهٔ موسیقی‌تان در هفته و ماه اخیر چطور تغییر کرده: سبک‌ها، خواننده‌های جدید، آهنگ‌های کشف‌شده."),
    ADVANCED_RECOMMENDATION("تنظیم پیشنهادها", "میزان کشف موسیقی جدید در برابر آهنگ‌های آشنا را تنظیم کنید."),
    ADVANCED_REDISCOVER("کشف مجدد پیشرفته", "آهنگ‌هایی که مدت‌هاست نشنیده‌اید را با فاصلهٔ زمانی دلخواه پیدا کنید."),
    ADVANCED_INSIGHTS("بینش‌های شنیداری", "تحلیل سلیقه: BPM، محدودهٔ انرژی، ساعت‌های اوج شنیدن و رفتار رد کردن."),
    ADVANCED_STATISTICS("آمار پیشرفته", "ژانرها، حال‌وهوا، روزهای هفته و روند شنیدن شما."),
    ONLINE_LYRICS("متن آنلاین (بعداً)", "متن آهنگ از سرویس دارای مجوز؛ به‌محض افزوده‌شدن فعال می‌شود.")
}

/**
 * The ONE place that decides which plan unlocks what. To move a feature between Free and Premium
 * change its line here; no other code needs to change.
 */
object FeatureGate {
    private val requiredPlan: Map<PremiumFeature, Plan> = mapOf(
        PremiumFeature.AI_DJ to Plan.PREMIUM,
        PremiumFeature.NATURAL_LANGUAGE_SEARCH to Plan.PREMIUM,
        PremiumFeature.SMART_PLAYLIST_GENERATION to Plan.PREMIUM,
        PremiumFeature.SMART_RADIO to Plan.PREMIUM,
        PremiumFeature.TASTE_EVOLUTION to Plan.PREMIUM,
        PremiumFeature.ADVANCED_RECOMMENDATION to Plan.PREMIUM,
        PremiumFeature.ADVANCED_REDISCOVER to Plan.PREMIUM,
        PremiumFeature.ADVANCED_INSIGHTS to Plan.PREMIUM,
        PremiumFeature.ADVANCED_STATISTICS to Plan.PREMIUM,
        PremiumFeature.ONLINE_LYRICS to Plan.PREMIUM
    )

    fun requiredPlan(feature: PremiumFeature): Plan = requiredPlan[feature] ?: Plan.PREMIUM

    fun isAllowed(feature: PremiumFeature, entitlement: Entitlement, nowMs: Long = System.currentTimeMillis()): Boolean =
        requiredPlan(feature) == Plan.FREE || entitlement.isPremiumAt(nowMs)

    val premiumFeatures: List<PremiumFeature>
        get() = PremiumFeature.values().filter { requiredPlan(it) == Plan.PREMIUM }
}
