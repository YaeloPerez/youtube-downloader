# Vault (Android)

Native Android port of the web app, so each family member can install it on
their own phone and download using their own WiFi/mobile IP — the reason
this exists is that AWS/any datacenter IP gets challenged by YouTube's
bot-detection far more than a residential IP does (see the root
`deploy/README.md` for the full story on the server side). Running on-device
sidesteps that problem entirely, the same way testing locally on a Mac never
hit it.

**This does not fix `LOGIN_REQUIRED` / age-restricted content** — that's a
YouTube content-gating rule, not an IP-reputation one, and persists
regardless of platform.

## Why this architecture

The obvious first idea — embed the existing Python/FastAPI backend directly
via Chaquopy — was ruled out: **ffmpeg-kit**, the standard way any Android
app bundles ffmpeg, was deprecated/unmaintained in 2025, making hand-rolling
ffmpeg-for-Android fragile. Instead this uses
[**youtubedl-android**](https://github.com/yausername/youtubedl-android)
(`io.github.junkfood02.youtubedl-android`), an actively maintained library
that already bundles a working yt-dlp + ffmpeg for Android.

The UI is **not** rewritten natively — `templates/index.html` (the same file
the web app serves) is loaded into a `WebView` as-is, bridged to Kotlin via
`@JavascriptInterface` instead of talking to a real HTTP server. This
preserves the entire existing frontend (themes, format pickers, playlist
selection, progress bars, history) with no native UI rebuild.

```
templates/index.html (WebView, unchanged UI)
        │  window.fetch / window.EventSource — monkey-patched by a guarded
        │  shim at the top of the <script> tag (inert on the real web app)
        ▼
AndroidBridge.kt (@JavascriptInterface)
        │
YoutubeDlBridge.kt — Kotlin ports of app.py's _parse_formats /
        │             PLAYLIST_QUALITY_PRESETS / _safe_name
        ▼
youtubedl-android (bundled yt-dlp + ffmpeg)
        │
MediaStore.Downloads (real Downloads/ folder, Downloads/<playlist>/ for playlists)
```

## Key implementation notes

- **`YoutubeDL.getInstance().init()` and `FFmpeg.getInstance().init()` are
  separate calls** — the library doesn't set up ffmpeg as part of the main
  init. Both run in `YtDownloaderApp.onCreate()`, off the main thread; every
  bridge method awaits a `CompletableDeferred` before touching either.
- **`YoutubeDL.getInfo()` cannot represent playlists** (it wraps
  `--dump-json`, which prints one JSON object per line for a playlist, not
  one document, and its `VideoInfo` model has no `entries` field).
  `YoutubeDlBridge.analyze()` instead issues `--dump-single-json
  --flat-playlist` manually via `execute()` and parses the raw JSON with the
  library's own (public) Jackson `ObjectMapper`.
- **The progress callback `(Float, Long, String)` has no speed/size
  fields** — only percent and ETA are parsed internally by the library. Speed
  and total size are regexed out of the raw stdout line (the 3rd param)
  separately, matching what the frontend's `ProgressView` expects.
- **The bundled yt-dlp binary goes stale** (YouTube's player changes
  constantly) — `YoutubeDL.getInstance().updateYoutubeDL()` runs on every
  app start to pull the latest stable build. Without this, downloads started
  failing with 403s ("n challenge" solving failures) within months of the
  library's last release.
- **Formats are restricted to mp4 (video) / m4a (audio)** in
  `YoutubeDlBridge.parseFormats()` — deliberately narrower than the web
  app's format list (which also offers webm/ogg/opus), since every
  Android player/app handles mp4/m4a natively.
- **Files are staged in `context.cacheDir` then copied into
  `MediaStore.Downloads`** (`RELATIVE_PATH = Downloads/<playlist name>` for
  playlist entries) — no storage permission needed on API 29+, and it's the
  on-device equivalent of the web app's "zip and serve" step (which doesn't
  apply here since there's no separate client to serve the file to).
- **Status bar / edge-to-edge**: `WebView.setPadding()` does *not* push the
  Chromium-rendered page content down (it's a View-bounds thing, not a page
  layout thing) — on Android 15+'s edge-to-edge-by-default windows this left
  the page header drawn under the status bar clock/icons. Fixed by feeding
  the real inset sizes into the page as CSS custom properties
  (`--android-status-bar-height` / `--android-nav-bar-height`, set via
  `evaluateJavascript` once both the insets and the page load are ready) and
  having the page's own header/FAB pad themselves — see the `applyInsetsToPage()`
  call in `MainActivity.kt` and the corresponding CSS in
  `templates/index.html`.
- **UI differs from the web app in a few deliberate, `window.AndroidBridge`-gated
  ways**: no paste button (native clipboard bridge instead), the "Analizar"
  button is icon-only, audio formats are listed (and pre-selected) before
  video, the "Atajos" (keyboard shortcuts) card is hidden, and the
  post-download action is "Abrir Descargas" instead of "Guardar archivo" /
  "Descargar ZIP" (files are already in the real Downloads folder — nothing
  to save or zip).

## Distribution

Sideload-only — downloading YouTube content violates Google Play policy, so
this was never going to be published there. Share the built APK directly;
each phone needs "install unknown apps" enabled for whatever app it's
installed from (Files, a chat app, etc.). No auto-update mechanism; updates
mean rebuilding and re-sharing the APK.

## Building

Requires Android Studio (or just a JDK + the Android SDK) and a physical
device or emulator — `minSdk` is 29 (Android 10).

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # or any JDK 17+
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`templates/index.html` is copied into `app/src/main/assets/` automatically
by a Gradle `preBuild` task (`copyWebAssets` in `app/build.gradle.kts`) — it
stays the single source of truth; the copy in `assets/` is gitignored and
regenerated on every build.

### Known device quirks encountered during development

- Some devices require a manual on-screen confirmation for the *first*
  install of a new package (or after `adb uninstall` + fresh install) —
  subsequent `adb install -r` updates don't need this.
- Some OEM Android builds (e.g. MIUI/HyperOS) block `adb shell input
  tap/swipe` (`INSTALL_FAILED_USER_RESTRICTED` / `SecurityException:
  INJECT_EVENTS`) — UI interaction during testing has to be done by hand on
  the device, not simulated over ADB.

## Known limitations / deferred (not v1)

- No cookies/login support — the entire point of going native is avoiding
  the IP-reputation problem cookies were compensating for on the server; if
  age-restricted content becomes a recurring need, `--cookies` + a
  Storage-Access-Framework file picker would be the natural addition.
- No foreground `Service` — a long download stops if you fully background
  the app, same rough behavior as closing the web app's browser tab. Fine
  for casual family use; revisit only if it's a real pain point.
- APK size is large (~200MB) because it bundles Python + ffmpeg + yt-dlp for
  all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). ABI-specific
  splits would shrink this if it ever matters for distribution.
