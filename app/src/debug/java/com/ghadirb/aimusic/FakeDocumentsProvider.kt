package com.ghadirb.aimusic

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * Test double for the system's ExternalStorageProvider: serves a folder tree from the app's cache directory through the
 * Storage Access Framework, so LyricsSource's real DocumentsContract code path can be exercised on an emulator.
 * Document ids are paths relative to the root ("" = root).
 */
class FakeDocumentsProvider : DocumentsProvider() {
    private fun root(): File = File(context!!.cacheDir, "fakedocs").also { it.mkdirs() }
    private fun fileFor(id: String): File = if (id == "root" || id.isEmpty()) root() else File(root(), id.removePrefix("root/"))
    private fun idFor(file: File): String = if (file == root()) "root" else "root/" + file.relativeTo(root()).path.replace('\\', '/')

    private val defaultDocumentProjection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root").add(Root.COLUMN_TITLE, "Fake").add(Root.COLUMN_FLAGS, 0)
        }

    private fun MatrixCursor.addFile(file: File) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idFor(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == root()) "Fake" else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: defaultDocumentProjection).apply { addFile(fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: defaultDocumentProjection).apply { fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { addFile(it) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith(parentDocumentId)

    companion object { const val AUTHORITY = "com.ghadirb.aimusic.test.fakedocs" }
}
