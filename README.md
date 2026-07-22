# Vault — YT Downloader

Download YouTube videos, audio, and full playlists (organized into folders)
from a clean web UI — plus a native Android app for when a server-side IP
gets flagged by YouTube's bot detection.

## Components

| Path | What it is |
|---|---|
| `app.py`, `templates/index.html`, `requirements.txt` | FastAPI backend + single-file React (Babel-in-browser) frontend. Runs locally (`start.sh` / `start.bat`) or deployed to a server. |
| `deploy/` | Production deployment: pm2 + nginx config, setup script, and a step-by-step guide for adding this app to an existing Lightsail instance (see `deploy/README.md`). |
| `android/` | Native Android app ("Vault") — same UI (`templates/index.html`, reused as-is in a WebView), bundled yt-dlp/ffmpeg via `youtubedl-android`, no server required. See `android/README.md` for why this exists and how it's built. |

## Local development (Mac)

```bash
./start.sh
```

Installs Python deps + ffmpeg (via Homebrew) and starts the server at
`http://localhost:8000`. On Windows, use `start.bat`.

## Features

- Paste a link → pick a format (video up to 4K, audio-only, multiple
  qualities) → download.
- Playlists: select which videos to grab and at what quality, downloaded as
  a `.zip` (web) organized by playlist name, or straight into
  `Downloads/<playlist name>/` (Android).
- No ads, no tracking, no account required.

## Known limitation

Videos that require a logged-in YouTube session (`LOGIN_REQUIRED`, usually
age-restricted content) can't be downloaded — this is a YouTube
content-gating rule, not something client-side. Everything else works.

## Production deployment

Currently running at a Lightsail instance (`vault.devtech.mx`), sharing the
box with other Node/pm2 apps. Full deployment steps, including how the app
avoids colliding with other services on the same server, are documented in
[`deploy/README.md`](deploy/README.md).

## Android app

See [`android/README.md`](android/README.md) for the architecture, why it's
built the way it is (no Chaquopy, no server dependency), and build/install
instructions. Sideload-only (Google Play doesn't allow YouTube downloaders).
