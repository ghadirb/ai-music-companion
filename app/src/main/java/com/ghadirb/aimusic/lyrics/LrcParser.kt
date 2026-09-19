package com.ghadirb.aimusic.lyrics

import com.ghadirb.aimusic.analysis.LyricsAnalyzer.LrcLine
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Pure (Android-free, unit-tested) LRC handling.
 *
 * Real-world Persian .lrc files are messy: they come as UTF-8, UTF-8 with BOM, UTF-16 or
 * legacy Windows-1256; they use [mm:ss.xx], [mm:ss:xx] or [m:ss] stamps, several stamps per line,
 * an [offset:] header, enhanced word stamps <mm:ss.xx>, or no stamps at all (plain lyrics).
 */
object LrcParser {

    data class Parsed(val lines: List<LrcLine>, val synced: Boolean) {
        val isEmpty: Boolean get() = lines.isEmpty()
        companion object { val EMPTY = Parsed(emptyList(), true) }
    }

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""(?im)^\s*\[offset:\s*([+-]?\d+)\s*]""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val metaLine = Regex("""^\s*\[[A-Za-z]{2,}:[^\]]*]\s*$""")

    /** Decodes raw bytes, detecting BOMs, then strict UTF-8, then Windows-1256 (Persian/Arabic legacy). */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            val legacy = try { Charset.forName("windows-1256") } catch (_: Exception) { Charsets.ISO_8859_1 }
            String(bytes, legacy)
        }
    }

    fun parse(text: String): Parsed {
        val cleaned = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val offset = offsetTag.find(cleaned)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val synced = ArrayList<LrcLine>()
        val plain = ArrayList<String>()
        for (raw in cleaned.lineSequence()) {
            val stamps = timeTag.findAll(raw).toList()
            val body = raw.replace(timeTag, "").replace(wordTag, "").trim()
            if (stamps.isEmpty()) {
                if (body.isNotEmpty() && !metaLine.matches(raw)) plain.add(body)
                continue
            }
            if (body.isEmpty()) continue // timing-only line (instrumental gap)
            for (m in stamps) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val seconds = m.groupValues[2].toLongOrNull() ?: continue
                val fraction = m.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> (fraction.toLongOrNull() ?: 0L) * 100
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10
                    else -> fraction.toLongOrNull() ?: 0L
                }
                // Per the LRC spec a positive [offset:] makes lyrics appear sooner.
                val time = ((minutes * 60 + seconds) * 1000 + millis - offset).coerceAtLeast(0L)
                synced.add(LrcLine(time, body))
            }
        }
        return when {
            synced.isNotEmpty() -> Parsed(synced.sortedBy { it.timeMs }, synced = true)
            plain.isNotEmpty() -> Parsed(plain.mapIndexed { i, t -> LrcLine(i.toLong(), t) }, synced = false)
            else -> Parsed.EMPTY
        }
    }

    fun parse(bytes: ByteArray): Parsed = parse(decode(bytes))

    /** Lyrics words only (no stamps/headers) — used by the mood analyser. */
    fun plainText(text: String): String = parse(text).lines.joinToString("\n") { it.text }
}
