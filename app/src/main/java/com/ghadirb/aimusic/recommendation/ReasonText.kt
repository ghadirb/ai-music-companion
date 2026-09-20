package com.ghadirb.aimusic.recommendation

/** Persian, user-facing explanation strings for recommendation reasons. */
object ReasonText {

    fun format(reason: Reason): String = when (reason.type) {
        ReasonType.FAVORITE -> "چون در علاقه‌مندی‌های توست"
        ReasonType.FAVORITE_ARTIST -> "چون «${reason.detail.orEmpty()}» را زیاد گوش می‌دهی"
        ReasonType.GENRE_MATCH -> "چون سبک ${reason.detail.orEmpty()} را دوست داری"
        ReasonType.SIMILAR_TO_RECENT -> "چون اخیراً چند آهنگ مشابه گوش داده‌ای"
        ReasonType.RECENTLY_LOVED -> "چون اخیراً ${reason.number ?: 0} بار کامل گوش داده‌ای"
        ReasonType.NOT_PLAYED_LONG -> "چون مدت زیادی است پخش نشده (${reason.number ?: 0} روز)"
        ReasonType.TIME_OF_DAY_MATCH -> "چون با انرژی آهنگ‌هایی که این ساعت گوش می‌دهی هماهنگ است"
        ReasonType.EXPLORE -> "چون به سلیقهٔ تو نزدیک است و هنوز گوش نداده‌ای"
        ReasonType.NEW_ADDITION -> "تازه به کتابخانه‌ات اضافه شده"
    }

    /** The single most relevant reason, or null when nothing meaningful applies. */
    fun best(recommendation: Recommendation): String? = recommendation.reasons.firstOrNull()?.let(::format)
}
