package com.ghadirb.aimusic.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the user's own local lyrics for a track WITHOUT any broad storage permission. Sources, in order:
 *  1. a .lrc the user imported for this song (private app storage);
 *  2. the same-name sidecar .lrc found through a music folder the user granted ONCE with the system folder
 *     picker (Storage Access Framework). The folder is indexed in the background and the index is cached on disk;
 *  3. (Android 9 and older only) the sidecar file by direct path;
 *  4. lyrics embedded in the audio file's own tags (MP3 ID3 USLT, FLAC).
 * Nothing here touches the network and no "All files access" permission is used.
 */
class LyricsSource(context: Context) {

    data class IndexStats(val folders: Int, val files: Int)
    private class TreeIndex(val builtAt: Long, val entries: Map<String, List<String>>)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun load(track: TrackEntity): LrcParser.Parsed? = withContext(Dispatchers.IO) {
        readRawText(track)?.let { LrcParser.parse(it) }?.takeUnless { it.isEmpty }
    }

    /** Raw decoded text of the best matching lyrics, or null. Blocking — call off the main thread. */
    fun readRawText(track: TrackEntity): String? {
        val sidecar = try { readBytes(track) } catch (e: Exception) {
            Log.w(TAG, "Lyrics lookup failed: ${e.javaClass.simpleName}")
            null
        }
        if (sidecar != null) return LrcParser.decode(sidecar)
        return embeddedRead(track)
    }

    private fun readBytes(track: TrackEntity): ByteArray? {
        importedFile(track.id).takeIf { it.isFile }?.let { return it.inputStream().use(::readLimited) }
        val keys = keysFor(track)
        safRead(track, keys)?.let { return it }
        return legacyDirectRead(track, keys)
    }

    // ---------------------------------------------------------------- user actions

    /** Copies a user-picked .lrc into private storage for [track]. Returns true if it contained lyrics. */
    suspend fun importFor(track: TrackEntity, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use(::readLimited) ?: return@withContext false
            val text = LrcParser.decode(bytes)
            if (LrcParser.parse(text).isEmpty) return@withContext false
            importedFile(track.id).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Remembers a folder the user granted (persisted permission). Call [refreshIndex] afterwards. */
    fun addFolder(treeUri: Uri): Boolean = try {
        appContext.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putStringSet(KEY_TREES, folders() + treeUri.toString()).apply()
        memory.remove(treeUri.toString())
        true
    } catch (e: Exception) {
        false
    }

    fun hasFolder(): Boolean = folders().isNotEmpty()
    fun folderCount(): Int = folders().size

    /** Re-scans every granted folder for .lrc files (bounded depth/size) and caches the result on disk. */
    fun refreshIndex(): IndexStats {
        val trees = folders()
        val fresh = trees.associateWith { buildIndex(Uri.parse(it)) }
        memory.clear()
        memory.putAll(fresh)
        saveToDisk(fresh)
        return IndexStats(trees.size, fresh.values.sumOf { index -> index.entries.values.sumOf { it.size } })
    }

    fun indexedFileCount(): Int {
        loadFromDiskIfNeeded()
        return folders().sumOf { t -> memory[t]?.entries?.values?.sumOf { it.size } ?: 0 }
    }

    private fun folders(): Set<String> = prefs.getStringSet(KEY_TREES, emptySet()).orEmpty()

    // ---------------------------------------------------------------- lookup

    private fun importedFile(trackId: Long) = File(File(appContext.filesDir, "lyrics"), "$trackId.lrc")

    private fun audioBaseName(track: TrackEntity): String? = try {
        appContext.contentResolver.query(
            Uri.parse(track.path), arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null }
    } catch (e: Exception) { null }

    private fun keysFor(track: TrackEntity): List<String> =
        LyricsFileMatcher.keysFor(audioBaseName(track), track.title, track.artist)

    private fun safRead(track: TrackEntity, keys: List<String>): ByteArray? {
        val trees = folders()
        if (trees.isEmpty()) return null
        ensureIndexes(trees)
        var uri = lookup(trees, keys, track)
        if (uri == null && isStale(trees)) {
            // A new .lrc may have been copied after the last scan: refresh at most once every few minutes.
            refreshIndex()
            uri = lookup(trees, keys, track)
        }
        return uri?.let {
            try { appContext.contentResolver.openInputStream(it)?.use(::readLimited) } catch (_: Exception) { null }
        }
    }

    private fun lookup(trees: Set<String>, keys: List<String>, track: TrackEntity): Uri? {
        val folderName = track.folderPath?.let { File(it).name }.orEmpty()
        for (key in keys) {
            for (tree in trees) {
                val matches = memory[tree]?.entries?.get(key) ?: continue
                val chosen = matches.firstOrNull { folderName.isNotEmpty() && Uri.decode(it).contains("/$folderName/") } ?: matches.first()
                return Uri.parse(chosen)
            }
        }
        return null
    }

    private fun isStale(trees: Set<String>): Boolean {
        val oldest = trees.mapNotNull { memory[it]?.builtAt }.minOrNull() ?: return true
        return System.currentTimeMillis() - oldest > REINDEX_AFTER_MS
    }

    private fun ensureIndexes(trees: Set<String>) {
        loadFromDiskIfNeeded()
        val missing = trees.filter { !memory.containsKey(it) }
        if (missing.isNotEmpty()) refreshIndex()
    }

    /** Pre-Android-10 only: read `<song>.lrc` straight from the song's folder. */
    private fun legacyDirectRead(track: TrackEntity, keys: List<String>): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        val dir = track.folderPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        return try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".lrc", ignoreCase = true) } ?: return null
            val picked = LyricsFileMatcher.pick(files.map { it.name }, keys) ?: return null
            files.firstOrNull { it.name == picked }?.takeIf { it.canRead() }?.inputStream()?.use(::readLimited)
        } catch (_: Exception) { null }
    }

    private fun embeddedRead(track: TrackEntity): String? = try {
        appContext.contentResolver.openInputStream(Uri.parse(track.path))?.use { EmbeddedLyricsReader.read(it) }
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- index build / persistence

    private fun buildIndex(tree: Uri): TreeIndex {
        val entries = HashMap<String, MutableList<String>>()
        try {
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
            var visited = 0
            while (stack.isNotEmpty() && visited < MAX_INDEXED_ENTRIES) {
                val (docId, depth) = stack.removeLast()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                appContext.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        visited++
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) stack.addLast(id to depth + 1)
                        } else {
                            LyricsFileMatcher.keyOfFile(name)?.let { key ->
                                entries.getOrPut(key) { mutableListOf() }.add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not index lyrics folder: ${e.javaClass.simpleName}")
        }
        return TreeIndex(System.currentTimeMillis(), entries)
    }

    private fun indexFile() = File(appContext.filesDir, "lyrics_index_v1.json")

    private fun saveToDisk(indexes: Map<String, TreeIndex>) {
        try {
            val root = JSONObject()
            for ((tree, index) in indexes) {
                val entries = JSONObject()
                for ((key, uris) in index.entries) entries.put(key, JSONArray(uris))
                root.put(tree, JSONObject().put("builtAt", index.builtAt).put("entries", entries))
            }
            indexFile().writeText(root.toString())
        } catch (_: Exception) { /* cache only: rebuilt on demand */ }
    }

    private fun loadFromDiskIfNeeded() {
        if (memory.isNotEmpty()) return
        try {
            val file = indexFile().takeIf { it.isFile } ?: return
            val root = JSONObject(file.readText())
            for (tree in root.keys()) {
                val node = root.getJSONObject(tree)
                val entries = node.getJSONObject("entries")
                val map = HashMap<String, List<String>>()
                for (key in entries.keys()) {
                    val array = entries.getJSONArray(key)
                    map[key] = List(array.length()) { array.getString(it) }
                }
                memory[tree] = TreeIndex(node.optLong("builtAt"), map)
            }
        } catch (_: Exception) { /* corrupt cache: rebuilt on demand */ }
    }

    // ---------------------------------------------------------------- diagnostics ("why wasn't it found?")

    /** Human-readable explanation of what was checked for [track]. Blocking. */
    fun diagnose(track: TrackEntity): String {
        val sb = StringBuilder()
        val keys = keysFor(track)
        sb.appendLine("آهنگ: ${track.title}")
        sb.appendLine("نسخهٔ اندروید: ${Build.VERSION.SDK_INT}")
        sb.appendLine("پوشهٔ آهنگ: ${track.folderPath ?: "نامشخص"}")
        sb.appendLine("نام‌های جست‌وجو: ${keys.take(3).joinToString(" | ")}")
        sb.appendLine("فایل متن واردشده برای این آهنگ: ${if (importedFile(track.id).isFile) "دارد" else "ندارد"}")
        val trees = folders()
        sb.appendLine("پوشه‌های انتخاب‌شده برای متن: ${trees.size}")
        if (trees.isEmpty()) {
            sb.appendLine("← هنوز پوشهٔ موسیقی را برای شناسایی متن انتخاب نکرده‌اید. «انتخاب پوشهٔ موسیقی» را بزنید.")
        } else {
            ensureIndexes(trees)
            val total = trees.sumOf { t -> memory[t]?.entries?.values?.sumOf { it.size } ?: 0 }
            sb.appendLine("تعداد فایل lrc پیداشده در پوشه‌های انتخابی: $total")
            if (total == 0) sb.appendLine("← در پوشهٔ انتخابی هیچ فایل .lrc دیده نشد. مطمئن شوید پوشهٔ بالاتر (مثلاً Music) را انتخاب کرده‌اید نه پوشهٔ دیگری.")
            else {
                val found = lookup(trees, keys, track)
                sb.appendLine(if (found != null) "فایل هم‌نام پیدا شد: ${Uri.decode(found.toString()).substringAfterLast('/')}" else "فایل هم‌نامی برای این آهنگ در فهرست نیست.")
                val sample = trees.flatMap { t -> memory[t]?.entries?.keys.orEmpty() }.take(4)
                if (found == null && sample.isNotEmpty()) sb.appendLine("نمونهٔ نام فایل‌های lrc موجود: ${sample.joinToString(" | ")}")
            }
        }
        val embedded = try { embeddedRead(track)?.isNotBlank() == true } catch (_: Exception) { false }
        sb.appendLine("متن داخل تگ فایل صوتی: ${if (embedded) "دارد" else "ندارد"}")
        return sb.toString().trim()
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw java.io.IOException("Lyrics file too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "LyricsSource"
        const val PREFS = "lyrics_source"
        const val KEY_TREES = "trees"
        const val MAX_BYTES = 1_000_000
        const val MAX_DEPTH = 8
        const val MAX_INDEXED_ENTRIES = 60_000
        const val REINDEX_AFTER_MS = 5 * 60_000L
        val memory = ConcurrentHashMap<String, TreeIndex>()
    }
}
