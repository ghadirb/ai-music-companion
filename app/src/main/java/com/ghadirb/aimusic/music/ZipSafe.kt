package com.ghadirb.aimusic.music

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Reads a byte range of a (local or remote) file. Blocking: call from Dispatchers.IO. */
interface RangeSource {
    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray
}

class FileRangeSource(private val file: File) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        val buf = ByteArray(length)
        raf.readFully(buf)
        buf
    }
}

data class ZipEntryInfo(val path: String, val size: Long, val compressedSize: Long) {
    val name: String get() = path.substringAfterLast('/')
}

sealed class ZipListing {
    data class Ok(val audio: List<ZipEntryInfo>, val skippedEntries: Int) : ZipListing()
    /** [permanent] = the archive itself is unusable (never retry); false = temporary (network). */
    data class Failed(val reason: String, val permanent: Boolean) : ZipListing()
}

/** Cheap content sniffing: the extension alone is never trusted. */
object AudioSniffer {
    fun looksLikeAudio(head: ByteArray): Boolean {
        if (head.size < 4) return false
        fun at(i: Int) = head[i].toInt() and 0xFF
        if (at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33) return true                    // ID3 (mp3)
        if (at(0) == 0xFF && (at(1) and 0xE0) == 0xE0) return true                           // MPEG frame sync (mp3/aac ADTS)
        if (head.size >= 8 && at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) return true // "ftyp" (m4a)
        if (at(0) == 0x66 && at(1) == 0x4C && at(2) == 0x61 && at(3) == 0x43) return true   // fLaC
        if (at(0) == 0x4F && at(1) == 0x67 && at(2) == 0x67 && at(3) == 0x53) return true   // OggS (ogg/opus)
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46) return true   // RIFF (wav)
        return false
    }

    /** An HTML/JSON/text error page served with HTTP 200 is the classic "dead link" disguise. */
    fun looksLikeHtml(head: ByteArray): Boolean {
        val s = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        return s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<head") || s.startsWith("<?xml") || s.startsWith("{")
    }
}

class ZipSafeException(val reason: String) : IOException(reason)

object ZipSafe {
    const val MAX_ARCHIVE_BYTES = 800L * 1024 * 1024
    const val MAX_ENTRIES = 1000
    const val MAX_ENTRY_BYTES = 300L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED = 2L * 1024 * 1024 * 1024
    const val MAX_RATIO = 150L
    private const val MAX_DIRECTORY_BYTES = 2 * 1024 * 1024
    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50

    private val EXECUTABLE = setOf("apk", "exe", "bat", "cmd", "sh", "dex", "jar", "so", "msi", "scr", "com", "js", "vbs", "ps1", "dll", "bin")
    private val NESTED_ARCHIVE = setOf("zip", "rar", "7z", "gz", "tar", "tgz", "bz2", "xz")

    /** Zip Slip: reject absolute paths, drive letters, backslashes, NUL and any ".." segment. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 512) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\') || name.contains('\u0000') || name.contains(':')) return false
        return name.split('/').none { it == ".." }
    }

    private fun failed(reason: String, permanent: Boolean = true) = ZipListing.Failed(reason, permanent)

    /**
     * Reads only the central directory (a few KB at the end of the file) and validates it WITHOUT downloading or unpacking
     * anything. Works for a remote archive (Range requests) and a local file alike.
     */
    fun readDirectory(source: RangeSource, size: Long): ZipListing {
        if (size < 22) return failed("too_small")
        if (size > MAX_ARCHIVE_BYTES) return failed("too_large")
        val tailLen = minOf(size, 22L + 65535L).toInt()
        val tail = try { source.read(size - tailLen, tailLen) } catch (_: IOException) { return failed("network", permanent = false) }
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) { if (bb.getInt(i) == EOCD_SIG) { eocd = i; break } }
        if (eocd < 0) return failed("no_eocd")
        val diskNo = bb.getShort(eocd + 4).toInt() and 0xFFFF
        val cdDisk = bb.getShort(eocd + 6).toInt() and 0xFFFF
        val total = bb.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = bb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = bb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return failed("zip64_unsupported")
        if (diskNo != 0 || cdDisk != 0) return failed("multi_disk")
        if (total > MAX_ENTRIES) return failed("too_many_entries")
        if (cdSize > MAX_DIRECTORY_BYTES || cdOffset + cdSize > size) return failed("corrupt_directory")
        val cd = try { source.read(cdOffset, cdSize.toInt()) } catch (_: IOException) { return failed("network", permanent = false) }
        val cb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

        val audio = ArrayList<ZipEntryInfo>()
        var skipped = 0
        var totalUncompressed = 0L
        var pos = 0
        for (n in 0 until total) {
            if (pos + 46 > cd.size || cb.getInt(pos) != CEN_SIG) return failed("corrupt_directory")
            val flags = cb.getShort(pos + 8).toInt() and 0xFFFF
            val compressed = cb.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            val uncompressed = cb.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cb.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = cb.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = cb.getShort(pos + 32).toInt() and 0xFFFF
            if (pos + 46 + nameLen > cd.size) return failed("corrupt_directory")
            val name = String(cd, pos + 46, nameLen, Charsets.UTF_8)
            pos += 46 + nameLen + extraLen + commentLen
            if (pos > cd.size) return failed("corrupt_directory")

            if (!isSafeEntryName(name)) return failed("unsafe_path")
            if (name.endsWith("/")) continue // directory
            val ext = Autoindex.extensionOf(name)
            if (ext in EXECUTABLE) return failed("executable")
            if (flags and 1 != 0) return failed("encrypted")
            if (uncompressed > MAX_ENTRY_BYTES) return failed("zip_bomb")
            totalUncompressed += uncompressed
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) return failed("zip_bomb")
            if (uncompressed > 1024 * 1024 && (compressed == 0L || uncompressed / compressed > MAX_RATIO)) return failed("zip_bomb")

            if (ext in Autoindex.AUDIO_EXTENSIONS) audio.add(ZipEntryInfo(name, uncompressed, compressed))
            else { skipped += 1; if (ext in NESTED_ARCHIVE) continue }
        }
        if (audio.isEmpty()) return failed("no_audio")
        return ZipListing.Ok(audio.sortedBy { it.path.lowercase() }, skipped)
    }

    private fun safeFileName(entryName: String): String {
        val base = entryName.substringAfterLast('/')
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return cleaned.ifEmpty { "track" }.take(160)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val ext = Autoindex.extensionOf(name)
        val stem = if (ext.isNotEmpty()) name.dropLast(ext.length + 1) else name
        var i = 2
        while (candidate.exists() && i < 1000) {
            candidate = File(dir, if (ext.isNotEmpty()) "$stem ($i).$ext" else "$stem ($i)")
            i += 1
        }
        return candidate
    }

    /**
     * Extracts ONE audio entry. The destination name is derived from the entry's base name only (never its path), the final
     * file is verified to live inside [destDir], at most the declared size is written, the content must look like audio,
     * and a failed/partial extraction never leaves a file behind.
     */
    fun extract(zip: File, entry: ZipEntryInfo, destDir: File): File {
        if (!isSafeEntryName(entry.path)) throw ZipSafeException("unsafe_path")
        if (entry.size > MAX_ENTRY_BYTES) throw ZipSafeException("zip_bomb")
        destDir.mkdirs()
        val root = destDir.canonicalFile
        val target = uniqueFile(root, safeFileName(entry.path))
        if (target.canonicalFile.parentFile != root) throw ZipSafeException("unsafe_path")
        val part = File(root, target.name + ".part")
        try {
            ZipFile(zip).use { zf ->
                val e = zf.getEntry(entry.path) ?: throw ZipSafeException("not_found")
                if (e.size != entry.size && e.size >= 0) throw ZipSafeException("size_mismatch")
                zf.getInputStream(e).use { input ->
                    part.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > entry.size) throw ZipSafeException("zip_bomb") // never write more than declared
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val head = part.inputStream().use { s -> val b = ByteArray(16); val n = s.read(b); b.copyOf(maxOf(n, 0)) }
            if (!AudioSniffer.looksLikeAudio(head)) throw ZipSafeException("not_audio")
            if (!part.renameTo(target)) throw ZipSafeException("rename_failed")
            return target
        } catch (e: ZipSafeException) {
            part.delete(); throw e
        } catch (e: IOException) {
            part.delete(); throw ZipSafeException("corrupt_archive")
        }
    }
}
