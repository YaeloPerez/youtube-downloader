package mx.devtech.ytdownloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Kotlin port of app.py's yt-dlp helpers: _parse_formats, _safe_name,
 * _fmt_size, PLAYLIST_QUALITY_PRESETS, and the /info route's playlist vs.
 * single-video branching. Output JSON shapes are kept identical to the
 * Python backend's so templates/index.html's JS needs no changes to consume
 * them (see parseFormat() in the frontend).
 */

data class QualityPreset(
    val format: String,
    val label: String,
    val type: String,
    val needsFfmpeg: Boolean,
)

// Mirrors PLAYLIST_QUALITY_PRESETS in app.py exactly (same keys, same format
// strings) — used both for playlist downloads and as the shared preset list.
val PLAYLIST_QUALITY_PRESETS: Map<String, QualityPreset> = linkedMapOf(
    "best" to QualityPreset(
        format = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
        label = "Mejor calidad (mp4)",
        type = "video",
        needsFfmpeg = true,
    ),
    "1080p" to QualityPreset(
        format = "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]",
        label = "Hasta 1080p (mp4)",
        type = "video",
        needsFfmpeg = true,
    ),
    "720p" to QualityPreset(
        format = "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]",
        label = "Hasta 720p (mp4)",
        type = "video",
        needsFfmpeg = true,
    ),
    "audio" to QualityPreset(
        format = "bestaudio[ext=m4a]/bestaudio/best",
        label = "Solo audio (m4a)",
        type = "audio",
        needsFfmpeg = false,
    ),
)

/** Mirrors _safe_name() in app.py — alphanumeric plus " -_()[]" only. */
fun safeName(name: String?, fallback: String = "video"): String {
    val cleaned = (name ?: "").filter { it.isLetterOrDigit() || it in " -_()[]" }.trim()
    return cleaned.ifEmpty { fallback }
}

/** Mirrors _fmt_size() in app.py. */
fun fmtSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    return when {
        bytes >= 1_000_000_000L -> " ~%.1fGB".format(bytes / 1e9)
        bytes >= 1_000_000L -> " ~%.0fMB".format(bytes / 1e6)
        else -> " ~%.0fKB".format(bytes / 1e3)
    }
}

/** Mirrors the M:SS / "--" duration formatting used throughout app.py. */
fun fmtDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "--"
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

class YoutubeDlBridge(private val context: Context) {
    private val ytdl get() = YoutubeDL.getInstance()

    /**
     * Mirrors /info in app.py: always requests --dump-single-json and
     * --flat-playlist (flat only affects playlist *entries* — a direct
     * video URL still returns full formats, same comment as app.py:200-201).
     *
     * getInfo()/VideoInfo cannot represent playlists (--dump-json prints one
     * object per line for a playlist, and VideoInfo has no entries field),
     * so this issues the request manually via execute() and parses the raw
     * JSON with the library's own Jackson ObjectMapper instead.
     */
    fun analyze(url: String): String {
        val request = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--flat-playlist")
        val response = ytdl.execute(request)
        val root = ytdl.objectMapper.readTree(response.out)

        if (root.get("_type")?.asText() == "playlist") {
            val entries = JSONArray()
            var index = 0
            (root.get("entries") ?: return errorJson("Playlist vacía")).forEach { entry ->
                index += 1
                val id = entry.get("id")?.asText()
                val durationSeconds = entry.get("duration")?.let { if (it.isNumber) it.asInt() else null }
                val thumbs = entry.get("thumbnails")
                val thumbnail = if (thumbs != null && thumbs.isArray && thumbs.size() > 0) {
                    thumbs[thumbs.size() - 1].get("url")?.asText()
                } else {
                    entry.get("thumbnail")?.asText()
                }
                entries.put(
                    JSONObject().apply {
                        put("index", index)
                        put("id", id ?: JSONObject.NULL)
                        put(
                            "url",
                            entry.get("url")?.asText()
                                ?: id?.let { "https://www.youtube.com/watch?v=$it" }
                                ?: JSONObject.NULL,
                        )
                        put("title", entry.get("title")?.asText() ?: "Video")
                        put("duration", fmtDuration(durationSeconds))
                        put("thumbnail", thumbnail ?: JSONObject.NULL)
                    },
                )
            }
            return JSONObject().apply {
                put("type", "playlist")
                put("title", root.get("title")?.asText() ?: "Playlist")
                put("uploader", root.get("uploader")?.asText() ?: "")
                put("video_count", entries.length())
                put("entries", entries)
            }.toString()
        }

        val info = ytdl.objectMapper.treeToValue(root, VideoInfo::class.java)
        return JSONObject().apply {
            put("type", "video")
            put("title", info.title ?: "Video")
            put("thumbnail", info.thumbnail ?: "")
            put("duration", fmtDuration(info.duration))
            put("uploader", info.uploader ?: "")
            put("formats", parseFormats(info))
        }.toString()
    }

    /** Line-for-line port of _parse_formats() in app.py:125-187. */
    private fun parseFormats(info: VideoInfo): JSONArray {
        val result = JSONArray()
        result.put(
            JSONObject().apply {
                put("format_id", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best")
                put("label", "Mejor calidad (mp4)")
                put("type", "video")
                put("ext", "mp4")
                put("needs_ffmpeg", true)
            },
        )
        result.put(
            JSONObject().apply {
                put("format_id", "bestaudio[ext=m4a]/bestaudio/best")
                put("label", "Solo audio (m4a)")
                put("type", "audio")
                put("ext", "m4a")
                put("needs_ffmpeg", false)
            },
        )

        val seenVideo = HashSet<Pair<Int, String>>()
        val seenAudio = HashSet<Pair<String, Int>>()

        val formats: List<VideoFormat> = (info.formats ?: arrayListOf())
            .sortedByDescending { it.height }

        for (f in formats) {
            val vcodec = f.vcodec ?: "none"
            val acodec = f.acodec ?: "none"
            val height = f.height
            val ext = f.ext ?: ""
            val fid = f.formatId ?: ""
            val size = fmtSize(if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate)

            // Restricted to mp4/m4a on Android (vs. also offering webm/ogg/opus
            // on the web app) — simpler, universally-compatible choices for
            // a phone: every Android player/app handles these natively.
            if (vcodec != "none" && height > 0 && ext == "mp4") {
                val key = height to ext
                if (seenVideo.add(key)) {
                    val hasAudio = acodec != "none" && acodec.isNotEmpty()
                    val needsFfmpeg = !hasAudio
                    val combinedFid = if (hasAudio) fid else "$fid+bestaudio[ext=m4a]/bestaudio"
                    result.put(
                        JSONObject().apply {
                            put("format_id", combinedFid)
                            put("label", "${height}p ($ext)$size")
                            put("type", "video")
                            put("ext", if (needsFfmpeg) "mp4" else ext)
                            put("needs_ffmpeg", needsFfmpeg)
                        },
                    )
                }
            } else if (vcodec == "none" && acodec != "none" && acodec.isNotEmpty() && ext == "m4a") {
                val abr = f.abr
                val key = ext to abr
                if (seenAudio.add(key)) {
                    val labelAbr = if (abr > 0) " ${abr}kbps" else ""
                    result.put(
                        JSONObject().apply {
                            put("format_id", fid)
                            put("label", "Audio$labelAbr ($ext)$size")
                            put("type", "audio")
                            put("ext", ext)
                            put("needs_ffmpeg", false)
                        },
                    )
                }
            }
        }
        return result
    }

    private fun errorJson(message: String): String =
        JSONObject().apply { put("error", message) }.toString()

    // ── downloading ──────────────────────────────────────────────────────

    /** Raw yt-dlp progress line → (speed, totalSize), the two fields the
     * library's own (percent, eta) callback doesn't provide (verified by
     * reading StreamProcessExtractor's own regex, which only extracts
     * percent/ETA). Matches a line like:
     * "[download]  45.2% of ~12.34MiB at 1.23MiB/s ETA 00:10" */
    private val speedSizePattern: Pattern =
        Pattern.compile("of\\s+~?([\\d.]+\\w+)\\s+at\\s+([\\d.]+\\w+/s)")

    data class ProgressUpdate(val percent: Float, val etaSeconds: Long, val speed: String, val total: String)

    fun parseProgressLine(percent: Float, etaSeconds: Long, rawLine: String): ProgressUpdate {
        val m = speedSizePattern.matcher(rawLine)
        return if (m.find()) {
            ProgressUpdate(percent, etaSeconds, m.group(2) ?: "", m.group(1) ?: "")
        } else {
            ProgressUpdate(percent, etaSeconds, "", "")
        }
    }

    /** Downloads one video/entry with the given yt-dlp format string into
     * [outputTemplate] (a full path with a %(ext)s placeholder, matching
     * app.py's outtmpl usage), reporting progress via [onProgress] and
     * returning the actual downloaded File on success. */
    fun download(
        url: String,
        formatId: String,
        outputTemplate: String,
        needsFfmpeg: Boolean,
        processId: String,
        onProgress: (ProgressUpdate) -> Unit,
    ) {
        val request = YoutubeDLRequest(url)
            .addOption("-f", formatId)
            .addOption("-o", outputTemplate)
        if (needsFfmpeg) {
            request.addOption("--merge-output-format", "mp4")
        }
        ytdl.execute(request, processId) { percent, eta, line ->
            onProgress(parseProgressLine(percent, eta, line))
        }
    }

    fun cancel(processId: String): Boolean = ytdl.destroyProcessById(processId)
}
