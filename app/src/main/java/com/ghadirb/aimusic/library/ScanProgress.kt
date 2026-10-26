package com.ghadirb.aimusic.library

/** Progress of a library scan: rows of Android's media database looked at so far, out of all rows. Pure logic. */
data class ScanProgress(val done: Int, val total: Int) {
    /** 0..100; unknown total reports 0 (the UI then shows the indeterminate bar). */
    val percent: Int get() = if (total <= 0) 0 else ((done.coerceIn(0, total).toLong() * 100) / total).toInt()
    val known: Boolean get() = total > 0

    companion object {
        const val REPORT_EVERY = 50

        /** Rows are reported in steps (and always the last one) so the UI is not flooded on big libraries. */
        fun shouldReport(done: Int, total: Int): Boolean = done >= total || done % REPORT_EVERY == 0
    }
}
