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
}
