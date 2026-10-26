package com.ghadirb.aimusic.library

/** Progress of a multi-file copy/move: bytes over all files, and which file is being copied. */
data class CopyProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String
) {
    /** 0..100; unknown total (size 0) reports 0 instead of dividing by zero. */
    val percent: Int get() = percentOf(doneBytes, totalBytes)

    companion object {
        fun percentOf(done: Long, total: Long): Int =
            if (total <= 0L) 0 else ((done.coerceIn(0L, total) * 100) / total).toInt()
    }
}
