package com.ghadirb.aimusic.lyrics

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Reads lyrics embedded in the audio file's tags — no storage permission needed beyond the audio itself:
 *  - MP3: ID3v2.3/2.4 `USLT` frames (unsynchronised lyrics; may contain [mm:ss] LRC stamps)
 *  - FLAC: Vorbis comment `LYRICS` / `UNSYNCEDLYRICS`
 * Frames/blocks are streamed and skipped, so big cover-art blocks are never loaded into memory.
 */
object EmbeddedLyricsReader {
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 512 * 1024

    fun read(stream: InputStream): String? {
        val input = BufferedInputStream(stream, 16 * 1024)
        return try {
            input.mark(16)
            val head = ByteArray(4)
            if (!input.readFully(head)) return null
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> {
                    input.reset(); readId3(input)
                }
                head.decodeToString() == "fLaC" -> readFlac(input)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- ID3v2
    private fun readId3(input: InputStream): String? {
        val header = ByteArray(10)
        if (!input.readFully(header)) return null
        val version = header[3].toInt()
        if (version != 3 && version != 4) return null // v2.2 uses a different frame layout: not supported
        val flags = header[5].toInt()
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        var remaining = tagSize.toLong()
        if (flags and 0x40 != 0) { // extended header
            val ext = ByteArray(4)
            if (!input.readFully(ext)) return null
            val extSize = if (version == 4) synchsafe(ext, 0) else bigEndian(ext, 0) + 4
            val skip = (extSize - 4).toLong().coerceAtLeast(0)
            if (!input.skipFully(skip)) return null
            remaining -= extSize
        }
        var fallback: String? = null
        while (remaining > 10) {
            val frame = ByteArray(10)
            if (!input.readFully(frame)) break
            remaining -= 10
            if (frame[0].toInt() == 0) break // padding
            val id = String(frame, 0, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) synchsafe(frame, 4) else bigEndian(frame, 4)
            if (size < 0 || size > remaining) break
            if (id == "USLT" && size in 1..MAX_TEXT_BYTES) {
                val data = ByteArray(size)
                if (!input.readFully(data)) break
                remaining -= size
                val text = decodeUslt(data)
                if (!text.isNullOrBlank()) {
                    if (text.contains(Regex("""\[\d{1,3}:\d{2}"""))) return text // synced-looking lyrics win
                    if (fallback == null) fallback = text
                }
            } else {
                if (!input.skipFully(size.toLong())) break
                remaining -= size
            }
        }
        return fallback
    }

    /** USLT: encoding(1) language(3) descriptor(terminated) text. */
    internal fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt()
        var i = 4 // skip encoding + language
        val wide = encoding == 1 || encoding == 2
        // skip the content descriptor
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) return null
        val body = data.copyOfRange(i, data.size)
        return when (encoding) {
            1 -> String(body, Charsets.UTF_16)                         // with BOM
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> LrcParser.decode(body)                             // "ISO-8859-1" in practice is often UTF-8 or Windows-1256
        }
    }

    // ---------------------------------------------------------------- FLAC
    private fun readFlac(input: InputStream): String? {
        while (true) {
            val block = ByteArray(4)
            if (!input.readFully(block)) return null
            val last = block[0].toInt() and 0x80 != 0
            val type = block[0].toInt() and 0x7F
            val length = ((block[1].toInt() and 0xFF) shl 16) or ((block[2].toInt() and 0xFF) shl 8) or (block[3].toInt() and 0xFF)
            if (type == 4 && length in 1..MAX_TEXT_BYTES * 4) {
                val data = ByteArray(length)
                if (!input.readFully(data)) return null
                return parseVorbisLyrics(data)
            }
            if (!input.skipFully(length.toLong())) return null
            if (last) return null
        }
    }

    internal fun parseVorbisLyrics(data: ByteArray): String? {
        var pos = 0
        fun int32(): Int? {
            if (pos + 4 > data.size) return null
            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = int32() ?: return null
        if (vendor < 0 || pos + vendor > data.size) return null
        pos += vendor
        val count = int32() ?: return null
        repeat(count.coerceAtMost(4096)) {
            val len = int32() ?: return null
            if (len < 0 || pos + len > data.size) return null
            val entry = String(data, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "LYRICS_TEXT") return entry.substring(eq + 1).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- byte helpers
    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun bigEndian(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) { left -= skipped; continue }
            if (read() < 0) return false
            left -= 1
        }
        return true
    }
}
