package com.ghadirb.aimusic.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the user's own local lyrics (.lrc) for a track. Sources, in priority order:
 *  1. a file the user imported for this track (private app storage, works on every Android version);
 *  2. the sidecar file next to the audio file (direct path; works on older Android / with legacy access);
 *  3. the MediaStore "files" collection;
 *  4. a folder the user granted once through the system folder picker (Storage Access Framework).
 *
 * Why this exists: on Android 10+ (scoped storage) an app with only the audio permission can NOT open
 * a .lrc file by its path, which is why the earlier "read the file next to the song" approach showed nothing.
 * Nothing here touches the network.
 */
class LyricsSource(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun load(track: TrackEntity): LrcParser.Parsed? = withContext(Dispatchers.IO) {
        readRawText(track)?.let { LrcParser.parse(it) }?.takeUnless { it.isEmpty }
    }

    /** Raw decoded text of the best matching lyrics file, or null. Blocking — call off the main thread. */
    fun readRawText(track: TrackEntity): String? {
        val bytes = try { readBytes(track) } catch (e: Exception) {
            Log.w(TAG, "Lyrics lookup failed: ${e.javaClass.simpleName}")
            null
        }
        return bytes?.let { LrcParser.decode(it) }
    }

    private fun readBytes(track: TrackEntity): ByteArray? {
        importedFile(track.id).takeIf { it.isFile }?.let { return it.inputStream().use(::readLimited) }

        val baseName = audioBaseName(track)
        val names = candidateNames(track, baseName)
        directRead(track, names)?.let { return it }
        mediaStoreRead(track, names)?.let { return it }
        return safRead(track, names)
    }

    // ---- user actions ----

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

    /** Remembers a folder (music or lyrics folder) the user granted, so .lrc files inside it are found automatically. */
    fun addFolder(treeUri: Uri): Boolean {
        return try {
            appContext.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putStringSet(KEY_TREES, folders() + treeUri.toString()).apply()
            treeIndex.remove(treeUri.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasFolder(): Boolean = folders().isNotEmpty()

    private fun folders(): Set<String> = prefs.getStringSet(KEY_TREES, emptySet()).orEmpty()

    // ---- lookup strategies ----

    private fun importedFile(trackId: Long) = File(File(appContext.filesDir, "lyrics"), "$trackId.lrc")

    private fun audioBaseName(track: TrackEntity): String? = try {
        appContext.contentResolver.query(
            Uri.parse(track.path), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (e: Exception) { null }

    private fun candidateNames(track: TrackEntity, baseName: String?): List<String> {
        val names = LinkedHashSet<String>()
        baseName?.takeIf { it.isNotBlank() }?.let { names.add("$it.lrc") }
        if (track.artist.isNotBlank() && track.artist != "Unknown artist") names.add("${track.artist} - ${track.title}.lrc")
        names.add("${track.title}.lrc")
        return names.toList()
    }

    private fun directRead(track: TrackEntity, names: List<String>): ByteArray? {
        val dir = track.folderPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        for (name in names) {
            try {
                val file = File(dir, name)
                if (file.isFile && file.canRead()) return file.inputStream().use(::readLimited)
            } catch (_: Exception) { /* not readable under scoped storage: try the next strategy */ }
        }
        // Case-insensitive match (".LRC", different capitalisation).
        return try {
            val wanted = names.map { it.lowercase() }.toSet()
            dir.listFiles()?.firstOrNull { it.isFile && it.name.lowercase() in wanted && it.canRead() }
                ?.inputStream()?.use(::readLimited)
        } catch (_: Exception) { null }
    }

    private fun mediaStoreRead(track: TrackEntity, names: List<String>): ByteArray? {
        val filesUri = MediaStore.Files.getContentUri("external")
        for (name in names) {
            try {
                appContext.contentResolver.query(
                    filesUri, arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?", arrayOf(name), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = android.content.ContentUris.withAppendedId(filesUri, c.getLong(0))
                        appContext.contentResolver.openInputStream(uri)?.use(::readLimited)?.let { return it }
                    }
                }
            } catch (_: Exception) { /* provider may deny access: fall through */ }
        }
        return null
    }

    private fun safRead(track: TrackEntity, names: List<String>): ByteArray? {
        val folderName = track.folderPath?.let { File(it).name }.orEmpty()
        for (tree in folders()) {
            val index = treeIndex.getOrPut(tree) { buildIndex(Uri.parse(tree)) }
            for (name in names) {
                val matches = index[name.lowercase()] ?: continue
                val chosen = matches.firstOrNull { folderName.isNotEmpty() && it.toString().contains(Uri.encode(folderName)) }
                    ?: matches.first()
                try {
                    appContext.contentResolver.openInputStream(chosen)?.use(::readLimited)?.let { return it }
                } catch (_: Exception) { /* permission revoked or file gone */ }
            }
        }
        return null
    }

    /** Lists every .lrc under [tree] once (depth/size bounded) and caches name -> document URIs. */
    private fun buildIndex(tree: Uri): Map<String, List<Uri>> {
        val result = HashMap<String, MutableList<Uri>>()
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
                        } else if (name.endsWith(".lrc", ignoreCase = true)) {
                            result.getOrPut(name.lowercase()) { mutableListOf() }
                                .add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not index lyrics folder: ${e.javaClass.simpleName}")
        }
        return result
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
        val treeIndex = ConcurrentHashMap<String, Map<String, List<Uri>>>()
    }
}
