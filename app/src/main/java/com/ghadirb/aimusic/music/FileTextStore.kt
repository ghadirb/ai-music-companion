package com.ghadirb.aimusic.music

import java.io.File

/** Small JSON files in the app's private storage (written to a temp file first, so a crash never leaves half a file). */
class FileTextStore(private val file: File) : TextStore {
    override fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    override fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        }
    }
}
