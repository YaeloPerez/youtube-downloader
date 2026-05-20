import asyncio
import json
import shutil
import threading
import uuid
from pathlib import Path
from queue import Empty, Queue

import yt_dlp
from fastapi import BackgroundTasks, FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse

app = FastAPI()


@app.get("/check")
async def system_check():
    return {"ffmpeg": shutil.which("ffmpeg") is not None}
DOWNLOADS_DIR = Path("downloads")
DOWNLOADS_DIR.mkdir(exist_ok=True)

# ── helpers ────────────────────────────────────────────────────────────────────

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
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True}) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    duration = info.get("duration", 0)
    dur_str = f"{duration // 60}:{duration % 60:02d}" if duration else "--"

    return {
        "title": info.get("title", "Video"),
        "thumbnail": info.get("thumbnail", ""),
        "duration": dur_str,
        "uploader": info.get("uploader", ""),
        "formats": _parse_formats(info),
    }


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

    # Sanitize title for use as filename
    safe_title = "".join(c for c in title if c.isalnum() or c in " -_()[]").strip() or "video"

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
