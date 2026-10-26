package com.ghadirb.aimusic.music

import java.net.URLDecoder
import java.text.Normalizer

/** One entry of an nginx/Apache "Index of /" page. */
data class ListingEntry(val name: String, val isDir: Boolean, val size: Long?, val modified: String?)

sealed class ListingResult {
    data class Listing(val entries: List<ListingEntry>) : ListingResult()
    /** Login page, error page, SPA shell ... anything that is not a real directory index. */
    data class NotAListing(val reason: String) : ListingResult()
}

/** Port of the Worker's listing.ts + track.ts so the phone can scan sources the Worker cannot reach (Ahaang). */
object Autoindex {
    private val MONTHS = mapOf("jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04", "may" to "05", "jun" to "06",
        "jul" to "07", "aug" to "08", "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12")
    private val ROW = Regex("""<a href="([^"]*)">[^<]*</a>\s*(?:(\d{2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2})\s+(\d+|-))?""")
    private val TITLE = Regex("""<title>\s*Index of /""", RegexOption.IGNORE_CASE)
    private val CONTROL = Regex("[\\u0000-\\u001f]")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

    fun extensionOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(i + 1).lowercase() else ""
    }
    fun isAudioName(name: String) = extensionOf(name) in AUDIO_EXTENSIONS
    fun isZipName(name: String) = extensionOf(name) == "zip"

    /** Percent-encodes ONE path segment exactly once (names contain spaces, Persian letters, brackets). */
    fun encodeSegment(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A")

    private fun decode(href: String): String? = try {
        URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) { null }

    fun parse(html: String): ListingResult {
        if (html.isBlank()) return ListingResult.NotAListing("empty")
        if (!TITLE.containsMatchIn(html)) return ListingResult.NotAListing("html_page")
        val entries = ArrayList<ListingEntry>()
        for (m in ROW.findAll(html)) {
            val href = m.groupValues[1]
            if (href.isEmpty() || href == "../" || href.startsWith("/") || href.startsWith("?") || href.startsWith("#") || SCHEME.containsMatchIn(href)) continue
            val isDir = href.endsWith("/")
            val name = decode(if (isDir) href.dropLast(1) else href) ?: continue
            if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == ".." || CONTROL.containsMatchIn(name)) continue
            val month = MONTHS[m.groupValues[3].lowercase()]
            val modified = if (m.groupValues[2].isNotEmpty() && month != null)
                "${m.groupValues[4]}-$month-${m.groupValues[2]}T${m.groupValues[5]}:${m.groupValues[6]}" else null
            val sizeText = m.groupValues[7]
            val size = if (!isDir && sizeText.isNotEmpty() && sizeText != "-") sizeText.toLongOrNull() else null
            entries.add(ListingEntry(name, isDir, size, modified))
        }
        return ListingResult.Listing(entries)
    }

    // ------------------------------------------------------------------ names

    /** Folds the many ways Persian/Arabic text is typed into one comparable form (ی/ي/ى, ک/ك, digits, ZWNJ, diacritics, punctuation). */
    fun normalizeText(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[\\u064A\\u0649]"), "\u06CC")
            .replace("\u0643", "\u06A9")
            .replace(Regex("[\\u06F0-\\u06F9]")) { (it.value[0].code - 0x06F0).toString() }
            .replace(Regex("[\\u0660-\\u0669]")) { (it.value[0].code - 0x0660).toString() }
            .replace(Regex("[\\u200C\\u200B\\u200D\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), " ")
            .lowercase()
            .replace(Regex("_+"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val KNOWN_BITRATES = setOf(48, 64, 96, 112, 128, 160, 192, 224, 256, 320)
    private val SITE_TAG = Regex("""[\[(]?\s*(?:https?://)?(?:www\.)?[a-z0-9-]+\.(?:com|ir|net|org|info)\s*[\])]?""", RegexOption.IGNORE_CASE)
    private val QUALITY_PATTERNS = listOf(
        Regex("""\s*\[(\d{2,3})\s*(?:kbps)?\]\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\((\d{2,3})\s*(?:kbps)?\)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+-\s+(\d{2,3})\s*(?:kbps)?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(\d{2,3})\s*kbps\s*$""", RegexOption.IGNORE_CASE),
    )

    data class ParsedName(val artist: String?, val title: String, val quality: Int?)

    private fun stripQuality(text: String): Pair<String, Int?> {
        var current = text.trim()
        var quality: Int? = null
        repeat(3) {
            var changed = false
            for (p in QUALITY_PATTERNS) {
                val m = p.find(current) ?: continue
                if (m.groupValues[1].toInt() in KNOWN_BITRATES) {
                    quality = quality ?: m.groupValues[1].toInt()
                    current = current.substring(0, m.range.first).trim()
                    changed = true
                    break
                }
            }
            if (!changed) return Pair(current, quality)
        }
        return Pair(current, quality)
    }

    /** "Artist - Title (128).mp3" -> parts. Splits on the FIRST " - " only; titles may contain hyphens. */
    fun parseTrackName(fileName: String): ParsedName {
        val ext = extensionOf(fileName)
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length + 1) else fileName
        val (text, quality) = stripQuality(base.replace(Regex("_+"), " "))
        val cleaned = text.replace(SITE_TAG, " ").replace(Regex("\\s+"), " ").trim()
        val i = cleaned.indexOf(" - ")
        if (i <= 0 || i >= cleaned.length - 3) return ParsedName(null, cleaned.ifEmpty { base.trim() }, quality)
        return ParsedName(cleaned.substring(0, i).trim(), cleaned.substring(i + 3).trim(), quality)
    }

    private val ARTIST_SPLIT = Regex("""\s+(?:x|and|ft\.?|feat\.?|featuring|with|\u0648)\s+|\s*[&,+/]\s*|\s+\u0648\s+""", RegexOption.IGNORE_CASE)

    fun splitArtists(credit: String): List<String> = credit.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** Stable identity of a song across sources; the order of featured artists must not matter. */
    fun trackKey(artist: String?, title: String): String {
        val artists = artist?.let { splitArtists(it).map(::normalizeText).filter { a -> a.isNotEmpty() }.sorted() } ?: emptyList()
        return artists.joinToString("+") + "|" + normalizeText(title)
    }

    /** Singer identity for grouping archives: first credited artist, normalized ("" when unknown). */
    fun artistKey(artist: String?): String {
        if (artist.isNullOrBlank()) return ""
        return normalizeText(splitArtists(artist).firstOrNull() ?: artist)
    }

    /** "Artoosh - Goldenhits.zip" -> artist "Artoosh", collection "Goldenhits". */
    fun parseZipName(fileName: String): Pair<String?, String> {
        val p = parseTrackName(fileName)
        return Pair(p.artist, p.title)
    }

    // ------------------------------------------------------------------ collection kinds

    /** "Monthly Collection - Mehr 1404.zip", "Year Collection - 1401.zip": site-made bundles, not a singer's collection. */
    private val PERIODIC_ARTIST = Regex("""^(?:monthly|weekly|yearly|year|season(?:al)?)\s+collections?$""")
    private val GOLD_WORDS = Regex("""gold|golchin|\u06AF\u0644\u0686\u06CC\u0646|greatest|\bhits?\b|\bbest\b""")

    enum class CollectionKind {
        /** A singer's collection ("Artoosh - Goldenhits"), also when the file name has no singer but says "Golchin"/"Goldenhits". */
        ARTIST,
        /** Monthly / yearly bundles of new songs. */
        PERIODIC,
        /** Anything else that happens to be a ZIP (a bare number, a cover pack ...): never shown. */
        OTHER,
    }

    fun isPeriodicArtistKey(artistKey: String): Boolean = PERIODIC_ARTIST.matches(artistKey)

    fun collectionKind(artist: String?, collection: String): CollectionKind {
        val a = normalizeText(artist ?: "")
        if (a.isNotEmpty() && PERIODIC_ARTIST.matches(a)) return CollectionKind.PERIODIC
        if (a.isNotEmpty()) return CollectionKind.ARTIST
        return if (GOLD_WORDS.containsMatchIn(normalizeText(collection))) CollectionKind.ARTIST else CollectionKind.OTHER
    }

    /** Identity of a collection regardless of the quality marker in the file name ("X - Goldenhits.zip" == "X - Goldenhits (128).zip"). */
    fun collectionKey(artist: String?, collection: String): String = artistKey(artist) + "|" + normalizeText(collection)
}
