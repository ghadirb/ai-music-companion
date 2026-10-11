package com.ghadirb.aimusic.ui.common

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.ghadirb.aimusic.library.TrackFileOps
import kotlinx.coroutines.launch

/**
 * Real file deletion for songs and videos, shared by every screen.
 *
 * Android 11+: one system confirmation deletes the whole batch.
 * Android 10: the system confirmation only GRANTS access to one file, it does not delete it, so after
 * each "Allow" the delete is retried (until the batch is done). Only files that were really deleted are
 * reported to [onDeleted] - nothing is removed from the app's lists unless the file is gone from the phone.
 *
 * Returns the function to call with the content:// uris to delete.
 */
@Composable
fun rememberDeleteFlow(
    onDeleted: (List<Uri>) -> Unit,
    onMessage: (String) -> Unit
): (List<Uri>) -> Unit {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleted by rememberUpdatedState(onDeleted)
    val message by rememberUpdatedState(onMessage)
    // Plain holders (not Compose state): they are read from callbacks only, never during composition.
    val pending = remember { arrayOfNulls<TrackFileOps.DeleteOutcome.NeedsConfirmation>(1) }
    val launcherRef = remember { arrayOfNulls<ActivityResultLauncher<IntentSenderRequest>>(1) }

    // Paths are read before deleting (the rows vanish afterwards) and used to prove the files are really gone.
    val paths = remember { HashMap<Uri, String>() }

    /** The system says [uris] were deleted: only those really gone from the phone are reported to the caller. */
    fun report(uris: List<Uri>, expected: Int) {
        scope.launch {
            val verified = TrackFileOps.verifyDeleted(context, uris, paths)
            if (verified.gone.isNotEmpty()) deleted(verified.gone)
            when {
                verified.kept.isNotEmpty() ->
                    message("${verified.kept.size} فایل از حافظهٔ گوشی پاک نشد و در فهرست باقی ماند.")
                uris.size < expected -> message("بعضی فایل‌ها حذف نشدند.")
            }
        }
    }

    fun step(uris: List<Uri>, deletedSoFar: List<Uri>) {
        scope.launch {
            val unknown = uris.filter { it !in paths }
            if (unknown.isNotEmpty()) paths.putAll(TrackFileOps.pathsOf(context, unknown))
            when (val outcome = TrackFileOps.requestDelete(context, uris, deletedSoFar)) {
                is TrackFileOps.DeleteOutcome.Deleted -> {
                    if (outcome.uris.isNotEmpty()) report(outcome.uris, uris.size + deletedSoFar.size)
                    else message("بعضی فایل‌ها حذف نشدند.")
                }
                is TrackFileOps.DeleteOutcome.NeedsConfirmation -> {
                    pending[0] = outcome
                    launcherRef[0]?.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                }
                is TrackFileOps.DeleteOutcome.Failed -> {
                    if (deletedSoFar.isNotEmpty()) report(deletedSoFar, deletedSoFar.size)
                    message(outcome.reason)
                }
            }
        }
    }

    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val request = pending[0]
        pending[0] = null
        if (request == null) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // createDeleteRequest should have deleted every file in the batch - verified, not assumed.
                val all = request.deletedSoFar + request.uris
                report(all, all.size)
            } else {
                // Android 10: access granted for one file - actually delete it now and continue with the rest.
                step(request.uris, request.deletedSoFar)
            }
        } else {
            if (request.deletedSoFar.isNotEmpty()) report(request.deletedSoFar, request.deletedSoFar.size)
            message("حذف لغو شد.")
        }
    }
    launcherRef[0] = activityLauncher

    return { uris -> if (uris.isNotEmpty()) step(uris, emptyList()) }
}
