package com.ghadirb.aimusic.stats

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** Current vs previous value of one metric. [percent] is null when there is nothing to compare with. */
data class Delta(val current: Long, val previous: Long) {
    val percent: Int? get() = if (previous <= 0L) null else ((current - previous) * 100.0 / previous).roundToInt()
}

data class PeriodComparison(val listenTime: Delta, val plays: Delta, val uniqueTracks: Delta)

/** Consecutive-day listening streaks over the whole history. */
data class Streak(val current: Int, val best: Int, val activeDays: Int)

/** Pure helpers behind the improved statistics screen (JVM unit-tested). */
object StatsInsights {
    private const val DAY_MS = 86_400_000L

    fun compare(current: StatsSummary, previous: StatsSummary): PeriodComparison = PeriodComparison(
        listenTime = Delta(current.listenMs, previous.listenMs),
        plays = Delta(current.completedPlays.toLong(), previous.completedPlays.toLong()),
        uniqueTracks = Delta(current.uniqueTracks.toLong(), previous.uniqueTracks.toLong())
    )

    private fun dayIndex(timeMs: Long, zone: TimeZone): Long = Math.floorDiv(timeMs + zone.getOffset(timeMs), DAY_MS)

    /**
     * The current streak stays alive if you listened today OR yesterday (the day is not over yet),
     * so opening the app in the morning does not show "0" before the first song.
     */
    fun streak(startTimes: List<Long>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Streak {
        val days = startTimes.map { dayIndex(it, zone) }.toSortedSet()
        if (days.isEmpty()) return Streak(0, 0, 0)

        var best = 0
        var run = 0
        var prev: Long? = null
        for (d in days) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = d
        }

        val today = dayIndex(nowMs, zone)
        var cursor = when {
            today in days -> today
            (today - 1) in days -> today - 1
            else -> return Streak(0, best, days.size)
        }
        var current = 0
        while (cursor in days) { current++; cursor-- }
        return Streak(current, best, days.size)
    }

    /** "یک روز قبل" wording for the comparison caption of each range. */
    fun previousLabel(range: InsightRange): String? = when (range) {
        InsightRange.TODAY -> "دیروز"
        InsightRange.WEEK -> "هفتهٔ قبل"
        InsightRange.MONTH -> "ماه قبل"
        InsightRange.YEAR -> "سال قبل"
        InsightRange.ALL -> null
    }

    /** "▲ ۲۰٪ نسبت به ماه قبل", "▼ …", "بدون تغییر…", or null when there is nothing to compare. */
    fun deltaText(delta: Delta, previousLabel: String?): String? {
        val pct = delta.percent ?: return null
        val label = previousLabel ?: return null
        return when {
            pct > 0 -> "▲ $pct٪ نسبت به $label"
            pct < 0 -> "▼ ${-pct}٪ نسبت به $label"
            else -> "بدون تغییر نسبت به $label"
        }
    }

    /** Persian weekday initial for each of the last [count] days, oldest first (ش ی د س چ پ ج). */
    fun lastDaysInitials(nowMs: Long, zone: TimeZone, count: Int): List<String> {
        fun initial(dayOfWeek: Int) = when (dayOfWeek) {
            Calendar.SATURDAY -> "ش"; Calendar.SUNDAY -> "ی"; Calendar.MONDAY -> "د"; Calendar.TUESDAY -> "س"
            Calendar.WEDNESDAY -> "چ"; Calendar.THURSDAY -> "پ"; else -> "ج"
        }
        return (count - 1 downTo 0).map { back ->
            val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, -back) }
            initial(cal.get(Calendar.DAY_OF_WEEK))
        }
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} ساعت و ${minutes % 60} دقیقه" else "$minutes دقیقه"
    }

    /** Plain-text recap the user can share (nothing leaves the device unless they pick an app). */
    fun recapText(periodLabel: String, s: StatsSummary, streak: Streak?): String = buildString {
        appendLine("🎧 خلاصهٔ شنیدن من — $periodLabel")
        appendLine("⏱ ${duration(s.listenMs)} موسیقی، ${s.completedPlays} پخش، ${s.uniqueTracks} آهنگ متفاوت")
        s.topTracks.firstOrNull()?.let { appendLine("🎵 آهنگ اول: ${it.track.title} (${it.plays} بار)") }
        s.topArtists.firstOrNull()?.let { appendLine("🎤 خوانندهٔ اول: ${it.name}") }
        s.peakHour?.let { appendLine("🕒 ساعت اوج: $it تا ${(it + 1) % 24}") }
        if (streak != null && streak.best > 1) appendLine("🔥 بیشترین روزهای پیاپی: ${streak.best}")
        append("با «نواسا»")
    }
}
