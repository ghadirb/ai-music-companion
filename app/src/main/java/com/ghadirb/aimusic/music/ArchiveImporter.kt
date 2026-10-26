package com.ghadirb.aimusic.music

import java.io.File

sealed class ZipFetch {
    data class Ok(val zip: File) : ZipFetch()
    data class Failed(val reason: String, val temporary: Boolean) : ZipFetch()
}

data class ExtractReport(val files: List<File>, val failed: List<String>)

/**
 * Turns a singer-collection ZIP into playable files: downloads the ARCHIVE first (never streams it), keeps it briefly in the
 * cache, lists it with the same safety rules as the browser (Zip Slip, bombs, encrypted, size limits) and extracts only
 * what was asked. Pure JVM; the Android side only supplies directories.
 */
class ArchiveImporter(
    private val downloader: OnlineDownloader,
    private val cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxArchiveBytes: Long = ZipSafe.MAX_ARCHIVE_BYTES,
) {
    /** Removes archives and half-finished downloads older than [maxAgeMs]; keeps recent ones so a second song of the same ZIP is instant. */
    fun purgeStale(maxAgeMs: Long = 60 * 60_000L) {
        val cutoff = now() - maxAgeMs
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".zip") || f.name.endsWith(".part")) && f.lastModified() < cutoff) f.delete()
        }
    }

    private fun cacheFile(url: String) = File(cacheDir, "z" + Integer.toHexString(url.hashCode()) + ".zip")

    /** Main link first, then the other copies of the same collection; stops at the first one that is a real, usable ZIP. */
    fun fetchZip(
        collection: OnlineCollection,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ZipFetch {
        purgeStale()
        var last: ZipFetch.Failed = ZipFetch.Failed("no_link", false)
        for (url in (listOf(collection.url) + collection.altUrls).distinct()) {
            val file = cacheFile(url)
            if (file.exists() && file.length() > 0) { file.setLastModified(now()); return ZipFetch.Ok(file) }
            when (val r = downloader.download(url, file, DownloadKind.ZIP, maxArchiveBytes, isCancelled, onProgress)) {
                is DownloadResult.Ok -> return ZipFetch.Ok(r.file)
                is DownloadResult.Failed -> {
                    last = ZipFetch.Failed(r.reason, r.temporary)
                    if (r.reason == "cancelled") return last
                }
            }
        }
        return last
    }

    /** Audio files inside [zip], already filtered and checked by [ZipSafe]. */
    fun list(zip: File): ZipListing = ZipSafe.readDirectory(FileRangeSource(zip), zip.length())

    /** Extracts the entries whose path is in [paths] (null = every audio file). One bad entry never stops the others. */
    fun extract(zip: File, paths: Set<String>?, destDir: File): ExtractReport {
        val listing = list(zip) as? ZipListing.Ok ?: return ExtractReport(emptyList(), listOf("zip_unusable"))
        val wanted = listing.audio.filter { paths == null || it.path in paths }
        val done = ArrayList<File>(); val failed = ArrayList<String>()
        for (e in wanted) {
            try { done += ZipSafe.extract(zip, e, destDir) } catch (x: ZipSafeException) { failed += e.path + ":" + x.reason }
            catch (x: java.io.IOException) { failed += e.path + ":io" }
        }
        return ExtractReport(done, failed)
    }
}
