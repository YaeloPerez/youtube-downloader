package mx.devtech.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Moves a file already downloaded into the app's private cache dir into the
 * device's real public Downloads folder via MediaStore (API 29+, no storage
 * permission needed). This replaces the web app's "zip it, serve it, browser
 * downloads it" step (app.py's /file/{id}), which doesn't apply on-device —
 * here we just write straight into Downloads/<subfolder?>/<file>.
 */
object MediaStoreSaver {

    private val MIME_TYPES = mapOf(
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "ogg" to "audio/ogg",
    )

    fun mimeTypeFor(ext: String): String = MIME_TYPES[ext.lowercase()] ?: "application/octet-stream"

    /** [subfolder] is null for single-video downloads, or a sanitized
     * playlist name for playlist downloads (Downloads/<subfolder>/<file>). */
    fun saveToDownloads(context: Context, stagedFile: File, displayName: String, subfolder: String?): Uri {
        val ext = stagedFile.extension
        val relativePath = if (subfolder != null) {
            "${Environment.DIRECTORY_DOWNLOADS}/$subfolder"
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(ext))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")

        resolver.openOutputStream(itemUri).use { out ->
            requireNotNull(out) { "No se pudo abrir el archivo destino" }
            stagedFile.inputStream().use { input -> input.copyTo(out) }
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        stagedFile.delete()
        return itemUri
    }
}
