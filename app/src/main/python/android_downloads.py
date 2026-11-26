"""Download queue for the native client: lists formats, runs one worker thread, reports progress."""
import json
import queue
import re
import sys
import threading
import time
import traceback

from android_dl_common import QUIET, Cancelled, Progress

_lock = threading.RLock()
_jobs = []
_trackers = {}
_pending = queue.Queue()
_state = {"worker": None, "next": 0, "pushed": 0.0}
_FINISHED = ("done", "error", "cancelled")
_WEIGHTS = {
    "audio": {"download": (0.0, 0.95), "export": (0.95, 1.0)},
    "video": {"video": (0.0, 0.75), "audio": (0.75, 0.95), "merge": (0.95, 0.98),
              "export": (0.98, 1.0)},
}


class _Silent:
    def debug(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


def _clean(message):
    return re.sub(r"https?://\S+", "<URL>", str(message))


def _log():
    err = getattr(sys.modules.get("yt_term"), "_orig_err", None) or sys.__stderr__
    try:
        traceback.print_exc(file=err)
    except Exception:
        pass


def _size(fmt):
    value = fmt.get("filesize") or fmt.get("filesize_approx")
    try:
        return int(value) if value else 0
    except (TypeError, ValueError):
        return 0


def formats(ytid):
    """JSON with the audio and video options yt-dlp offers for a video."""
    import yt_dlp
    result = {"audio": [], "video": [], "error": ""}
    try:
        options = {"quiet": True, "no_warnings": True, "noplaylist": True, "logger": _Silent()}
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % ytid, download=False)
        usable = [f for f in ((info or {}).get("formats") or []) if f.get("url") and not f.get("has_drm")]
        audio = [f for f in usable if f.get("vcodec") == "none" and f.get("acodec") not in (None, "none")]
        m4a = [f for f in audio if f.get("ext") == "m4a"]
        pool = sorted(m4a or audio, key=lambda f: f.get("abr") or f.get("tbr") or 0, reverse=True)
        seen = set()
        for fmt in pool:
            rate = int(round(fmt.get("abr") or fmt.get("tbr") or 0))
            if rate in seen:
                continue
            seen.add(rate)
            name = str(fmt.get("ext") or "audio").upper()
            result["audio"].append({
                "id": str(fmt["format_id"]),
                "label": ("%s \u00b7 %d kbps" % (name, rate)) if rate else name,
                "size": _size(fmt), "recommended": False})
        if result["audio"]:
            result["audio"][0]["recommended"] = True
        audio_size = result["audio"][0]["size"] if result["audio"] else 0
        best = {}
        for fmt in usable:
            if (fmt.get("ext") == "mp4" and str(fmt.get("vcodec") or "").startswith("avc1")
                    and fmt.get("acodec") in (None, "none") and fmt.get("height")):
                height = int(fmt["height"])
                rank = (0 if "m3u8" in str(fmt.get("protocol")) else 1, fmt.get("tbr") or 0)
                if height not in best or rank > best[height][0]:
                    best[height] = (rank, fmt)
        entries = []
        for height in sorted(best, reverse=True):
            fmt = best[height][1]
            fps = int(fmt.get("fps") or 0)
            size = _size(fmt)
            entries.append((height, {
                "id": str(fmt["format_id"]),
                "label": "%dp" % height + (" \u00b7 %d fps" % fps if fps > 30 else ""),
                "size": size + audio_size if size else 0, "recommended": False}))
        if entries:
            pick = next((o for h, o in entries if h <= 720), entries[-1][1])
            pick["recommended"] = True
        result["video"] = [option for _, option in entries]
    except Exception as error:
        _log()
        result["error"] = _clean(error)[:300]
    return json.dumps(result, ensure_ascii=False)


def snapshot():
    with _lock:
        items = [{key: job[key] for key in ("id", "title", "kind", "label", "state", "percent",
                                            "stage", "path", "error")} for job in _jobs]
    return json.dumps({"items": items}, ensure_ascii=False)


def _push(force=False):
    now = time.monotonic()
    if not force and now - _state["pushed"] < 0.25:
        return
    _state["pushed"] = now
    try:
        from java import jclass
        jclass("iecxiv.yewdroid.DownloadHub").update(snapshot())
    except Exception:
        _log()


def _find(job_id):
    with _lock:
        return next((job for job in _jobs if job["id"] == job_id), None)


def _run(job):
    weights = _WEIGHTS[job["kind"]]

    def on_stage(name, fraction):
        start, end = weights.get(name, (0.0, 1.0))
        with _lock:
            job["stage"] = name
            job["percent"] = round(100.0 * (start + (end - start) * fraction), 1)
        _push()

    tracker = Progress(on_stage)
    _trackers[job["id"]] = tracker
    QUIET.on = True
    try:
        from mps_youtube import config
        folder = config.DDIR.get
        if job["kind"] == "audio":
            from android_audio_download import download_audio
            from android_video_download import _publish
            destination = download_audio(job["ytid"], folder, _publish, job["format_id"], tracker)
        else:
            from android_video_download import download_video
            destination = download_video(job["ytid"], folder, lambda data: None,
                                         job["format_id"], tracker)
        with _lock:
            job.update(state="done", percent=100.0, stage="", path=str(destination or ""))
    except BaseException as error:
        if tracker.cancelled or isinstance(error, Cancelled):
            with _lock:
                job.update(state="cancelled", stage="")
        else:
            _log()
            lines = [line.strip() for line in _clean(error).splitlines() if line.strip()]
            with _lock:
                job.update(state="error", stage="", error=(lines[0] if lines else "Error")[:200])
    finally:
        QUIET.on = False
        _trackers.pop(job["id"], None)


def _loop():
    while True:
        job = _find(_pending.get())
        try:
            if job is None or job["state"] != "queued":
                continue
            with _lock:
                job["state"] = "running"
            _push(True)
            _run(job)
        except Exception:
            _log()
        _push(True)


def enqueue(ytid, title, kind, format_id, label):
    kind = "audio" if kind == "audio" else "video"
    with _lock:
        _state["next"] += 1
        job = {"id": _state["next"], "ytid": str(ytid), "title": str(title), "kind": kind,
               "format_id": str(format_id), "label": str(label), "state": "queued",
               "percent": 0.0, "stage": "", "path": "", "error": ""}
        _jobs.append(job)
        worker = _state["worker"]
        if worker is None or not worker.is_alive():
            worker = threading.Thread(target=_loop, daemon=True, name="yewdroid-downloads")
            _state["worker"] = worker
            worker.start()
    _pending.put(job["id"])
    _push(True)
    return job["id"]


def cancel(job_id):
    job = _find(int(job_id))
    if job is None:
        return False
    with _lock:
        if job["state"] == "queued":
            job["state"] = "cancelled"
        elif job["state"] == "running":
            tracker = _trackers.get(job["id"])
            if tracker is not None:
                tracker.cancelled = True
            job["stage"] = "cancelling"
    _push(True)
    return True


def clear_finished():
    with _lock:
        _jobs[:] = [job for job in _jobs if job["state"] not in _FINISHED]
    _push(True)
