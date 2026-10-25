package com.ghadirb.aimusic.library

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes or copies/moves tracks that were scanned from MediaStore (see
 * [com.ghadirb.aimusic.data.scanner.MediaLibraryScanner]). Tracks are indexed by their
 * MediaStore content:// uri, so removing one from the device — not just from this app's
 * Library — has to go through MediaStore/ContentResolver, or it silently no-ops (or throws)
 * under scoped storage on Android 10+. All work runs off the main thread.
 */
object TrackFileOps {

    /** Result of attempting to delete a batch of tracks from the device. */
    sealed interface DeleteOutcome {
        /** Every uri was deleted immediately — no system confirmation was needed. */
        data class Deleted(val uris: List<Uri>) : DeleteOutcome
        /**
         * The system needs the user to confirm via a dialog before [uris] can be deleted.
         * Launch [intentSender] (e.g. with ActivityResultContracts.StartIntentSenderForResult);
         * a positive result means every uri in [uris] was deleted.
         */
        data class NeedsConfirmation(
            val intentSender: IntentSender,
            val uris: List<Uri>,
            /** Android 10 deletes one uri per confirmation: files already deleted earlier in this batch. */
            val deletedSoFar: List<Uri> = emptyList()
        ) : DeleteOutcome
        data class Failed(val reason: String) : DeleteOutcome
    }

    /**
     * Attempts to delete [uris] (MediaStore content:// uris) from the device.
     * - Android 11+: always goes through one batched system confirmation via
     *   [MediaStore.createDeleteRequest], regardless of file ownership.
     * - Android 10: each non-owned uri needs its own confirmation; the first one
     *   encountered is surfaced and the caller should retry the remaining uris
     *   (returned in the same order) after it's resolved.
     * - Below Android 10: legacy storage permission lets ContentResolver delete directly.
     */
    suspend fun requestDelete(
        context: Context,
        uris: List<Uri>,
        deletedSoFar: List<Uri> = emptyList()
    ): DeleteOutcome = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext DeleteOutcome.Deleted(deletedSoFar)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, uris)
                DeleteOutcome.NeedsConfirmation(pendingIntent.intentSender, uris, deletedSoFar)
            } catch (e: Exception) {
                DeleteOutcome.Failed("درخواست حذف ساخته نشد.")
            }
        }
        val deleted = deletedSoFar.toMutableList()
        for (uri in uris) {
            try {
                // Only a row that really went away counts; "0 rows" means the file is still on the phone.
                if (resolver.delete(uri, null, null) > 0) deleted += uri
            } catch (e: SecurityException) {
                // Android 10: the user must allow THIS file first; the system grants access but does NOT
                // delete it - the caller must call requestDelete again with the remaining uris.
                // RecoverableSecurityException only exists on API 29+, so it is never named in a catch
                // clause here (minSdk is 26): it is detected inside an API-guarded helper instead.
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) recoverableIntentSender(e) else null
                if (sender != null) {
                    val remaining = uris.dropWhile { it != uri }
                    return@withContext DeleteOutcome.NeedsConfirmation(sender, remaining, deleted.toList())
                }
                return@withContext DeleteOutcome.Failed("دسترسی حذف این فایل داده نشد.")
            }
        }
        DeleteOutcome.Deleted(deleted)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoverableIntentSender(e: SecurityException): IntentSender? =
        (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

    /** Device file paths (MediaStore DATA column) of [uris]; must be read BEFORE deleting, while the rows still exist. */
    suspend fun pathsOf(context: Context, uris: List<Uri>): Map<Uri, String> = withContext(Dispatchers.IO) {
        val out = HashMap<Uri, String>()
        for (uri in uris) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out[uri] = it }
                }
            }
        }
        out
    }

    /** [gone] really left the phone; [kept] are files that are still on the device. */
    data class VerifyResult(val gone: List<Uri>, val kept: List<Uri>)

    /**
     * The system said these were deleted - check that the files really are gone, so the app never drops an
     * item from its lists while the file is still on the phone. On Android 10 and below (legacy storage) a
     * file that survived is deleted directly as a second attempt.
     */
    suspend fun verifyDeleted(context: Context, uris: List<Uri>, paths: Map<Uri, String>): VerifyResult =
        withContext(Dispatchers.IO) {
            val gone = mutableListOf<Uri>()
            val kept = mutableListOf<Uri>()
            for (uri in uris) {
                var present = stillOnDevice(context, uri, paths[uri])
                if (present) {
                    // The system dialog said "deleted" but the file is still there (some phones/ROMs, or a file in a
                    // folder the media provider does not own): remove the file itself, then the leftover row.
                    paths[uri]?.let { runCatching { File(it).delete() } }
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    present = stillOnDevice(context, uri, paths[uri])
                }
                if (present) kept += uri else gone += uri
            }
            VerifyResult(gone, kept)
        }

    private fun stillOnDevice(context: Context, uri: Uri, path: String?): Boolean {
        if (path != null) return runCatching { File(path).exists() }.getOrDefault(false)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /** Which source uris copied successfully vs. failed. */
    data class CopyResult(val succeeded: List<Uri>, val failed: List<Uri>)

    /**
     * Copies each (sourceUri, fallbackTitle) in [tracks] into the SAF folder tree at
     * [destinationTree] (obtained via ActivityResultContracts.OpenDocumentTree). The real
     * on-disk file name is looked up per file; [fallbackTitle] (the track's title) is only
     * used to name the copy when that lookup fails. A same-named file already in the
     * destination is never overwritten — a " (2)", " (3)"... suffix is added instead.
     * Returns which source uris were copied, so the caller can optionally follow up with
     * [requestDelete] on the succeeded ones to turn this into a move.
     */
    suspend fun copyToFolder(
        context: Context,
        destinationTree: Uri,
        tracks: List<Pair<Uri, String>>,
        /** Called off the main thread, only when the whole-batch percent changes. Cancelling the coroutine stops the copy and removes the half-written file. */
        onProgress: ((CopyProgress) -> Unit)? = null
    ): CopyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val destDir = DocumentFile.fromTreeUri(context, destinationTree)
        if (destDir == null || !destDir.canWrite()) {
            return@withContext CopyResult(emptyList(), tracks.map { it.first })
        }
        val succeeded = mutableListOf<Uri>()
        val failed = mutableListOf<Uri>()
        val sizes = tracks.map { (uri, _) -> sizeOf(resolver, uri) }
        val total = sizes.sum()
        var doneBytes = 0L
        var lastPercent = -1
        for ((index, track) in tracks.withIndex()) {
            val (sourceUri, fallbackTitle) = track
            currentCoroutineContext().ensureActive()
            var created: DocumentFile? = null
            val ok = runCatching {
                val mime = resolver.getType(sourceUri) ?: "audio/*"
                val name = uniqueNameIn(destDir, resolveDisplayName(resolver, sourceUri, mime, fallbackTitle))
                val destFile = destDir.createFile(mime, name) ?: return@runCatching false
                created = destFile
                resolver.openInputStream(sourceUri).use { input ->
                    resolver.openOutputStream(destFile.uri).use { output ->
                        if (input == null || output == null) return@runCatching false
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            doneBytes += read
                            val percent = CopyProgress.percentOf(doneBytes, total)
                            if (onProgress != null && percent != lastPercent) {
                                lastPercent = percent
                                onProgress(CopyProgress(doneBytes, total, index + 1, tracks.size, fallbackTitle))
                            }
                        }
                    }
                }
                true
            }.getOrDefault(false)
            // A failed copy (disk full, cancelled...) must not leave a broken half file in the destination.
            if (!ok) runCatching { created?.delete() }
            if (ok) succeeded += sourceUri else failed += sourceUri
        }
        CopyResult(succeeded, failed)
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** Prefers the file's real on-disk name; falls back to the track title + an extension guessed from its mime type. */
    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri, mime: String, fallbackTitle: String): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        if (!queried.isNullOrBlank()) return queried
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
        val safeTitle = fallbackTitle.ifBlank { "track" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$safeTitle.$ext"
    }

    private fun uniqueNameIn(dir: DocumentFile, displayName: String): String {
        if (dir.findFile(displayName) == null) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var n = 2
        var candidate = "$base ($n)$ext"
        while (dir.findFile(candidate) != null) { n++; candidate = "$base ($n)$ext" }
        return candidate
    }
}
