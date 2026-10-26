package com.ghadirb.aimusic.library

import android.content.Context

/**
 * Folders the user does not want in the library (WhatsApp audio, app caches, ringtone packs...).
 * Pure matching + a tiny store. Excluding hides files from the library only; nothing is deleted from the phone.
 */
object ExcludedFolders {

    /**
     * Same folder, same key: Arabic ي/ك equal Persian ی/ک, and invisible characters (ZWNJ, ZWJ, direction marks,
     * BOM) are ignored, so a Persian folder name matches however the phone or the keyboard wrote it.
     */
    fun normalize(path: String): String {
        val sb = StringBuilder(path.length)
        for (ch in path.replace('\\', '/').trim()) {
            when (ch) {
                '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF', '\u202A', '\u202B', '\u202C' -> Unit
                '\u064A', '\u0649' -> sb.append('\u06CC')
                '\u0643' -> sb.append('\u06A9')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trimEnd('/').lowercase()
    }

    /** "/storage/emulated/0/Music/Podcasts" -> "music/podcasts" (also /sdcard/ and SD-card volumes). */
    fun stripVolume(path: String): String {
        val p = normalize(path)
        val m = Regex("^/(storage/(emulated/\\d+|[^/]+)|sdcard|mnt/sdcard)(/|$)").find(p) ?: return p.trimStart('/')
        return p.substring(m.range.last + 1).trimStart('/')
    }

    /** True when [path] (a file or folder) is one of [excluded] or lies inside one of them. */
    fun isExcluded(path: String?, excluded: Set<String>): Boolean {
        if (path.isNullOrBlank() || excluded.isEmpty()) return false
        val p = normalize(path)
        return excluded.any { ex ->
            val e = normalize(ex)
            e.isNotEmpty() && (p == e || p.startsWith("$e/"))
        }
    }

    /**
     * Video rows only know a volume-relative folder ("Movies/Family/"). A video is excluded when that folder is an
     * excluded folder or inside one; volumes are ignored (the same relative folder on SD card matches too).
     */
    fun isExcludedRelative(relativePath: String?, excluded: Set<String>): Boolean {
        if (relativePath.isNullOrBlank() || excluded.isEmpty()) return false
        val rel = normalize(relativePath).trimStart('/')
        if (rel.isEmpty()) return false
        return excluded.any { ex ->
            val e = stripVolume(ex)
            e.isNotEmpty() && (rel == e || rel.startsWith("$e/"))
        }
    }
}

class ExcludedFoldersStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("excluded_folders", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet("paths", emptySet()).orEmpty().toSet()

    fun save(paths: Set<String>) {
        prefs.edit().putStringSet("paths", paths.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.toSet()).apply()
    }
}
