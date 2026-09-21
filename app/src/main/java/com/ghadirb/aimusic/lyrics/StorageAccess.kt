package com.ghadirb.aimusic.lyrics

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/**
 * The app no longer asks for "All files access". On Android 10+ the way to read `<song>.lrc` files that sit next to
 * the songs is to let the user pick their music folder ONCE with the system folder picker (scoped, revocable,
 * and no broad permission). Older Android versions can read the files directly.
 */
object StorageAccess {
    /** True when a one-time folder grant is needed for automatic sidecar-lyrics detection. */
    val needsFolderGrant: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** "/storage/emulated/0/Music/X" -> "primary:Music/X"; "/storage/1A2B-3C4D/Music" -> "1A2B-3C4D:Music"; else null. */
    fun documentIdFor(path: String?): String? {
        if (path.isNullOrBlank()) return null
        Regex("""^/(?:storage/emulated/0|sdcard)(?:/(.*))?$""").find(path)?.let { return "primary:" + it.groupValues[1] }
        Regex("""^/storage/([^/]+)(?:/(.*))?$""").find(path)?.let { m ->
            val volume = m.groupValues[1]
            if (volume != "emulated" && volume != "self") return "$volume:" + m.groupValues[2]
        }
        return null
    }

    /** Where the folder picker should open (the song's own folder), so the user only has to tap "Use this folder". */
    fun initialTreeUri(folderPath: String?): Uri? =
        documentIdFor(folderPath)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it) }
}
