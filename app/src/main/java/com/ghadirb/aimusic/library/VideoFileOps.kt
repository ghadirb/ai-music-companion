package com.ghadirb.aimusic.library

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renaming a video file through MediaStore (the only way that works under scoped storage). The MediaStore id does
 * not change, so favourites, playlists and watch history stay attached to the renamed file.
 * Copy / move reuse [TrackFileOps.copyToFolder] (system folder picker) and the shared delete flow.
 */
object VideoFileOps {

    sealed interface RenameOutcome {
        data object Done : RenameOutcome
        /** The system must ask the user first; launch [intentSender] and call [rename] again after RESULT_OK. */
        data class NeedsPermission(val intentSender: IntentSender) : RenameOutcome
        data class Failed(val reason: String) : RenameOutcome
    }

    suspend fun rename(context: Context, uri: Uri, newName: String): RenameOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            if (resolver.update(uri, values, null, null) > 0) RenameOutcome.Done
            else RenameOutcome.Failed("تغییر نام انجام نشد.")
        } catch (e: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    runCatching { MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender }.getOrNull()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> recoverable(e)
                else -> null
            }
            if (sender != null) RenameOutcome.NeedsPermission(sender)
            else RenameOutcome.Failed("اجازهٔ تغییر نام این فایل داده نشد.")
        } catch (e: Exception) {
            RenameOutcome.Failed("تغییر نام انجام نشد؛ شاید فایلی با این نام وجود دارد.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoverable(e: SecurityException): IntentSender? =
        (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

    sealed interface MoveOutcome {
        /** Files were moved by MediaStore itself: same ids, so favourites / playlists / history stay attached. */
        data class Done(val moved: Int) : MoveOutcome
        data class NeedsPermission(val intentSender: IntentSender) : MoveOutcome
        /** This device/destination cannot do an in-place move; the caller falls back to copy + delete. */
        data class Unsupported(val reason: String) : MoveOutcome
    }

    /**
     * Moves [uris] to [relativePath] (e.g. "Movies/Family/") by updating MediaStore RELATIVE_PATH. Android 10+ only.
     * The file is moved on disk by the system and keeps its MediaStore id, so nothing in the app needs migrating.
     * After a permission dialog (RESULT_OK) call this again with the same uris; already moved ones are skipped.
     */
    suspend fun moveToRelativePath(context: Context, uris: List<Uri>, relativePath: String): MoveOutcome =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext MoveOutcome.Unsupported("انتقال مستقیم نیاز به اندروید ۱۰ یا بالاتر دارد.")
            }
            val resolver = context.contentResolver
            var moved = 0
            for (uri in uris) {
                try {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
                    if (resolver.update(uri, values, null, null) > 0) moved++
                } catch (e: SecurityException) {
                    val sender = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                            runCatching { MediaStore.createWriteRequest(resolver, uris).intentSender }.getOrNull()
                        else -> recoverable(e)
                    }
                    return@withContext if (sender != null) MoveOutcome.NeedsPermission(sender)
                    else MoveOutcome.Unsupported("اجازهٔ انتقال این فایل داده نشد.")
                } catch (e: Exception) {
                    // e.g. destination not allowed for videos, or another volume
                    return@withContext MoveOutcome.Unsupported("انتقال مستقیم به این پوشه ممکن نیست.")
                }
            }
            if (moved == 0) MoveOutcome.Unsupported("هیچ فایلی منتقل نشد.") else MoveOutcome.Done(moved)
        }
}
