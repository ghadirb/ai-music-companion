package com.ghadirb.aimusic.lyrics

import android.os.Build

/**
 * The app no longer asks for "All files access". On Android 10+ the way to read `<song>.lrc` files that sit next to
 * the songs is to let the user pick their music folder ONCE with the system folder picker (scoped, revocable,
 * and no broad permission). Older Android versions can read the files directly.
 */
object StorageAccess {
    /** True when a one-time folder grant is needed for automatic sidecar-lyrics detection. */
    val needsFolderGrant: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
