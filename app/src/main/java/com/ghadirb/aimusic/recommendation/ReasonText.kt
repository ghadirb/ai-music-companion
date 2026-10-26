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
        ReasonType.TIME_OF_DAY_MATCH -> when (reason.detail) {
            "night" -> "چون شب‌ها این نوع آهنگ را بیشتر گوش می‌دهی"
            "morning" -> "چون صبح‌ها این نوع آهنگ را بیشتر گوش می‌دهی"
            "afternoon" -> "چون ظهرها این نوع آهنگ را بیشتر گوش می‌دهی"
            "evening" -> "چون عصرها این نوع آهنگ را بیشتر گوش می‌دهی"
            else -> "چون با انرژی آهنگ‌هایی که این ساعت گوش می‌دهی هماهنگ است"
        }
        ReasonType.EXPLORE -> "چون به سلیقهٔ تو نزدیک است و هنوز گوش نداده‌ای"
        ReasonType.NEW_ADDITION -> "تازه به کتابخانه‌ات اضافه شده"
        ReasonType.SIMILAR_TRACK -> reason.detail?.let { "مشابه «$it» که دوست داری" } ?: "مشابه آهنگ‌هایی که اخیراً دوست داشته‌ای"
        ReasonType.SIMILAR_ARTIST -> "چون خوانندگان مشابه سلیقهٔ تو را زیاد گوش می‌دهی"
        ReasonType.SIMILAR_MOOD -> when (reason.detail?.lowercase()) {
            "calm" -> "چون آهنگ‌های آرام این سبک را زیاد گوش می‌دهی"
            "energetic" -> "چون آهنگ‌های پرانرژی این‌چنینی را زیاد گوش می‌دهی"
            "happy" -> "چون آهنگ‌های شاد را دوست داری"
            "sad" -> "چون آهنگ‌های احساسی را زیاد گوش می‌دهی"
            else -> "چون حال‌وهوایش به آهنگ‌های موردعلاقه‌ات نزدیک است"
        }
        ReasonType.HIGH_COMPLETION -> "چون معمولاً این آهنگ را تا آخر گوش می‌دهی"
        ReasonType.SESSION_MATCH -> "چون با آنچه همین الان گوش می‌دهی هماهنگ است"
        ReasonType.RECENTLY_FAVORITED -> "مشابه آهنگی که تازه به علاقه‌مندی‌ها اضافه کرده‌ای"
        ReasonType.DISCOVERY -> "برای کشف چیزی تازه در کتابخانه‌ات"
        ReasonType.COLD_START -> "پیشنهاد اولیه بر اساس ویژگی‌های آهنگ؛ با گوش دادن شخصی‌تر می‌شود"
    }

    /** The single most relevant reason, or null when nothing meaningful applies. */
    fun best(recommendation: Recommendation): String? = recommendation.reasons.firstOrNull()?.let(::format)
}
