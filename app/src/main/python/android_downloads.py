"""Download queue for the native client: lists formats, runs one download at a time, reports progress."""
import itertools
import json
import os
import queue
import re
import sys
import threading
import time
import traceback

_jobs = []
_lock = threading.Lock()
_pending = queue.Queue()
_worker = [None]
_ids = itertools.count(1)
_last = [0.0]
_FINISHED = ("done", "error", "cancelled")
_FIELDS = ("id", "title", "kind", "label", "status", "stage", "percent", "message")


class Cancelled(Exception):
    pass


def _folder():
    try:
        from mps_youtube import config
        return str(config.DDIR.get)
    except Exception:
        return os.path.join(os.environ.get("HOME", "."), "Downloads")


def _clean(error):
    text = re.sub(r"https?://\S+", "<URL>", str(error))
    lines = [line for line in text.splitlines() if line.strip()]
    return "\n".join(lines[:2])[:300]


def _size(fmt):
    return int(fmt.get("filesize") or fmt.get("filesize_approx") or 0)


def snapshot():
    with _lock:
        items = [{key: job[key] for key in _FIELDS} for job in _jobs]
    return json.dumps({"items": items})


def _notify(force=False):
    now = time.time()
    if not force and now - _last[0] < 0.25:
        return
    _last[0] = now
    try:
        from java import jclass
        jclass("iecxiv.yewdroid.DownloadHub").update(snapshot())
    except Exception:
        pass


def refresh():
    _notify(True)


def formats(ytid):
    """Audio and video options for one video, as JSON. Never raises."""
    result = {"audio": [], "video": [], "error": ""}
    try:
        import yt_dlp
        opts = {"quiet": True, "no_warnings": True, "noplaylist": True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % ytid,
                                    download=False, process=False)
        found = list((info or {}).get("formats") or [])
        pool = [f for f in found if f.get("url")] or found
        pool = [f for f in pool if not f.get("has_drm")]
        audio = [f for f in pool if f.get("vcodec") == "none"
                 and f.get("acodec") not in (None, "none")]
        preferred = [f for f in audio if f.get("ext") == "m4a"] or audio
        preferred.sort(key=lambda f: f.get("abr") or f.get("tbr") or 0, reverse=True)
        seen = set()
        for fmt in preferred:
            rate = int(fmt.get("abr") or fmt.get("tbr") or 0)
            label = "%s \u00b7 %d kbps" % (str(fmt.get("ext") or "audio").upper(), rate) if rate \
                else str(fmt.get("ext") or "audio").upper()
            if label in seen:
                continue
            seen.add(label)
            result["audio"].append({"key": str(fmt.get("format_id")), "label": label, "size": _size(fmt)})
        audio_size = _size(preferred[0]) if preferred else 0
        best = {}
        for fmt in pool:
            height = fmt.get("height")
            if (fmt.get("ext") == "mp4" and str(fmt.get("vcodec") or "").startswith("avc1") and height):
                score = fmt.get("tbr") or _size(fmt)
                if height not in best or score > (best[height].get("tbr") or _size(best[height])):
                    best[height] = fmt
        for height in sorted(best, reverse=True):
            video_size = _size(best[height])
            result["video"].append({
                "key": str(int(height)), "label": "%dp" % height,
                "size": video_size + audio_size if video_size else 0})
    except Exception as error:
        result["error"] = _clean(error)
    return json.dumps(result)


def enqueue(ytid, title, kind, choice, label):
    job = {"id": next(_ids), "ytid": str(ytid), "title": str(title), "kind": str(kind),
           "choice": str(choice or ""), "label": str(label or ""), "status": "queued",
           "stage": "", "percent": 0, "message": "", "cancel": False}
    with _lock:
        _jobs.append(job)
    _pending.put(job)
    if _worker[0] is None or not _worker[0].is_alive():
        _worker[0] = threading.Thread(target=_loop, daemon=True, name="yewtube-downloads")
        _worker[0].start()
    _notify(True)
    return job["id"]


def cancel(job_id):
    with _lock:
        job = next((j for j in _jobs if j["id"] == int(job_id)), None)
    if job is None or job["status"] in _FINISHED:
        return
    job["cancel"] = True
    if job["status"] == "queued":
        job["status"] = "cancelled"
    _notify(True)


def clear_finished():
    with _lock:
        _jobs[:] = [j for j in _jobs if j["status"] not in _FINISHED]
    _notify(True)


def _loop():
    while True:
        job = _pending.get()
        if job["status"] != "queued":
            continue
        try:
            _run(job)
        except Exception:
            traceback.print_exc(file=sys.__stderr__ or sys.stderr)
            job["status"], job["message"] = "error", "Unexpected error"
            _notify(True)


def _run(job):
    import android_audio_download as audio
    import android_video_download as video

    def check():
        if job["cancel"]:
            raise Cancelled()

    def on_progress(data):
        check()
        if data.get("status") == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            job["percent"] = int(100.0 * data.get("downloaded_bytes", 0) / total) if total else -1
            _notify()

    def on_stage(name):
        if name in ("audio", "video"):
            check()
        job["stage"] = name
        job["percent"] = -1 if name in ("merging", "exporting") else 0
        _notify(True)

    job["status"] = "running"
    _notify(True)
    try:
        if job["kind"] == "audio":
            destination = audio.download_audio(
                job["ytid"], _folder(), video._publish, format_id=job["choice"] or None,
                on_progress=on_progress, on_stage=on_stage, cancelled=lambda: job["cancel"])
        else:
            destination = video.download_video(
                job["ytid"], _folder(), on_progress,
                height=int(job["choice"]) if job["choice"] else None,
                on_stage=on_stage, cancelled=lambda: job["cancel"])
        job["status"], job["message"], job["percent"] = "done", str(destination), 100
    except Exception as error:
        if job["cancel"]:
            job["status"], job["message"] = "cancelled", ""
        else:
            job["status"], job["message"] = "error", _clean(error)
            traceback.print_exc(file=sys.__stderr__ or sys.stderr)
    _notify(True)
