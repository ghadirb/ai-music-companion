package com.ghadirb.aimusic.analysis

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Best-effort Persian lyric mood analysis (README AI ROADMAP item: "تحلیل متن
 * شعر فارسی"). Honest scope, stated up front:
 *
 * - There is no bundled lyrics database and no network call (per the doc's
 *   privacy rule, nothing may be sent off-device) — this only reads a plain
 *   `.lrc` sidecar file the user already has next to the audio file
 *   (same file name, `.lrc` extension — a common format many Persian music
 *   collections already use), via MediaStore's RELATIVE_PATH/DISPLAY_NAME
 *   (API 29+) to resolve the file location safely.
 * - If no `.lrc` file exists, this returns null and the track's mood stays
 *   whatever AudioAnalyzer derived from the audio itself — this is a
 *   refinement layer, not a requirement.
 * - Sentiment scoring is a small hand-built Persian keyword list, not a
 *   trained NLP model. It is intentionally conservative (ties -> null)
 *   rather than guessing.
 */
object LyricsAnalyzer {

    object LyricMood {
        const val SAD = "sad"
        const val HAPPY = "happy"
    }

    // Small, hand-picked keyword sets — good enough as a coarse signal, not a claim of NLP accuracy.
    private val sadWords = listOf(
        "غم", "غمگین", "اشک", "گریه", "دلتنگ", "تنها", "تنهایی", "درد", "رفتی", "جدایی",
        "حسرت", "داغ", "ماتم", "بغض", "شکست", "فراق", "سیاه", "دلشکسته"
    )
    private val happyWords = listOf(
        "شاد", "شادی", "خنده", "عشق", "جشن", "رقص", "خوشحال", "امید", "آرزو", "زیبا",
        "لبخند", "نور", "بهار", "جوانی", "شور", "پایکوبی"
    )

    /**
     * Returns [LyricMood.SAD] / [LyricMood.HAPPY], or null if no `.lrc` file was found
     * or the keyword counts were inconclusive (tied or both zero).
     */
    fun analyze(context: Context, trackUri: Uri): String? {
        val lrcFile = findSidecarLrc(context, trackUri) ?: return null
        if (!lrcFile.exists() || !lrcFile.canRead()) return null

        val text = try {
            lrcFile.readText()
        } catch (e: Exception) {
            return null
        }
        // Strip [mm:ss.xx] timestamps, keep the words.
        val plain = text.replace(Regex("\\[[0-9:.]+\\]"), " ")

        var sadCount = 0
        var happyCount = 0
        for (w in sadWords) if (plain.contains(w)) sadCount++
        for (w in happyWords) if (plain.contains(w)) happyCount++

        return when {
            sadCount > happyCount -> LyricMood.SAD
            happyCount > sadCount -> LyricMood.HAPPY
            else -> null
        }
    }

    /**
     * Resolves the on-disk path of a MediaStore audio content:// URI (API 29+ via
     * RELATIVE_PATH+DISPLAY_NAME, since DATA is deprecated) and looks for a same-name
     * `.lrc` file beside it. Returns null on any failure — this is best-effort only.
     */
    private fun findSidecarLrc(context: Context, trackUri: Uri): File? {
        return try {
            val projection = arrayOf(
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            context.contentResolver.query(trackUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (relPathCol < 0 || nameCol < 0) return null

                val relativePath = cursor.getString(relPathCol) ?: return null
                val displayName = cursor.getString(nameCol) ?: return null
                val baseName = displayName.substringBeforeLast('.', displayName)

                val storageRoot = android.os.Environment.getExternalStorageDirectory()
                File(File(storageRoot, relativePath), "$baseName.lrc")
            }
        } catch (e: Exception) {
            null
        }
    }
}
