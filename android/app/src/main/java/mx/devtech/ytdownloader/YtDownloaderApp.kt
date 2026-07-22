package mx.devtech.ytdownloader

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "YtDownloaderApp"

class YtDownloaderApp : Application() {

    // First run has to unzip the bundled python/ffmpeg distributions, which
    // takes real time — every bridge call that touches YoutubeDL/FFmpeg must
    // await this before doing anything, or it races a cold start.
    val ready = CompletableDeferred<Boolean>()

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                YoutubeDL.getInstance().init(this@YtDownloaderApp)
                FFmpeg.getInstance().init(this@YtDownloaderApp)
                ready.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp/ffmpeg init failed", e)
                ready.complete(false)
                return@launch
            }
            // The bundled yt-dlp binary is only as fresh as the library's
            // last release — YouTube's player changes constantly, and a
            // stale yt-dlp fails to solve its "n challenge" (403s on every
            // download). Update to the latest stable build on every start;
            // harmless no-op if already current, and non-fatal if offline
            // (falls back to whatever's already bundled/installed).
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(this@YtDownloaderApp)
                Log.i(TAG, "yt-dlp update status: $status")
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update check failed, continuing with existing binary", e)
            }
        }
    }
}
