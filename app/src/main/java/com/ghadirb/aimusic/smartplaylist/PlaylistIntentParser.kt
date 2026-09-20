package com.ghadirb.aimusic.smartplaylist

import com.ghadirb.aimusic.library.EnergyBand
import com.ghadirb.aimusic.search.SearchText

/** Library facts the parser may match against (artist/genre names present on this device). */
data class ParseContext(
    val knownArtists: List<String> = emptyList(),
    val knownGenres: List<String> = emptyList(),
    val currentTrackId: Long? = null
)

/**
 * Offline rule-based Persian/English parser: free text -> [PlaylistIntent]. Deterministic and
 * private (runs on-device). The Premium AI DJ can replace/augment it with an LLM that returns the
 * same structure, keeping this as the offline fallback.
 */
object PlaylistIntentParser {

    private val focusWords = listOf("مطالعه", "تمرکز", "درس", "کار کردن", "study", "focus", "reading", "concentrat")
    private val calmWords = listOf("آرام", "ملایم", "relax", "calm", "chill", "slow", "soft")
    private val sadWords = listOf("غمگین", "غم", "sad", "melanchol")
    private val happyWords = listOf("شاد", "رقص", "happy", "dance", "party", "upbeat")
    private val workoutWords = listOf("ورزش", "تمرین", "دویدن", "workout", "gym", "running", "exercise")
    private val drivingWords = listOf("رانندگی", "ماشین", "جاده", "driving", "road trip", "car")
    private val nightWords = listOf("شبانه", "شب", "night", "late")
    private val lessPlayedWords = listOf("کمتر گوش", "کمتر پخش", "گوش نداده", "نشنیده", "less played", "least played", "not played", "haven t", "rarely")
    private val favoriteWords = listOf("علاقه مندی", "محبوب", "favorite", "favourite")
    private val persianWords = listOf("ایرانی", "فارسی", "persian", "iranian")
    private val foreignWords = listOf("خارجی", "انگلیسی", "english", "foreign")
    private val similarWords = listOf("شبیه", "مشابه", "similar")

    fun parse(text: String, context: ParseContext = ParseContext()): PlaylistIntent {
        val t = SearchText.normalize(text)
        if (t.isEmpty()) return PlaylistIntent()

        fun has(words: List<String>) = words.any { containsWord(t, SearchText.normalize(it)) }

        val focus = has(focusWords)
        val calm = has(calmWords)
        val sad = has(sadWords)
        val happy = has(happyWords)
        val workout = has(workoutWords)
        val driving = has(drivingWords)
        val night = has(nightWords)

        val moods = LinkedHashSet<String>()
        var energy: EnergyBand? = null
        val labels = ArrayList<String>()

        when {
            workout -> { moods += "energetic"; energy = EnergyBand.HIGH; labels += "ورزش" }
            driving && night -> { moods += listOf("calm", "neutral", "happy"); energy = EnergyBand.MEDIUM; labels += "رانندگی شبانه" }
            driving -> { moods += listOf("energetic", "happy"); energy = EnergyBand.MEDIUM; labels += "رانندگی" }
            focus -> { moods += listOf("calm", "neutral"); energy = EnergyBand.LOW; labels += "تمرکز" }
            happy -> { moods += listOf("happy", "energetic"); energy = EnergyBand.HIGH; labels += "شاد" }
            sad -> { moods += "sad"; labels += "غمگین" }
            calm || night -> { moods += "calm"; energy = EnergyBand.LOW; labels += if (night && !calm) "شب" else "آرام" }
        }

        val language = when {
            has(persianWords) -> LanguageFilter.PERSIAN
            has(foreignWords) -> LanguageFilter.NON_PERSIAN
            else -> null
        }
        if (language == LanguageFilter.PERSIAN) labels += "ایرانی"

        val lessPlayed = has(lessPlayedWords)
        val favoriteOnly = has(favoriteWords)
        if (favoriteOnly) labels += "علاقه‌مندی"
        if (lessPlayed) labels += "کمتر شنیده‌شده"

        val artist = context.knownArtists.filter { it.length >= 3 }
            .filter { containsWord(t, SearchText.normalize(it)) }.maxByOrNull { it.length }
        val genre = context.knownGenres.filter { it.length >= 3 }
            .filter { containsWord(t, SearchText.normalize(it)) }.maxByOrNull { it.length }
        artist?.let { labels += it }
        genre?.let { labels += it }

        val similarTo = if (has(similarWords)) context.currentTrackId else null
        if (similarTo != null) labels += "مشابه آهنگ فعلی"

        val duration = parseDurationMinutes(t)
        val trackWord = SearchText.normalize("آهنگ")
        val count = Regex("""(\d{1,3})\s*($trackWord|track|song)""").find(t)?.groupValues?.get(1)?.toIntOrNull()

        return PlaylistIntent(
            moods = moods,
            energy = energy,
            genre = genre,
            artist = artist,
            language = language,
            durationMinutes = duration,
            excludeRecentDays = if (lessPlayed) 14 else null,
            favoriteOnly = favoriteOnly,
            similarToTrackId = similarTo,
            sort = if (lessPlayed) SmartSort.LEAST_PLAYED else SmartSort.BEST_MATCH,
            limit = count ?: PlaylistIntent.DEFAULT_LIMIT,
            title = labels.takeIf { it.isNotEmpty() }?.let { "پلی‌لیست ${it.joinToString(" · ")}" }
        ).sanitized()
    }

    /** Whole-token/phrase match on normalised text so "شب" does not fire inside unrelated words. */
    private fun containsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = i == 0 || text[i - 1] == ' '
            val afterIndex = i + word.length
            // Allow Persian suffixes (ها، ی، ان...) but not a longer unrelated word start: accept if boundary or short suffix.
            val after = afterIndex == text.length || text[afterIndex] == ' ' || (word.length >= 3)
            if (before && after) return true
            from = i + 1
        }
    }

    private fun parseDurationMinutes(t: String): Int? {
        when {
            t.contains("نیم ساعت") || t.contains("half hour") || t.contains("half an hour") -> return 30
            t.contains("یک ربع") -> return 15
        }
        Regex("""(\d{1,3})\s*(ساعت|hours?|hrs?)""").find(t)?.let { return it.groupValues[1].toInt() * 60 }
        Regex("""(\d{1,3})\s*(دقیقه|minutes?|mins?)""").find(t)?.let { return it.groupValues[1].toInt() }
        val words = mapOf("یک" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5)
        for ((word, n) in words) {
            if (Regex("""(^| )$word ساعت""").containsMatchIn(t)) return n * 60
        }
        if (t.contains("one hour") || t.contains("an hour")) return 60
        return null
    }
}
