package com.ghadirb.aimusic.video

/**
 * Shifts every cue of a subtitle file by a fixed time (JVM unit tested). Positive = later, negative = earlier.
 * Cues that would end before 0 are dropped; a cue starting before 0 is clamped to 0. Used for subtitle sync on
 * engines that cannot delay subtitles themselves (Media3).
 */
object SubtitleShift {

    private const val BOM = '\uFEFF'

    /** @param extension "srt", "vtt", "ass" or "ssa" (any case); null for unsupported formats. */
    fun shift(text: String, extension: String, offsetMs: Long): String? {
        val body = text.trimStart(BOM)
        return when (extension.lowercase()) {
            "srt" -> shiftSrt(body, offsetMs)
            "vtt" -> shiftVtt(body, offsetMs)
            "ass", "ssa" -> shiftAss(body, offsetMs)
            else -> null
        }
    }

    // ---- SRT ----
    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val SRT_LINE = Regex("""^\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})(.*)$""")

    fun parseClock(s: String): Long? {
        val m = SRT_TIME.matchEntire(s.trim()) ?: return null
        val (h, mi, se, fr) = m.destructured
        val millis = fr.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + millis
    }

    fun formatSrt(ms: Long): String = clock(ms, ',')
    fun formatVtt(ms: Long): String = clock(ms, '.')

    private fun clock(ms: Long, sep: Char): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, t % 1000)
    }

    private fun shiftSrt(text: String, offset: Long): String {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var index = 1
        for (block in blocks) {
            val lines = block.split('\n')
            val timingAt = lines.indexOfFirst { SRT_LINE.matches(it) }
            if (timingAt < 0) continue
            val m = SRT_LINE.matchEntire(lines[timingAt])!!
            val start = (parseClock(m.groupValues[1]) ?: continue) + offset
            val end = (parseClock(m.groupValues[2]) ?: continue) + offset
            if (end <= 0L) continue
            val rest = lines.drop(timingAt + 1)
            out += (listOf("$index", "${formatSrt(start)} --> ${formatSrt(end)}${m.groupValues[3].trimEnd()}") + rest).joinToString("\n")
            index++
        }
        return out.joinToString("\n\n") + if (out.isNotEmpty()) "\n" else ""
    }

    // ---- WebVTT ----
    private val VTT_LINE = Regex("""^\s*((?:\d{1,2}:)?\d{2}:\d{2}\.\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}\.\d{1,3})(.*)$""")

    private fun vttClock(s: String): Long? {
        val parts = s.trim().split(':')
        val withHours = if (parts.size == 2) "00:${s.trim()}" else s.trim()
        return parseClock(withHours)
    }

    private fun shiftVtt(text: String, offset: Long): String {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        for (block in blocks) {
            val lines = block.split('\n')
            val timingAt = lines.indexOfFirst { VTT_LINE.matches(it) }
            if (timingAt < 0) {
                if (block.trimStart().startsWith("WEBVTT")) out += block   // header (and style/note blocks) kept as is
                else if (lines.first().trimStart().let { it.startsWith("STYLE") || it.startsWith("NOTE") }) out += block
                continue
            }
            val m = VTT_LINE.matchEntire(lines[timingAt])!!
            val start = (vttClock(m.groupValues[1]) ?: continue) + offset
            val end = (vttClock(m.groupValues[2]) ?: continue) + offset
            if (end <= 0L) continue
            val head = lines.take(timingAt)   // optional cue identifier
            val rest = lines.drop(timingAt + 1)
            out += (head + "${formatVtt(start)} --> ${formatVtt(end)}${m.groupValues[3].trimEnd()}" + rest).joinToString("\n")
        }
        val joined = out.joinToString("\n\n")
        return (if (joined.startsWith("WEBVTT")) joined else "WEBVTT\n\n$joined") + "\n"
    }

    // ---- ASS / SSA: "Dialogue: Layer,Start,End,Style,..." with h:mm:ss.cc ----
    private val ASS_TIME = Regex("""^(\d+):(\d{2}):(\d{2})\.(\d{2})$""")

    private fun assClock(s: String): Long? {
        val m = ASS_TIME.matchEntire(s.trim()) ?: return null
        val (h, mi, se, cs) = m.destructured
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + cs.toLong() * 10
    }

    private fun formatAss(ms: Long): String {
        val t = (ms.coerceAtLeast(0L) + 5) / 10   // centiseconds, rounded
        val cs = t % 100
        val s = (t / 100) % 60
        val m = (t / 6000) % 60
        val h = t / 360_000
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    private fun shiftAss(text: String, offset: Long): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val isDialogue = line.startsWith("Dialogue:")
            val isComment = line.startsWith("Comment:")
            if (!isDialogue && !isComment) { out += line; continue }
            val prefix = if (isDialogue) "Dialogue:" else "Comment:"
            val fields = line.removePrefix(prefix).trimStart().split(',', limit = 10)
            val start = fields.getOrNull(1)?.let(::assClock)
            val end = fields.getOrNull(2)?.let(::assClock)
            if (fields.size < 10 || start == null || end == null) { out += line; continue }
            val newEnd = end + offset
            if (newEnd <= 0L) continue
            val f = fields.toMutableList()
            f[1] = formatAss(start + offset)
            f[2] = formatAss(newEnd)
            out += "$prefix ${f.joinToString(",")}"
        }
        return out.joinToString("\n")
    }
}
