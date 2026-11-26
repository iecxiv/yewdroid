"""Download queue for the native client: format list, one worker thread, progress to the sink."""
import json
import queue
import re
import sys
import threading
import time
import traceback

_THREAD = "yewdroid-download"
_ANSI = re.compile(r"\x1b\[[0-9;?%]*[A-Za-z]")
_URL = re.compile(r"https?://\S+")
_lock = threading.RLock()
_jobs = []
_pending = queue.Queue()
_cancelled = set()
_counter = [0]
_worker = [None]
_last_emit = [0.0]
_term = [None]


class Cancelled(Exception):
    pass


def install(term):
    """Keep the worker's console output out of the terminal and remember the sink."""
    _term[0] = term
    out = getattr(term, "_Out", None)
    if out is None or getattr(out, "_download_quiet", False):
        return
    original = out.write

    def write(self, s):
        if threading.current_thread().name == _THREAD:
            return len(s) if s else 0
        return original(self, s)

    out.write = write
    out._download_quiet = True


def _err():
    return getattr(_term[0], "_orig_err", sys.__stderr__)


def _find(job_id):
    for job in _jobs:
        if job["id"] == job_id:
            return job
    return None


def _snapshot():
    with _lock:
        items = [{key: job[key] for key in ("id", "title", "kind", "label", "state", "stage", "percent", "detail")}
                 for job in _jobs]
    return json.dumps({"items": items}, ensure_ascii=False)


def _emit(force=True):
    now = time.time()
    if not force and now - _last_emit[0] < 0.25:
        return
    _last_emit[0] = now
    notify = getattr(getattr(_term[0], "_sink", None), "downloads", None)
    if notify is None:
        return
    try:
        notify(_snapshot())
    except Exception:
        traceback.print_exc(file=_err())


def _set(job, force=True, **fields):
    with _lock:
        job.update(fields)
    _emit(force)


def _short(error):
    text = _ANSI.sub("", str(error)).strip().splitlines()
    text = _URL.sub("<URL>", text[0] if text else type(error).__name__)
    return text[:300]


def _size(fmt):
    return int(fmt.get("filesize") or fmt.get("filesize_approx") or 0)


def formats(ytid):
    """JSON with the audio and video options yt-dlp offers for a video, best first."""
    import yt_dlp
    options = {"quiet": True, "no_warnings": True, "noplaylist": True, "skip_download": True}
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % ytid, download=False) or {}
    usable = [f for f in info.get("formats") or [] if f.get("url") and not f.get("has_drm")]
    audio = [f for f in usable if f.get("vcodec") == "none" and f.get("acodec") not in (None, "none")]
    audio = [f for f in audio if f.get("ext") == "m4a"] or audio
    audio.sort(key=lambda f: f.get("abr") or f.get("tbr") or 0, reverse=True)
    audio_options, seen = [], set()
    for f in audio:
        rate = int(round(f.get("abr") or f.get("tbr") or 0))
        key = (f.get("ext"), rate)
        if key in seen:
            continue
        seen.add(key)
        label = str(f.get("ext") or "audio").upper() + (" %d kbps" % rate if rate else "")
        audio_options.append({"id": str(f.get("format_id")), "label": label, "size": _size(f)})
    best_audio = max((_size(f) for f in audio), default=0)
    heights = {}
    for f in usable:
        height = int(f.get("height") or 0)
        if (height and f.get("ext") == "mp4" and str(f.get("vcodec") or "").startswith("avc1")
                and f.get("acodec") in (None, "none")):
            if height not in heights or (f.get("tbr") or 0) > (heights[height].get("tbr") or 0):
                heights[height] = f
    video_options = []
    for height in sorted(heights, reverse=True):
        f = heights[height]
        fps = int(f.get("fps") or 0)
        size = _size(f)
        video_options.append({"id": str(height),
                              "label": "%dp%s" % (height, " %d fps" % fps if fps > 30 else ""),
                              "size": size + best_audio if size else 0})
    return json.dumps({"audio": audio_options, "video": video_options}, ensure_ascii=False)


def enqueue(ytid, title, kind, choice, label):
    with _lock:
        _counter[0] += 1
        job = {"id": _counter[0], "ytid": str(ytid), "title": str(title), "kind": str(kind),
               "choice": str(choice), "label": str(label), "state": "queued", "stage": "",
               "percent": -1.0, "detail": ""}
        _jobs.append(job)
        if _worker[0] is None or not _worker[0].is_alive():
            _worker[0] = threading.Thread(target=_work, daemon=True, name=_THREAD)
            _worker[0].start()
    _pending.put(job["id"])
    _emit()
    return job["id"]


def cancel(job_id):
    job_id = int(job_id)
    with _lock:
        job = _find(job_id)
        if job is None or job["state"] not in ("queued", "running"):
            return False
        _cancelled.add(job_id)
        if job["state"] == "queued":
            job["state"] = "cancelled"
    _emit()
    return True


def clear_finished():
    with _lock:
        _jobs[:] = [job for job in _jobs if job["state"] in ("queued", "running")]
    _emit()


def _run(job):
    from mps_youtube import config
    import android_audio_download as audio
    import android_video_download as video

    folder = str(config.DDIR.get)

    def report(stage, data):
        if job["id"] in _cancelled:
            raise Cancelled("cancelled")
        percent = -1.0
        if data and data.get("status") == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            if total:
                percent = 100.0 * data.get("downloaded_bytes", 0) / total
        elif data and data.get("status") == "finished":
            percent = 100.0
        changed = stage != job["stage"] or percent in (-1.0, 100.0)
        _set(job, force=changed, stage=stage, percent=percent)

    if job["kind"] == "video":
        height = int(job["choice"]) if job["choice"].isdigit() else None
        return video.download_video(job["ytid"], folder, lambda data: None, height=height, report=report)
    return audio.download_audio(job["ytid"], folder, video._publish,
                                format_id=job["choice"] or None, report=report)


def _work():
    while True:
        job = _find(_pending.get())
        if job is None or job["state"] != "queued":
            continue
        _set(job, state="running", stage="start", percent=-1.0)
        try:
            destination = _run(job)
            _set(job, state="done", stage="", percent=100.0, detail=str(destination or ""))
        except BaseException as error:
            if job["id"] in _cancelled or isinstance(error, Cancelled):
                _set(job, state="cancelled", stage="", percent=-1.0, detail="")
            else:
                traceback.print_exc(file=_err())
                _set(job, state="error", stage="", percent=-1.0, detail=_short(error))
