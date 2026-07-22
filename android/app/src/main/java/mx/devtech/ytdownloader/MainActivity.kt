package mx.devtech.ytdownloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var statusBarPx: Int = 0
    private var navBarPx: Int = 0
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)
        webView.setBackgroundColor(android.graphics.Color.parseColor("#16211F"))

        // WebView.setPadding() does NOT push the internal Chromium-rendered
        // page content down (it's a View-bounds thing, not a page-layout
        // thing) — so on Android 15+'s edge-to-edge-by-default windows, the
        // page's own header renders right under the status bar clock/icons.
        // Instead, feed the real inset sizes into the page as CSS variables
        // and let its own stylesheet pad the header (see templates/index.html,
        // --android-status-bar-height / --android-nav-bar-height).
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarPx = bars.top
            navBarPx = bars.bottom
            applyInsetsToPage()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                applyInsetsToPage()
            }
        }

        webView.settings.javaScriptEnabled = true
        // Off by default — without it, the web app's localStorage-backed
        // settings/history (templates/index.html) silently stop persisting.
        webView.settings.domStorageEnabled = true
        // Off by default — without these, WebView ignores the page's own
        // <meta name="viewport"> and reflows text to one word per line.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        webView.addJavascriptInterface(AndroidBridge(this, webView), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun applyInsetsToPage() {
        if (!pageLoaded) return
        val density = resources.displayMetrics.density
        val topDp = statusBarPx / density
        val bottomDp = navBarPx / density
        webView.evaluateJavascript(
            "document.documentElement.style.setProperty('--android-status-bar-height', '${topDp}px');" +
                "document.documentElement.style.setProperty('--android-nav-bar-height', '${bottomDp}px');",
            null,
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
