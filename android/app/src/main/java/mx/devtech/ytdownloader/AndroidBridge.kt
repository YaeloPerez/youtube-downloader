package mx.devtech.ytdownloader

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * JS bridge exposed to the WebView as `window.AndroidBridge`. Every method
 * that touches yt-dlp/ffmpeg runs on a background coroutine; results and
 * progress are delivered back into the page via evaluateJavascript, calling
 * two global JS callbacks the compatibility shim in templates/index.html
 * wires up: window.__onDownloadEvent (single-video channel) and
 * window.__onPlaylistEvent (playlist channel) — both carry the exact same
 * `{status: ...}` JSON shapes the existing frontend already switches on, so
 * its onmessage handlers need zero changes.
 */
class AndroidBridge(
    private val context: Context,
    private val webView: WebView,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ytdl = YoutubeDlBridge(context)

    private val DOWNLOAD_PROCESS_ID = "download"
    private val PLAYLIST_PROCESS_ID = "playlist"

    private fun postToWebView(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** First run unzips python/ffmpeg (real I/O cost) — every method must
     * wait for that before touching YoutubeDL/FFmpeg, or it races a cold
     * start (e.g. the user pastes a URL and taps Analizar within the first
     * second of launch). */
    private suspend fun awaitReady() {
        (context.applicationContext as YtDownloaderApp).ready.await()
    }

    private fun emitAnalyze(json: String) = postToWebView("window.__onAnalyze(${jsStringLiteral(json)})")
    private fun emitDownloadEvent(json: String) = postToWebView("window.__onDownloadEvent(${jsStringLiteral(json)})")
    private fun emitPlaylistEvent(json: String) = postToWebView("window.__onPlaylistEvent(${jsStringLiteral(json)})")

    @JavascriptInterface
    fun readClipboard(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
    }

    @JavascriptInterface
    fun analyze(url: String) {
        scope.launch {
            awaitReady()
            val json = try {
                ytdl.analyze(url)
            } catch (e: Exception) {
                JSONObject().put("detail", e.message ?: "Error al analizar el video").toString()
            }
            emitAnalyze(json)
        }
    }

    @JavascriptInterface
    fun startDownload(url: String, formatId: String, extHint: String, title: String) {
        scope.launch {
            awaitReady()
            val downloadId = UUID.randomUUID().toString()
            val outputTemplate = File(context.cacheDir, "$downloadId.%(ext)s").absolutePath
            val needsFfmpeg = formatId.contains("+") || formatId.contains("bestvideo")

            try {
                ytdl.download(url, formatId, outputTemplate, needsFfmpeg, DOWNLOAD_PROCESS_ID) { p ->
                    emitDownloadEvent(
                        JSONObject().apply {
                            put("status", "downloading")
                            put("percent", "${p.percent}%")
                            put("speed", p.speed)
                            put("eta", if (p.etaSeconds > 0) "${p.etaSeconds / 60}:${(p.etaSeconds % 60).toString().padStart(2, '0')}" else "")
                            put("total", p.total)
                        }.toString(),
                    )
                }

                val outFile = context.cacheDir.listFiles { f -> f.name.startsWith("$downloadId.") }?.firstOrNull()
                    ?: throw IllegalStateException("Archivo no encontrado tras la descarga")
                val safeTitle = safeName(title)
                val displayName = "$safeTitle.${outFile.extension}"
                val uri = MediaStoreSaver.saveToDownloads(context, outFile, displayName, subfolder = null)

                emitDownloadEvent(
                    JSONObject().apply {
                        put("status", "complete")
                        put("download_id", uri.toString())
                        put("filename", displayName)
                    }.toString(),
                )
            } catch (e: Exception) {
                emitDownloadEvent(
                    JSONObject().apply {
                        put("status", "error")
                        put("message", e.message ?: "Error desconocido")
                    }.toString(),
                )
            }
        }
    }

    /** [entriesJson] is a JSON array of {index, url, title}, same shape the
     * frontend already builds for POST /playlist/download in app.py. */
    @JavascriptInterface
    fun startPlaylistDownload(qualityKey: String, entriesJson: String, playlistTitle: String) {
        scope.launch {
            awaitReady()
            val preset = PLAYLIST_QUALITY_PRESETS[qualityKey]
            if (preset == null) {
                emitPlaylistEvent(JSONObject().put("status", "video_error").put("message", "Calidad no válida").toString())
                return@launch
            }

            val entries = JSONArray(entriesJson)
            val total = entries.length()
            val safePlaylistName = safeName(playlistTitle, "playlist")
            var completed = 0

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val index = entry.getInt("index")
                val entryUrl = entry.getString("url")
                val entryTitle = entry.optString("title", "video")
                val stagingBase = "playlist_${safePlaylistName}_${index}_${UUID.randomUUID()}"
                val outputTemplate = File(context.cacheDir, "$stagingBase.%(ext)s").absolutePath

                try {
                    ytdl.download(entryUrl, preset.format, outputTemplate, preset.needsFfmpeg, PLAYLIST_PROCESS_ID) { p ->
                        emitPlaylistEvent(
                            JSONObject().apply {
                                put("status", "downloading")
                                put("video_index", index)
                                put("video_title", entryTitle)
                                put("percent", "${p.percent}%")
                                put("speed", p.speed)
                                put("eta", if (p.etaSeconds > 0) "${p.etaSeconds / 60}:${(p.etaSeconds % 60).toString().padStart(2, '0')}" else "")
                                put("total", p.total)
                            }.toString(),
                        )
                    }

                    val outFile = context.cacheDir.listFiles { f -> f.name.startsWith("$stagingBase.") }?.firstOrNull()
                        ?: throw IllegalStateException("Archivo no encontrado")
                    val displayName = "${index.toString().padStart(3, '0')} - ${safeName(entryTitle)}.${outFile.extension}"
                    MediaStoreSaver.saveToDownloads(context, outFile, displayName, subfolder = safePlaylistName)

                    completed += 1
                    emitPlaylistEvent(
                        JSONObject().apply {
                            put("status", "video_complete")
                            put("video_index", index)
                            put("video_title", entryTitle)
                            put("completed", completed)
                            put("total", total)
                        }.toString(),
                    )
                } catch (e: Exception) {
                    emitPlaylistEvent(
                        JSONObject().apply {
                            put("status", "video_error")
                            put("video_index", index)
                            put("video_title", entryTitle)
                            put("message", e.message ?: "Error desconocido")
                        }.toString(),
                    )
                }
            }

            emitPlaylistEvent(
                JSONObject().apply {
                    put("status", "all_complete")
                    put("playlist_name", safePlaylistName)
                }.toString(),
            )
        }
    }

    @JavascriptInterface
    fun cancelActiveJob() {
        ytdl.cancel(DOWNLOAD_PROCESS_ID)
        ytdl.cancel(PLAYLIST_PROCESS_ID)
    }

    /** [mediaUri] is the content:// Uri string handed back in the "complete"
     * event's download_id field. */
    @JavascriptInterface
    fun openFile(mediaUri: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(mediaUri), context.contentResolver.getType(Uri.parse(mediaUri)))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun openDownloadsApp() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Safely embeds a JSON string as a JS string-literal argument. */
internal fun jsStringLiteral(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
    return "'$escaped'"
}
