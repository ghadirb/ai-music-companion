package com.ghadirb.aimusic.recommendation

import java.util.Calendar
import java.util.TimeZone

/** Coarse time-of-day buckets shared by the taste profile, scorer and mixes. */
object TimeBuckets {
    const val MORNING = "morning"
    const val AFTERNOON = "afternoon"
    const val EVENING = "evening"
    const val NIGHT = "night"

    fun bucketForHour(hour: Int): String = when (hour) {
        in 5..10 -> MORNING
        in 11..16 -> AFTERNOON
        in 17..21 -> EVENING
        else -> NIGHT
    }

    fun hourOf(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

    fun bucketOf(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        bucketForHour(hourOf(timeMs, zone))
}
