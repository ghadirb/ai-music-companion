package com.ghadirb.aimusic.music

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A Jalali (Solar Hijri) calendar date. The music sites organise their folders by Jalali date. */
data class JalaliDate(val jy: Int, val jm: Int, val jd: Int) {
    /** "1405-07-19" */
    val key: String get() = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", jy, jm, jd)
}

/** One day folder: the Gregorian date (sortable) and the Jalali date the site uses in its path. */
data class FolderDay(val gregorian: LocalDate, val jalali: JalaliDate)

/**
 * Gregorian -> Jalali conversion (33-year-cycle algorithm, same as the Worker's jalali.ts / jalaali-js, MIT).
 * Pure Kotlin on purpose: android.icu is not available in JVM unit tests, and "today" must be computed in Asia/Tehran.
 */
object Jalali {
    private val BREAKS = intArrayOf(-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178)
    private val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

    private fun mod(a: Int, b: Int) = a - (a / b) * b

    private class Cal(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int): Cal {
        val bl = BREAKS.size
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        require(jy >= jp && jy < BREAKS[bl - 1]) { "Invalid Jalali year $jy" }
        for (i in 1 until bl) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += (jump / 33) * 8 + mod(jump, 33) / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += (n / 33) * 8 + (mod(n, 33) + 3) / 4
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
        val leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + ((jump + 4) / 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return Cal(leap, gy, march)
    }

    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = ((gy + (gm - 8) / 6 + 100100) * 1461) / 4 + (153 * mod(gm + 9, 12) + 2) / 5 + gd - 34840408
        d = d - (((gy + 100100 + (gm - 8) / 6) / 100) * 3) / 4 + 752
        return d
    }

    private fun d2gYear(jdn: Int): Int {
        var j = 4 * jdn + 139361631
        j += (((4 * jdn + 183187720) / 146097) * 3) / 4 * 4 - 3908
        val i = (mod(j, 1461) / 4) * 5 + 308
        val gm = mod(i / 153, 12) + 1
        return j / 1461 - 100100 + (8 - gm) / 6
    }

    fun fromGregorian(gy: Int, gm: Int, gd: Int): JalaliDate {
        val jdn = g2d(gy, gm, gd)
        var jy = d2gYear(jdn) - 621
        val r = jalCal(jy)
        val jdn1f = g2d(r.gy, 3, r.march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) return JalaliDate(jy, 1 + k / 31, mod(k, 31) + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return JalaliDate(jy, 7 + k / 30, mod(k, 30) + 1)
    }

    fun fromGregorian(date: LocalDate): JalaliDate = fromGregorian(date.year, date.monthValue, date.dayOfMonth)

    /** Today's date in Asia/Tehran, then the previous days (newest first). Walks the Gregorian calendar, so month/year/leap edges are exact. */
    fun recentDays(nowMs: Long, days: Int): List<FolderDay> {
        val today = Instant.ofEpochMilli(nowMs).atZone(TEHRAN).toLocalDate()
        return (0 until days).map { i ->
            val g = today.minusDays(i.toLong())
            FolderDay(g, fromGregorian(g))
        }
    }
}
