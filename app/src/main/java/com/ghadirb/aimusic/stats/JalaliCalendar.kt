package com.ghadirb.aimusic.stats

import java.util.Calendar
import java.util.TimeZone

/** Pure Gregorian <-> Jalali (Persian) date conversion, so week/month/year insights match the Iranian calendar. */
object JalaliCalendar {
    private val MONTH_NAMES = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    fun monthName(month: Int): String = MONTH_NAMES.getOrElse(month - 1) { "" }

    /** @return [year, month, day] in the Jalali calendar. */
    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val cumulative = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + cumulative[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 } else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        return intArrayOf(jy, jm, jd)
    }

    /** @return [year, month, day] in the Gregorian calendar. */
    fun toGregorian(jy: Int, jm: Int, jd: Int): IntArray {
        val jy2 = jy + 1595
        var days = -355668 + (365 * jy2) + ((jy2 / 33) * 8) + (((jy2 % 33) + 3) / 4) + jd +
            (if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186)
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val monthLengths = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13 && gd > monthLengths[gm]) { gd -= monthLengths[gm]; gm++ }
        return intArrayOf(gy, gm, gd)
    }

    fun jalaliOf(timeMs: Long, zone: TimeZone): IntArray {
        val c = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        return toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Local midnight of a Jalali date. */
    fun startOfJalaliDay(jy: Int, jm: Int, jd: Int, zone: TimeZone): Long {
        val g = toGregorian(jy, jm, jd)
        return Calendar.getInstance(zone).apply {
            clear()
            set(g[0], g[1] - 1, g[2], 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
