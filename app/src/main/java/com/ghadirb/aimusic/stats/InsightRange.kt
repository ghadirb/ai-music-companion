package com.ghadirb.aimusic.stats

import java.util.Calendar
import java.util.TimeZone

enum class InsightRange(val labelFa: String) {
    TODAY("امروز"), WEEK("این هفته"), MONTH("این ماه"), YEAR("امسال"), ALL("همیشه")
}

data class PeriodBounds(val startMs: Long, val endMs: Long) {
    fun contains(t: Long) = t in startMs until endMs
}

/**
 * Calendar-aware periods. With [persian] = true, weeks start on Saturday and months/years follow the Jalali calendar;
 * otherwise weeks start on Monday and months/years are Gregorian. Periods are half-open [start, end).
 */
object InsightPeriods {
    private fun startOfDay(timeMs: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

    private fun addDays(timeMs: Long, days: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply { timeInMillis = timeMs; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

    fun current(range: InsightRange, nowMs: Long, zone: TimeZone, persian: Boolean): PeriodBounds {
        val day = startOfDay(nowMs, zone)
        val dayStart = day.timeInMillis
        return when (range) {
            InsightRange.ALL -> PeriodBounds(0L, Long.MAX_VALUE)
            InsightRange.TODAY -> PeriodBounds(dayStart, addDays(dayStart, 1, zone))
            InsightRange.WEEK -> {
                val firstDay = if (persian) Calendar.SATURDAY else Calendar.MONDAY
                val back = (day.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7
                val start = addDays(dayStart, -back, zone)
                PeriodBounds(start, addDays(start, 7, zone))
            }
            InsightRange.MONTH -> if (persian) {
                val j = JalaliCalendar.jalaliOf(nowMs, zone)
                PeriodBounds(JalaliCalendar.startOfJalaliDay(j[0], j[1], 1, zone), nextJalaliMonthStart(j[0], j[1], zone))
            } else {
                val start = Calendar.getInstance(zone).apply { timeInMillis = dayStart; set(Calendar.DAY_OF_MONTH, 1) }
                PeriodBounds(start.timeInMillis, Calendar.getInstance(zone).apply { timeInMillis = start.timeInMillis; add(Calendar.MONTH, 1) }.timeInMillis)
            }
            InsightRange.YEAR -> if (persian) {
                val j = JalaliCalendar.jalaliOf(nowMs, zone)
                PeriodBounds(JalaliCalendar.startOfJalaliDay(j[0], 1, 1, zone), JalaliCalendar.startOfJalaliDay(j[0] + 1, 1, 1, zone))
            } else {
                val start = Calendar.getInstance(zone).apply { timeInMillis = dayStart; set(Calendar.DAY_OF_YEAR, 1) }
                PeriodBounds(start.timeInMillis, Calendar.getInstance(zone).apply { timeInMillis = start.timeInMillis; add(Calendar.YEAR, 1) }.timeInMillis)
            }
        }
    }

    /** The period immediately before [current] (for "compared with last week/month"); null for ALL. */
    fun previous(range: InsightRange, nowMs: Long, zone: TimeZone, persian: Boolean): PeriodBounds? {
        if (range == InsightRange.ALL) return null
        val cur = current(range, nowMs, zone, persian)
        // one millisecond before the current period lies inside the previous period
        return current(range, cur.startMs - 1, zone, persian)
    }

    private fun nextJalaliMonthStart(jy: Int, jm: Int, zone: TimeZone): Long =
        if (jm == 12) JalaliCalendar.startOfJalaliDay(jy + 1, 1, 1, zone) else JalaliCalendar.startOfJalaliDay(jy, jm + 1, 1, zone)
}
