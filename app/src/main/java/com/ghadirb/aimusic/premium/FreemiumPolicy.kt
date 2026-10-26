package com.ghadirb.aimusic.premium

import com.ghadirb.aimusic.mix.MixType
import java.util.TimeZone

/**
 * Free-vs-Premium rules for discovery features. Core listening (library playback, online STREAMING,
 * browsing the new-songs list, previews of locked mixes) is always free; what costs money to run or
 * adds personalised value is Premium:
 *  - online downloads beyond a small daily allowance,
 *  - the full set of "for every occasion" mixes (a few stay free),
 *  - the personalised personal mixes (basic favourites / recently-loved stay free).
 *
 * The values are defaults; [FreemiumPolicy.config] can be replaced by a server-provided config
 * without an app update.
 */
data class FreemiumConfig(
    val freeDailyOnlineDownloads: Int = 3,
    val freeOccasions: Set<MixType> = setOf(MixType.WORKOUT, MixType.DRIVING, MixType.CHILL),
    val freePersonalMixes: Set<MixType> = setOf(MixType.MY_FAVORITES, MixType.RECENTLY_LOVED),
    /** How many tracks of a locked mix are shown as a teaser. */
    val lockedPreviewCount: Int = 3
)

object FreemiumPolicy {
    @Volatile var config: FreemiumConfig = FreemiumConfig()

    /** The premium feature a mix needs, or null when the mix is free for everyone. */
    fun gateFor(type: MixType, cfg: FreemiumConfig = config): PremiumFeature? = when {
        type.occasion -> if (type in cfg.freeOccasions) null else PremiumFeature.ALL_OCCASION_MIXES
        else -> if (type in cfg.freePersonalMixes) null else PremiumFeature.PERSONAL_MIXES
    }
}

/** Minimal key/value storage so the quota logic is plain JVM (SharedPreferences is just one implementation). */
interface QuotaStorage {
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

/**
 * Soft per-day allowance of free online downloads, stored on the device (local day, so it resets at
 * the user's midnight). It is a product nudge, not a security boundary: Premium itself is verified
 * by the signed entitlement token.
 */
class DailyDownloadQuota(
    private val storage: QuotaStorage,
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {
    private fun dayKey(nowMs: Long): Long = (nowMs + zone().getOffset(nowMs)).floorDiv(DAY_MS)

    fun used(nowMs: Long): Int =
        if (storage.getLong(KEY_DAY, -1L) == dayKey(nowMs)) storage.getLong(KEY_COUNT, 0L).toInt() else 0

    fun remaining(limit: Int, nowMs: Long): Int = (limit - used(nowMs)).coerceAtLeast(0)

    /** Call once per successfully completed free download. */
    fun record(nowMs: Long) {
        val day = dayKey(nowMs)
        storage.putLong(KEY_COUNT, used(nowMs) + 1L)
        storage.putLong(KEY_DAY, day)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val KEY_DAY = "day"
        const val KEY_COUNT = "count"
    }
}
