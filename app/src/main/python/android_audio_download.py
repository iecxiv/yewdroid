"""Audio-only downloads with same-attempt diagnostics; no video fallback."""
from collections import deque
from importlib import metadata
import json
import os
import re
import shutil
import tempfile

from android_dl_common import Cancelled, is_bot_error, plain, say, youtube_opts


def _clean(message):
    return re.sub(r"https?://\S+", "<URL>", plain(message))


def _inside(folder, destination):
    """True when [destination] is a local file inside [folder] (Android 8/9 keeps the file locally)."""
    if not os.path.isabs(destination):
        return False
    folder = os.path.realpath(folder)
    try:
        return os.path.commonpath([folder, os.path.realpath(destination)]) == folder
    except ValueError:
        return False


def download_audio(ytid, folder, publish, format_id=None, tracker=None, clients=None):
    """Downloads the audio of a video. `format_id` picks a specific audio format; `tracker`
    (android_dl_common.Progress) receives progress and can cancel; `clients` forces YouTube
    player clients. `publish(path)` converts and/or exports the file and returns its destination;
    with a tracker it is responsible for reporting the encode and export stages."""
    import yt_dlp
    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".yewdroid-audio-", dir=folder)
    warnings = deque(maxlen=20)
    report = {"video_id": str(ytid), "formats": []}
    for package in ("yt-dlp", "yt-dlp-ejs"):
        try:
            report[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            report[package] = "not found"

    class Logger:
        def debug(self, message):
            pass

        def warning(self, message):
            text = _clean(message)
            warnings.append(text)
            say("[yt-dlp] " + text)

        def error(self, message):
            warnings.append(_clean(message))

    def select(ctx):
        formats = ctx.get("formats") or []
        report["formats"] = [{key: fmt.get(key) for key in (
            "format_id", "ext", "protocol", "acodec", "vcodec", "has_drm")}
            | {"has_url": bool(fmt.get("url"))} for fmt in formats[-100:]]
        audio = [fmt for fmt in formats if fmt.get("vcodec") == "none"
                 and fmt.get("acodec") not in (None, "none")
                 and fmt.get("url") and not fmt.get("has_drm")]
        preferred = [fmt for fmt in audio if fmt.get("ext") == "m4a"]
        candidates = preferred or audio
        if format_id:
            candidates = [fmt for fmt in audio
                          if str(fmt.get("format_id")) == str(format_id)] or candidates
        if not candidates:
            raise RuntimeError("No downloadable audio track in this extraction")
        chosen = candidates[-1]
        report["selected_format"] = chosen.get("format_id")
        say("[audio] Format %s (%s)" % (chosen.get("format_id"), chosen.get("ext")))
        yield chosen

    def on_progress(data):
        if data.get("status") == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
            if total:
                fraction = data.get("downloaded_bytes", 0) / total
                say("\r  Audio %.1f%%" % (100.0 * fraction), end="", flush=True)
                if tracker is not None:
                    tracker.stage("download", fraction)

    options = {
        "format": select,
        "outtmpl": os.path.join(work, "%(title).100B-%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "logger": Logger(),
        "progress_hooks": [on_progress],
        "postprocessors": [],
        "fixup": "never",
        "hls_prefer_native": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 25,
        "match_filter": lambda info, *, incomplete=False: (
            "Live streams are not supported" if info.get("is_live") else None),
    }
    options.update(youtube_opts(clients))
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            report["js_runtimes"] = list((ydl.params.get("js_runtimes") or {}).keys())
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
        say("\n[audio] Exporting finished file...")
        destination = publish(path)
        if not isinstance(destination, str) or not destination:
            raise RuntimeError("The export did not confirm the destination")
    except BaseException as error:
        if tracker is not None and tracker.cancelled:
            shutil.rmtree(work, ignore_errors=True)
            raise Cancelled() from None
        if is_bot_error(error):
            # Nothing was downloaded: the caller may retry with another player client.
            shutil.rmtree(work, ignore_errors=True)
            raise
        report["error"] = _clean(error)
        report["warnings"] = list(warnings)
        diagnostic = os.path.join(work, "diagnostic.json")
        try:
            with open(diagnostic, "w", encoding="utf-8") as handle:
                json.dump(report, handle, ensure_ascii=False, indent=2)
        except OSError:
            diagnostic = "Could not write the diagnostic"
        message = "%s\nFiles kept: %s\nDiagnostic: %s" % (_clean(error), work, diagnostic)
        if warnings:
            message += "\nWarnings from the same attempt:\n" + "\n".join(list(warnings)[-4:])
        if isinstance(error, Exception):
            raise RuntimeError(message) from error
        say(message)
        raise
    else:
        # Exported to public Downloads: the temporary folder is no longer needed. On Android 8/9 the
        # final file stays where it is (converted or not), so the folder must not be deleted.
        if not _inside(work, destination):
            shutil.rmtree(work, ignore_errors=True)
        say("Saved: %s" % destination)
        return destination
