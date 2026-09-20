package com.ghadirb.aimusic.crash

import android.content.Context
import android.os.Build
import com.ghadirb.aimusic.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash report: the last uncaught exception is written to app-private storage (scrubbed of file
 * paths/URIs). It is never uploaded; the user can share or delete it from Settings.
 */
object CrashLogger {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { file(app).apply { parentFile?.mkdirs() }.writeText(report(thread.name, error)) } catch (_: Throwable) { /* never mask the real crash */ }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastReport(context: Context): String? = file(context.applicationContext).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { file(context.applicationContext).delete() }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    internal fun report(threadName: String, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return scrub(
            "AI Music Companion crash report\nTime: $time\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Android: ${Build.VERSION.SDK_INT}  Device: ${Build.MANUFACTURER} ${Build.MODEL}\nThread: $threadName\n\n$trace"
        )
    }

    /** Removes content URIs, file paths, e-mail addresses and long hex/base64-looking tokens. */
    internal fun scrub(text: String): String = text
        .replace(Regex("""content://\S+"""), "content://<redacted>")
        .replace(Regex("""file://\S+"""), "file://<redacted>")
        .replace(Regex("""/(storage|sdcard|data|mnt)/[^\s:)\]]+"""), "/<redacted-path>")
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.-]+"""), "<email>")
        .replace(Regex("""\b[A-Za-z0-9_\-]{32,}\b"""), "<token>")
}
