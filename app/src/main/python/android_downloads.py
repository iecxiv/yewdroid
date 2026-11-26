"""Download queue for the native client: format listing, one worker thread, progress pushed to Kotlin."""
import json
import re
import sys
import threading
import time
import traceback

_cond = threading.Condition()
_jobs = []
_counter = [0]
_worker = [None]
_last_push = [0.0]
_URL = re.compile(r"https?://\S+")
_BANDS_VIDEO = {"video": (0.0, 70.0), "audio": (70.0, 95.0), "merge": (95.0, 97.0), "export": (97.0, 100.0)}
_BANDS_AUDIO = {"audio": (0.0, 95.0), "export": (95.0, 100.0)}
_FINISHED = ("done", "error", "cancelled")
_PUBLIC = ("id", "ytid", "title", "kind", "label", "status", "stage", "progress", "message")


def _snapshot_locked():
    return json.dumps({"items": [{key: job[key] for key in _PUBLIC} for job in _jobs]}, ensure_ascii=False)


def state():
    with _cond:
        return _snapshot_locked()


def _push(force=True):
    now = time.monotonic()
    with _cond:
        if not force and now - _last_push[0] < 0.4:
            return
        _last_push[0] = now
        data = _snapshot_locked()
    sink = getattr(sys.modules.get("yt_term"), "_sink", None)
    if sink is not None:
        try:
            sink.downloads(data)
        except Exception:
            pass


def _update(job, force=True, **fields):
    with _cond:
        job.update(fields)
    _push(force)


def _size(fmt, duration):
    value = fmt.get("filesize") or fmt.get("filesize_approx")
    if not value and duration:
        rate = fmt.get("tbr") or fmt.get("abr")
        if rate:
            value = rate * 1000 / 8 * duration
    return int(value or 0)


def formats(ytid):
    """JSON with the audio and video options (id, label, approximate size) available for a video."""
    import yt_dlp
    url = "https://www.youtube.com/watch?v=%s" % ytid
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True, "socket_timeout": 25}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False, process=False)
    if not info:
        raise RuntimeError("No data received")
    duration = float(info.get("duration") or 0)
    items = [fmt for fmt in (info.get("formats") or []) if not fmt.get("has_drm")]

    audio_all = [fmt for fmt in items if fmt.get("vcodec") == "none"
                 and fmt.get("acodec") not in (None, "none")
                 and "drc" not in str(fmt.get("format_id"))]
    m4a = [fmt for fmt in audio_all if fmt.get("ext") == "m4a"]
    source = sorted(m4a or audio_all, key=lambda fmt: fmt.get("abr") or fmt.get("tbr") or 0)
    audio, seen = [], set()
    for fmt in source:
        rate = int(round(fmt.get("abr") or fmt.get("tbr") or 0))
        key = (fmt.get("ext"), rate)
        if key in seen:
            continue
        seen.add(key)
        label = "%s %d kbps" % (fmt.get("ext"), rate) if rate else str(fmt.get("ext"))
        audio.append({"id": str(fmt.get("format_id")), "label": label,
                      "size": _size(fmt, duration), "recommended": False})
    if audio:
        audio[-1]["recommended"] = True

    audio_size = audio[-1]["size"] if audio else 0
    by_height = {}
    for fmt in items:
        if (str(fmt.get("vcodec") or "").startswith("avc1") and fmt.get("ext") == "mp4"
                and fmt.get("height")):
            height = int(fmt["height"])
            current = by_height.get(height)
            if current is None or (fmt.get("tbr") or 0) > (current.get("tbr") or 0):
                by_height[height] = fmt
    video = []
    for height in sorted(by_height):
        fmt = by_height[height]
        fps = int(fmt.get("fps") or 0)
        label = "%dp%s" % (height, fps if fps > 30 else "")
        video.append({"id": str(height), "label": label,
                      "size": _size(fmt, duration) + audio_size, "recommended": False})
    if video:
        pick = [entry for entry in video if int(entry["id"]) <= 720]
        (pick[-1] if pick else video[0])["recommended"] = True
    return json.dumps({"title": str(info.get("title") or ""), "audio": audio, "video": video},
                      ensure_ascii=False)


def enqueue(ytid, title, kind, option, label):
    with _cond:
        _counter[0] += 1
        job = {"id": _counter[0], "ytid": str(ytid), "title": str(title),
               "kind": "video" if kind == "video" else "audio", "option": str(option or ""),
               "label": str(label or ""), "status": "queued", "stage": "", "progress": 0.0,
               "message": "", "cancel": False}
        _jobs.append(job)
        if _worker[0] is None or not _worker[0].is_alive():
            _worker[0] = threading.Thread(target=_run, daemon=True, name="yewtube-downloads")
            _worker[0].start()
        _cond.notify_all()
    _push()
    return job["id"]


def cancel(job_id):
    with _cond:
        job = next((j for j in _jobs if j["id"] == int(job_id)), None)
        if job is None or job["status"] in _FINISHED:
            return False
        job["cancel"] = True
        if job["status"] == "queued":
            job["status"] = "cancelled"
    _push()
    return True


def clear_finished():
    with _cond:
        _jobs[:] = [job for job in _jobs if job["status"] not in _FINISHED]
    _push()


def _run():
    while True:
        with _cond:
            job = None
            while job is None:
                job = next((j for j in _jobs if j["status"] == "queued"), None)
                if job is None:
                    _cond.wait()
            job["status"] = "running"
        _push()
        _execute(job)


def _is_cancel(error):
    return type(error).__name__ == "DownloadCancelled"


def _execute(job):
    bands = _BANDS_VIDEO if job["kind"] == "video" else _BANDS_AUDIO

    def stage(name):
        _update(job, stage=name, progress=bands.get(name, (0.0, 0.0))[0])

    def hook(data):
        if job["cancel"]:
            try:
                from yt_dlp.utils import DownloadCancelled
                raise DownloadCancelled()
            except ImportError:
                raise RuntimeError("Cancelled")
        if data.get("status") == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            if total:
                low, high = bands.get(job["stage"], (0.0, 100.0))
                fraction = min(1.0, data.get("downloaded_bytes", 0) / total)
                _update(job, force=False, progress=low + (high - low) * fraction)

    try:
        from mps_youtube import config
        import android_audio_download
        import android_video_download
        folder = config.DDIR.get
        if job["kind"] == "video":
            height = int(job["option"]) if job["option"].isdigit() else None
            destination = android_video_download.download_video(
                job["ytid"], folder, hook, height, stage)
        else:
            destination = android_audio_download.download_audio(
                job["ytid"], folder, android_video_download._publish,
                job["option"] or None, hook, stage)
        _update(job, status="done", stage="", progress=100.0, message=str(destination or ""))
    except Exception as error:
        if job["cancel"] or _is_cancel(error):
            _update(job, status="cancelled", stage="", message="")
        else:
            text = _URL.sub("<URL>", str(error)).strip()
            first = text.splitlines()[0] if text else type(error).__name__
            _update(job, status="error", stage="", message=first[:240])
            try:
                traceback.print_exc(file=sys.modules["yt_term"]._orig_err)
            except Exception:
                pass
