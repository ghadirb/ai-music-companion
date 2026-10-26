package com.ghadirb.aimusic.music

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** What a download is supposed to be; the first bytes must agree, so an HTML error page saved with HTTP 200 is never kept. */
enum class DownloadKind { AUDIO, ZIP }

sealed class DownloadResult {
    data class Ok(val file: File, val bytes: Long) : DownloadResult()
    /** [temporary] = worth another try later (network, 5xx, 429); false = this link will never work (404, HTML page, too big...). */
    data class Failed(val reason: String, val temporary: Boolean) : DownloadResult()
}

/**
 * Downloads ONE file from a source site into [dest] ("<name>.part" while running, renamed when complete and verified).
 * Pure JVM (no Android types) so it is unit-tested against a local HTTP server.
 */
class OnlineDownloader(
    private val allowHttp: Boolean = false,
    private val timeoutMs: Int = 15_000,
) {
    fun download(
        url: String,
        dest: File,
        kind: DownloadKind,
        maxBytes: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        val u = try { URL(url) } catch (_: Exception) { return DownloadResult.Failed("bad_url", false) }
        val scheme = u.protocol.lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return DownloadResult.Failed("insecure_url", false)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Navasa/1.1 (Android)")
            }
            val code = conn.responseCode
            if (code == 429 || code >= 500) return DownloadResult.Failed("http_$code", true)
            if (code !in 200..299) return DownloadResult.Failed("http_$code", false)
            val type = conn.contentType?.lowercase().orEmpty()
            if (type.startsWith("text/html")) return DownloadResult.Failed("html_page", false)
            val declared = conn.contentLengthLong
            if (declared > maxBytes) return DownloadResult.Failed("too_large", false)
            var written = 0L
            conn.inputStream.use { input ->
                part.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes) throw ZipSafeException("too_large") // a server lying about Content-Length
                        out.write(buf, 0, n)
                        onProgress(written, declared)
                    }
                }
            }
            if (declared >= 0 && written != declared) return fail(part, "truncated", true)
            if (written == 0L) return fail(part, "empty", false)
            val head = part.inputStream().use { s -> val b = ByteArray(16); val n = s.read(b); b.copyOf(maxOf(n, 0)) }
            val valid = when (kind) {
                DownloadKind.AUDIO -> AudioSniffer.looksLikeAudio(head) && !AudioSniffer.looksLikeHtml(head)
                DownloadKind.ZIP -> head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
            if (!valid) return fail(part, if (AudioSniffer.looksLikeHtml(head)) "html_page" else "wrong_type", false)
            if (dest.exists() && !dest.delete()) return fail(part, "dest_locked", true)
            if (!part.renameTo(dest)) return fail(part, "rename_failed", true)
            return DownloadResult.Ok(dest, written)
        } catch (_: CancelledException) {
            return fail(part, "cancelled", true)
        } catch (e: ZipSafeException) {
            return fail(part, e.reason, false)
        } catch (_: SocketTimeoutException) {
            return fail(part, "timeout", true)
        } catch (_: IOException) {
            return fail(part, "network", true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fail(part: File, reason: String, temporary: Boolean): DownloadResult {
        runCatching { part.delete() }
        return DownloadResult.Failed(reason, temporary)
    }

    private class CancelledException : RuntimeException()

    companion object {
        /** Biggest single song accepted (a long DJ mix is well below this). */
        const val MAX_SONG_BYTES = 120L * 1024 * 1024

        /** "Artist - Title.mp3": characters Android file systems / FAT reject are replaced, length is capped. */
        fun safeFileName(artist: String?, title: String, url: String): String {
            val ext = url.substringBefore('?').substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "mp3"
            val base = (if (artist.isNullOrBlank()) title else "$artist - $title")
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").replace(Regex("\\s+"), " ").trim().trim('.').ifEmpty { "song" }
            return base.take(120) + "." + ext
        }
    }
}
