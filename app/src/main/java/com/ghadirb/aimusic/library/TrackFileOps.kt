package com.ghadirb.aimusic.library

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        data class NeedsConfirmation(val intentSender: IntentSender, val uris: List<Uri>) : DeleteOutcome
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
    suspend fun requestDelete(context: Context, uris: List<Uri>): DeleteOutcome = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext DeleteOutcome.Deleted(emptyList())
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, uris)
                DeleteOutcome.NeedsConfirmation(pendingIntent.intentSender, uris)
            } catch (e: Exception) {
                DeleteOutcome.Failed("درخواست حذف ساخته نشد.")
            }
        }
        val deleted = mutableListOf<Uri>()
        for (uri in uris) {
            try {
                if (resolver.delete(uri, null, null) > 0) deleted += uri
            } catch (e: RecoverableSecurityException) {
                // Android 10: this uri (and any after it) need the user's confirmation first.
                val remaining = uris.dropWhile { it != uri }
                return@withContext DeleteOutcome.NeedsConfirmation(e.userAction.actionIntent.intentSender, remaining)
            } catch (e: SecurityException) {
                return@withContext DeleteOutcome.Failed("دسترسی حذف این فایل داده نشد.")
            }
        }
        DeleteOutcome.Deleted(deleted)
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
        tracks: List<Pair<Uri, String>>
    ): CopyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val destDir = DocumentFile.fromTreeUri(context, destinationTree)
        if (destDir == null || !destDir.canWrite()) {
            return@withContext CopyResult(emptyList(), tracks.map { it.first })
        }
        val succeeded = mutableListOf<Uri>()
        val failed = mutableListOf<Uri>()
        for ((sourceUri, fallbackTitle) in tracks) {
            val ok = runCatching {
                val mime = resolver.getType(sourceUri) ?: "audio/*"
                val name = uniqueNameIn(destDir, resolveDisplayName(resolver, sourceUri, mime, fallbackTitle))
                val destFile = destDir.createFile(mime, name) ?: return@runCatching false
                resolver.openInputStream(sourceUri).use { input ->
                    resolver.openOutputStream(destFile.uri).use { output ->
                        if (input == null || output == null) return@runCatching false
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) succeeded += sourceUri else failed += sourceUri
        }
        CopyResult(succeeded, failed)
    }

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
