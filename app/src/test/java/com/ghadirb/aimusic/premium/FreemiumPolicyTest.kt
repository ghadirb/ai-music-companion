package com.ghadirb.aimusic.premium

import com.ghadirb.aimusic.mix.MixType
import com.ghadirb.aimusic.ui.screens.online.SINGERS_ENABLED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FreemiumPolicyTest {
    private class MemoryStorage : QuotaStorage {
        val map = HashMap<String, Long>()
        override fun getLong(key: String, default: Long) = map[key] ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
    }

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val noon = 1_800_000_000_000L - (1_800_000_000_000L % day) + 12 * 3_600_000L

    @Test fun defaultFreeSetsAreValid() {
        val cfg = FreemiumConfig()
        assertTrue(cfg.freeOccasions.all { it.occasion })
        assertTrue(cfg.freePersonalMixes.none { it.occasion })
        assertTrue(cfg.freeOccasions.isNotEmpty() && cfg.freePersonalMixes.isNotEmpty())
        assertTrue(cfg.freeDailyOnlineDownloads > 0)
    }

    @Test fun everyMixIsEitherFreeOrNeedsTheMatchingPremiumFeature() {
        val cfg = FreemiumConfig()
        MixType.values().forEach { type ->
            val gate = FreemiumPolicy.gateFor(type, cfg)
            val free = type in cfg.freeOccasions || type in cfg.freePersonalMixes
            if (free) assertNull(gate)
            else assertEquals(if (type.occasion) PremiumFeature.ALL_OCCASION_MIXES else PremiumFeature.PERSONAL_MIXES, gate)
        }
    }

    @Test fun freeUserIsGatedOnLockedMixesAndPremiumUnlocksThem() {
        val locked = MixType.values().first { FreemiumPolicy.gateFor(it) != null }
        val gate = FreemiumPolicy.gateFor(locked)!!
        assertFalse(FeatureGate.isAllowed(gate, Entitlement.FREE, noon))
        val premium = Entitlement(Plan.PREMIUM, setOf("premium_monthly"), noon + 1000, EntitlementSource.VERIFIED_TOKEN)
        assertTrue(FeatureGate.isAllowed(gate, premium, noon))
    }

    @Test fun quotaCountsDownAndNeverGoesNegative() {
        val q = DailyDownloadQuota(MemoryStorage()) { utc }
        assertEquals(3, q.remaining(3, noon))
        repeat(3) { q.record(noon + it) }
        assertEquals(0, q.remaining(3, noon))
        q.record(noon)
        assertEquals(0, q.remaining(3, noon))
    }

    @Test fun quotaResetsOnTheNextLocalDay() {
        val q = DailyDownloadQuota(MemoryStorage()) { utc }
        repeat(3) { q.record(noon) }
        assertEquals(0, q.remaining(3, noon + 3_600_000L))
        assertEquals(3, q.remaining(3, noon + day))
    }

    @Test fun quotaUsesTheLocalDayNotUtc() {
        val tehran = TimeZone.getTimeZone("Asia/Tehran")
        val q = DailyDownloadQuota(MemoryStorage()) { tehran }
        val localMidnight = (noon + tehran.getOffset(noon)).floorDiv(day) * day - tehran.getOffset(noon)
        val lateEvening = localMidnight + day - 30 * 60_000L // 23:30 local
        q.record(lateEvening)
        assertEquals(0, q.remaining(1, lateEvening))
        assertEquals(1, q.remaining(1, lateEvening + 2 * 3_600_000L)) // crossed local midnight
    }

    @Test fun singerZipCollectionsStayDisabled() {
        assertFalse(SINGERS_ENABLED)
    }
}
