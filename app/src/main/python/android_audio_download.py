"""Audio-only downloads with same-attempt diagnostics; no video fallback."""
from collections import deque
from importlib import metadata
import json
import os
import re
import shutil
import tempfile


def _clean(message):
    return re.sub(r"https?://\S+", "<URL>", str(message))


def was_cancelled(error):
    """True when the error (or its cause) is the download queue's cancellation."""
    for _ in range(8):
        if error is None:
            return False
        if type(error).__name__ == "Cancelled":
            return True
        error = error.__cause__ or error.__context__
    return False


def download_audio(ytid, folder, publish, format_id=None, report=None):
    import yt_dlp
    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".yewdroid-audio-", dir=folder)
    warnings = deque(maxlen=20)
    report_data = {"video_id": str(ytid), "formats": []}
    for package in ("yt-dlp", "yt-dlp-ejs"):
        try:
            report_data[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            report_data[package] = "not found"

    class Logger:
        def debug(self, message):
            pass

        def warning(self, message):
            text = _clean(message)
            warnings.append(text)
            print("[yt-dlp] " + text)

        def error(self, message):
            warnings.append(_clean(message))

    def select(ctx):
        formats = ctx.get("formats") or []
        report_data["formats"] = [{key: fmt.get(key) for key in (
            "format_id", "ext", "protocol", "acodec", "vcodec", "has_drm")}
            | {"has_url": bool(fmt.get("url"))} for fmt in formats[-100:]]
        audio = [fmt for fmt in formats if fmt.get("vcodec") == "none"
                 and fmt.get("acodec") not in (None, "none")
                 and fmt.get("url") and not fmt.get("has_drm")]
        preferred = [fmt for fmt in audio if fmt.get("ext") == "m4a"]
        candidates = preferred or audio
        if not candidates:
            raise RuntimeError("No downloadable audio track in this extraction")
        chosen = None
        if format_id:
            chosen = next((fmt for fmt in audio if str(fmt.get("format_id")) == str(format_id)), None)
        if chosen is None:
            chosen = candidates[-1]
        report_data["selected_format"] = chosen.get("format_id")
        print("[audio] Format %s (%s)" % (chosen.get("format_id"), chosen.get("ext")))
        yield chosen

    def progress(data):
        if report is not None:
            report("audio", data)
        if data.get("status") == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            if total:
                print("\r  Audio %.1f%%" % (100.0 * data.get("downloaded_bytes", 0) / total), end="", flush=True)

    options = {
        "format": select,
        "outtmpl": os.path.join(work, "%(title).100B-%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "logger": Logger(),
        "progress_hooks": [progress],
        "postprocessors": [],
        "fixup": "never",
        "hls_prefer_native": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 25,
        "match_filter": lambda info, *, incomplete=False: (
            "Live streams are not supported" if info.get("is_live") else None),
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            report_data["js_runtimes"] = list((ydl.params.get("js_runtimes") or {}).keys())
            info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % ytid, download=True)
            if not info or info.get("vcodec") != "none" or info.get("acodec") in (None, "none"):
                raise RuntimeError("The result is not an audio-only track")
            requested = info.get("requested_downloads") or []
            path = (requested[0].get("filepath") if requested else None) or ydl.prepare_filename(info)
        path = os.path.abspath(path)
        if os.path.commonpath([os.path.realpath(work), os.path.realpath(path)]) != os.path.realpath(work):
            raise RuntimeError("Unexpected audio path")
        if not os.path.isfile(path) or os.path.getsize(path) == 0:
            raise RuntimeError("Audio file is missing or empty")
        print("\n[audio] Exporting finished file...")
        if report is not None:
            report("export", None)
        destination = publish(path)
        if not isinstance(destination, str) or not destination:
            raise RuntimeError("The export did not confirm the destination")
    except BaseException as error:
        if was_cancelled(error):
            shutil.rmtree(work, ignore_errors=True)
            raise
        report_data["error"] = _clean(error)
        report_data["warnings"] = list(warnings)
        diagnostic = os.path.join(work, "diagnostic.json")
        try:
            with open(diagnostic, "w", encoding="utf-8") as handle:
                json.dump(report_data, handle, ensure_ascii=False, indent=2)
        except OSError:
            diagnostic = "Could not write the diagnostic"
        message = "%s\nFiles kept: %s\nDiagnostic: %s" % (_clean(error), work, diagnostic)
        if warnings:
            message += "\nWarnings from the same attempt:\n" + "\n".join(list(warnings)[-4:])
        if isinstance(error, Exception):
            raise RuntimeError(message) from error
        print(message)
        raise
    else:
        if destination != path:
            shutil.rmtree(work, ignore_errors=True)
        print("Saved: %s" % destination)
        return destination
