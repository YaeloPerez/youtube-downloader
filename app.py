import asyncio
import json
import shutil
import threading
import time
import uuid
from pathlib import Path
from queue import Empty, Queue

import yt_dlp
from fastapi import BackgroundTasks, FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel

app = FastAPI()


@app.get("/check")
async def system_check():
    return {"ffmpeg": shutil.which("ffmpeg") is not None}
DOWNLOADS_DIR = Path("downloads")
DOWNLOADS_DIR.mkdir(exist_ok=True)

# Files normally get cleaned up right after being served, but a closed tab or
# a failed download can leave orphans behind. On a long-running server (as
# opposed to a Mac only running this on demand) that quietly fills the disk,
# so sweep anything stale on an interval.
ORPHAN_MAX_AGE_SECONDS = 6 * 3600
ORPHAN_SWEEP_INTERVAL_SECONDS = 1800


async def _sweep_orphaned_downloads():
    while True:
        await asyncio.sleep(ORPHAN_SWEEP_INTERVAL_SECONDS)
        cutoff = time.time() - ORPHAN_MAX_AGE_SECONDS
        for p in DOWNLOADS_DIR.iterdir():
            try:
                if p.stat().st_mtime < cutoff:
                    shutil.rmtree(p, ignore_errors=True) if p.is_dir() else p.unlink(missing_ok=True)
            except FileNotFoundError:
                pass


@app.on_event("startup")
async def _start_background_tasks():
    asyncio.create_task(_sweep_orphaned_downloads())

# Playlist download jobs: job_id -> Queue of progress events
PLAYLIST_JOBS: dict[str, Queue] = {}

# Preset formats used for playlist downloads (per-video format probing would be
# too slow across many videos, so playlists use one shared quality choice)
PLAYLIST_QUALITY_PRESETS = {
    "best": {
        "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
        "label": "Mejor calidad (mp4)",
        "type": "video",
        "needs_ffmpeg": True,
    },
    "1080p": {
        "format": "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]",
        "label": "Hasta 1080p (mp4)",
        "type": "video",
        "needs_ffmpeg": True,
    },
    "720p": {
        "format": "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]",
        "label": "Hasta 720p (mp4)",
        "type": "video",
        "needs_ffmpeg": True,
    },
    "audio": {
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "label": "Solo audio (m4a)",
        "type": "audio",
        "needs_ffmpeg": False,
    },
}


class PlaylistEntry(BaseModel):
    index: int
    url: str
    title: str = "video"


class PlaylistDownloadRequest(BaseModel):
    quality: str
    entries: list[PlaylistEntry]
    playlist_title: str = "playlist"


# ── helpers ────────────────────────────────────────────────────────────────────

def _safe_name(name: str, fallback: str = "video") -> str:
    cleaned = "".join(c for c in name if c.isalnum() or c in " -_()[]").strip()
    return cleaned or fallback


def _fmt_size(b):
    if not b:
        return ""
    if b >= 1_000_000_000:
        return f" ~{b/1e9:.1f}GB"
    if b >= 1_000_000:
        return f" ~{b/1e6:.0f}MB"
    return f" ~{b/1e3:.0f}KB"


def _parse_formats(info: dict) -> list[dict]:
    result = [
        {
            "format_id": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
            "label": "Mejor calidad (mp4)",
            "type": "video",
            "ext": "mp4",
            "needs_ffmpeg": True,
        },
        {
            "format_id": "bestaudio[ext=m4a]/bestaudio/best",
            "label": "Solo audio (m4a)",
            "type": "audio",
            "ext": "m4a",
            "needs_ffmpeg": False,
        },
    ]

    seen_v: set = set()
    seen_a: set = set()

    for f in sorted(info.get("formats", []), key=lambda x: x.get("height") or 0, reverse=True):
        vcodec = f.get("vcodec", "none")
        acodec = f.get("acodec", "none")
        height = f.get("height")
        ext = f.get("ext", "")
        fid = f.get("format_id", "")
        size = _fmt_size(f.get("filesize") or f.get("filesize_approx"))

        if vcodec != "none" and height and ext in ("mp4", "webm"):
            key = (height, ext)
            if key not in seen_v:
                seen_v.add(key)
                # Video-only streams need ffmpeg to merge with audio
                has_audio = acodec not in (None, "none", "")
                if has_audio:
                    combined_fid = fid
                    needs_ffmpeg = False
                else:
                    combined_fid = f"{fid}+bestaudio[ext=m4a]/bestaudio"
                    needs_ffmpeg = True
                result.append({
                    "format_id": combined_fid,
                    "label": f"{height}p ({ext}){size}",
                    "type": "video",
                    "ext": "mp4" if needs_ffmpeg else ext,
                    "needs_ffmpeg": needs_ffmpeg,
                })
        elif vcodec == "none" and acodec != "none" and ext in ("m4a", "webm", "ogg", "opus"):
            abr = int(f.get("abr") or 0)
            key = (ext, abr)
            if key not in seen_a:
                seen_a.add(key)
                label_abr = f" {abr}kbps" if abr else ""
                result.append({
                    "format_id": fid,
                    "label": f"Audio{label_abr} ({ext}){size}",
                    "type": "audio",
                    "ext": ext,
                    "needs_ffmpeg": False,
                })

    return result


# ── routes ─────────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def index():
    return Path("templates/index.html").read_text(encoding="utf-8")


@app.get("/info")
async def get_video_info(url: str = Query(...)):
    try:
        # extract_flat only skips full extraction for *playlist entries*; a
        # direct video URL still returns full info (formats included).
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "extract_flat": "in_playlist"}) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    if info.get("_type") == "playlist":
        entries = []
        for idx, e in enumerate(info.get("entries") or [], start=1):
            if not e:
                continue
            duration = e.get("duration") or 0
            dur_str = f"{int(duration) // 60}:{int(duration) % 60:02d}" if duration else "--"
            thumbs = e.get("thumbnails") or []
            vid = e.get("id")
            entries.append({
                "index": idx,
                "id": vid,
                "url": e.get("url") or (f"https://www.youtube.com/watch?v={vid}" if vid else None),
                "title": e.get("title") or "Video",
                "duration": dur_str,
                "thumbnail": thumbs[-1]["url"] if thumbs else e.get("thumbnail"),
            })
        return {
            "type": "playlist",
            "title": info.get("title", "Playlist"),
            "uploader": info.get("uploader", ""),
            "video_count": len(entries),
            "entries": entries,
        }

    duration = info.get("duration", 0)
    dur_str = f"{duration // 60}:{duration % 60:02d}" if duration else "--"

    return {
        "type": "video",
        "title": info.get("title", "Video"),
        "thumbnail": info.get("thumbnail", ""),
        "duration": dur_str,
        "uploader": info.get("uploader", ""),
        "formats": _parse_formats(info),
    }


@app.post("/playlist/download")
async def start_playlist_download(req: PlaylistDownloadRequest):
    preset = PLAYLIST_QUALITY_PRESETS.get(req.quality)
    if not preset:
        raise HTTPException(status_code=400, detail="Calidad no válida")
    if not req.entries:
        raise HTTPException(status_code=400, detail="Selecciona al menos un video")
    if preset["needs_ffmpeg"] and shutil.which("ffmpeg") is None:
        raise HTTPException(status_code=400, detail="Esta calidad requiere ffmpeg, que no está instalado.")

    job_id = str(uuid.uuid4())
    queue: Queue = Queue()
    PLAYLIST_JOBS[job_id] = queue

    playlist_name = _safe_name(req.playlist_title, "playlist")
    folder = DOWNLOADS_DIR / f"{playlist_name}_{job_id[:8]}"
    folder.mkdir(parents=True, exist_ok=True)

    entries = req.entries
    total = len(entries)

    def run_playlist_download():
        completed = 0
        for entry in entries:
            safe_title = _safe_name(entry.title)
            output_template = str(folder / f"{entry.index:03d} - {safe_title}.%(ext)s")

            def progress_hook(d, idx=entry.index, title=entry.title):
                queue.put({**d, "_video_index": idx, "_video_title": title})

            opts: dict = {
                "format": preset["format"],
                "outtmpl": output_template,
                "progress_hooks": [progress_hook],
                "quiet": True,
                "no_warnings": True,
            }
            if preset["needs_ffmpeg"]:
                opts["merge_output_format"] = "mp4"

            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    ydl.download([entry.url])
                completed += 1
                queue.put({
                    "status": "_video_done",
                    "_video_index": entry.index,
                    "_video_title": entry.title,
                    "completed": completed,
                    "total": total,
                })
            except Exception as e:
                queue.put({
                    "status": "_video_error",
                    "_video_index": entry.index,
                    "_video_title": entry.title,
                    "message": str(e),
                })

        zip_id = None
        try:
            if any(folder.iterdir()):
                shutil.make_archive(str(DOWNLOADS_DIR / job_id), "zip", root_dir=folder)
                zip_id = job_id
        except Exception:
            pass
        finally:
            shutil.rmtree(folder, ignore_errors=True)

        queue.put({"status": "_all_done", "playlist_name": playlist_name, "zip_id": zip_id})

    threading.Thread(target=run_playlist_download, daemon=True).start()
    return {"job_id": job_id, "folder": folder.name, "total": total}


@app.get("/playlist/progress/{job_id}")
async def playlist_progress(job_id: str):
    queue = PLAYLIST_JOBS.get(job_id)
    if queue is None:
        raise HTTPException(status_code=404, detail="Job no encontrado")

    async def event_stream():
        while True:
            try:
                d = queue.get(timeout=0.3)
            except Empty:
                yield f"data: {json.dumps({'status': 'waiting'})}\n\n"
                await asyncio.sleep(0.05)
                continue

            status = d.get("status", "")

            if status == "downloading":
                pct = d.get("_percent_str", "?").strip()
                speed = d.get("_speed_str", "").strip()
                eta = d.get("_eta_str", "").strip()
                total_b = _fmt_size(d.get("total_bytes") or d.get("total_bytes_estimate"))
                payload = {
                    "status": "downloading",
                    "video_index": d.get("_video_index"),
                    "video_title": d.get("_video_title"),
                    "percent": pct, "speed": speed, "eta": eta, "total": total_b,
                }
                yield f"data: {json.dumps(payload)}\n\n"

            elif status == "_video_done":
                payload = {
                    "status": "video_complete",
                    "video_index": d.get("_video_index"),
                    "video_title": d.get("_video_title"),
                    "completed": d.get("completed"),
                    "total": d.get("total"),
                }
                yield f"data: {json.dumps(payload)}\n\n"

            elif status == "_video_error":
                payload = {
                    "status": "video_error",
                    "video_index": d.get("_video_index"),
                    "video_title": d.get("_video_title"),
                    "message": d.get("message"),
                }
                yield f"data: {json.dumps(payload)}\n\n"

            elif status == "_all_done":
                payload = {
                    "status": "all_complete",
                    "playlist_name": d.get("playlist_name"),
                    "zip_id": d.get("zip_id"),
                }
                yield f"data: {json.dumps(payload)}\n\n"
                PLAYLIST_JOBS.pop(job_id, None)
                return

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@app.get("/download")
async def download_video(
    url: str = Query(...),
    format_id: str = Query(...),
    ext: str = Query("mp4"),
    title: str = Query("video"),
):
    download_id = str(uuid.uuid4())
    output_template = str(DOWNLOADS_DIR / f"{download_id}.%(ext)s")
    queue: Queue = Queue()

    def progress_hook(d):
        queue.put(dict(d))

    def run_download():
        opts: dict = {
            "format": format_id,
            "outtmpl": output_template,
            "progress_hooks": [progress_hook],
            "quiet": True,
            "no_warnings": True,
        }
        # Merge to mp4 when combining streams
        if "+" in format_id or "bestvideo" in format_id:
            opts["merge_output_format"] = "mp4"

        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.download([url])
        except Exception as e:
            queue.put({"status": "error", "message": str(e)})
        finally:
            queue.put({"status": "_done"})

    threading.Thread(target=run_download, daemon=True).start()

    safe_title = _safe_name(title)

    async def event_stream():
        while True:
            try:
                d = queue.get(timeout=0.3)
            except Empty:
                yield f"data: {json.dumps({'status': 'waiting'})}\n\n"
                await asyncio.sleep(0.05)
                continue

            status = d.get("status", "")

            if status == "downloading":
                pct = d.get("_percent_str", "?").strip()
                speed = d.get("_speed_str", "").strip()
                eta = d.get("_eta_str", "").strip()
                total = _fmt_size(d.get("total_bytes") or d.get("total_bytes_estimate"))
                yield f"data: {json.dumps({'status': 'downloading', 'percent': pct, 'speed': speed, 'eta': eta, 'total': total})}\n\n"

            elif status == "error":
                yield f"data: {json.dumps({'status': 'error', 'message': d.get('message', 'Error desconocido')})}\n\n"
                return

            elif status == "_done":
                files = list(DOWNLOADS_DIR.glob(f"{download_id}.*"))
                if files:
                    real_ext = files[0].suffix
                    filename = f"{safe_title}{real_ext}"
                    yield f"data: {json.dumps({'status': 'complete', 'download_id': download_id, 'filename': filename})}\n\n"
                else:
                    yield f"data: {json.dumps({'status': 'error', 'message': 'Archivo no encontrado tras la descarga'})}\n\n"
                return

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@app.get("/file/{download_id}")
async def serve_file(download_id: str, background_tasks: BackgroundTasks, filename: str = Query("video")):
    files = list(DOWNLOADS_DIR.glob(f"{download_id}.*"))
    if not files:
        raise HTTPException(status_code=404, detail="Archivo no encontrado")
    file_path = files[0]
    background_tasks.add_task(file_path.unlink, missing_ok=True)
    return FileResponse(
        path=file_path,
        filename=filename,
        media_type="application/octet-stream",
    )
