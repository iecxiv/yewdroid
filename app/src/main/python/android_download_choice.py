"""Explicit choice, best selectors, and original audio encoding profiles."""
import os
import shutil
import subprocess
import tempfile
from types import SimpleNamespace


class DownloadCancelled(Exception):
    pass


def _colors():
    try:
        from mps_youtube import c
        return c
    except Exception:
        return SimpleNamespace(r="", g="", y="", w="")


def _duration(seconds):
    try:
        seconds = int(seconds)
    except (TypeError, ValueError):
        return ""
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    return "%d:%02d:%02d" % (hours, minutes, secs) if hours else "%d:%02d" % (minutes, secs)


def kind(fmt):
    video = fmt.get("vcodec") not in (None, "none")
    audio = fmt.get("acodec") not in (None, "none")
    return "video+audio" if video and audio else "video" if video else "audio" if audio else "other"


def can_mux(video, audio):
    return (video.get("ext") == "mp4" and str(video.get("vcodec", "")).startswith("avc1")
            and audio.get("ext") == "m4a" and str(audio.get("acodec", "")).startswith("mp4a"))


def ffmpeg_ready():
    from android_ffmpeg import binaries
    try:
        paths = binaries()
    except RuntimeError:
        return False
    for path in paths:
        result = subprocess.run([path, "-version"], capture_output=True, text=True, timeout=15)
        if result.returncode != 0:
            raise RuntimeError("The bundled FFmpeg binary does not start: " + os.path.basename(path))
    return True


def choose(rows, info, ffmpeg=False, prompt="Download number: ", extra=None):
    from mps_youtube import g, screen
    c = _colors()
    title = str(info.get("title") or info.get("id"))
    uploader = info.get("uploader") or info.get("channel")
    lines = ["  %s%s%s" % (c.r, title, c.w)]
    if uploader:
        lines.append(c.r + "  Uploaded by " + str(uploader) + c.w)
    length = _duration(info.get("duration"))
    if length:
        lines.append("  [" + length + "]")
    lines.append("")
    lines.append("  %s%-6s %-8s %-13s %-11s %-8s %s%s" % (
        c.w, "Item", "Format", "Quality", "Media", "Size", "Notes", c.w))
    lines.append("")
    for number, fmt in enumerate(rows, 1):
        size = fmt.get("filesize") or fmt.get("filesize_approx")
        size = "%5s Mb" % int(size / 1048576) if size else "%5s   " % "?"
        quality = fmt.get("resolution") or ("%sp" % fmt["height"] if fmt.get("height") else "%s kbps" % fmt.get("abr", "?"))
        notes = "id %s; %s / %s" % (fmt.get("format_id"), fmt.get("vcodec"), fmt.get("acodec"))
        lines.append("  %s%-6s %-8s %-13s %-11s %s  %s%s" % (
            c.g, number, fmt.get("ext"), quality, kind(fmt), size, notes, c.w))
    lines.append("")
    if extra:
        lines.append(extra)
    lines.append("FFmpeg: merges into MKV without re-encoding; depends on the build's support." if ffmpeg
                 else "Without FFmpeg: merges only MP4/H.264 + M4A/AAC.")
    lines.append("Select [%s1-%d%s] to download or [%sEnter%s] to return" % (c.y, len(rows), c.w, c.y, c.w))
    g.content = "\n".join(lines)
    screen.update()
    while True:
        answer = input(prompt).strip()
        if not answer:
            raise DownloadCancelled()
        if answer.isdigit() and 1 <= int(answer) <= len(rows):
            return rows[int(answer) - 1]
        print(c.y + "Selection out of range." + c.w)


def plan(ydl, info, mode, ffmpeg=False):
    rows = [fmt for fmt in info.get("formats", []) if fmt.get("url")
            and not fmt.get("has_drm") and kind(fmt) != "other"]
    if not rows:
        raise RuntimeError("No downloadable formats in this extraction")
    if mode in ("d", "dl", "download"):
        selected = choose(rows, info, ffmpeg)
        if kind(selected) != "video":
            return [selected]
        audio = [fmt for fmt in rows if kind(fmt) == "audio" and (ffmpeg or can_mux(selected, fmt))]
        if not audio:
            raise RuntimeError("No audio compatible with the available backend; nothing was downloaded")
        return [selected, choose(audio, info, ffmpeg, "Audio stream: ", "Select the audio to merge with the video")]
    selector = "ba" if mode.startswith("da") else "bv*+ba/b"
    selections = list(ydl.build_format_selector(selector)({"formats": rows, "incomplete_formats": False, "has_merged_format": any(kind(fmt) == "video+audio" for fmt in rows)}))
    if not selections:
        raise RuntimeError("No format available for " + selector)
    selected = selections[0]
    tracks = selected.get("requested_formats") or [selected]
    if mode.startswith("da") and any(kind(fmt) != "audio" for fmt in tracks):
        raise RuntimeError("The best format is not audio-only")
    if len(tracks) > 1:
        if len(tracks) != 2 or kind(tracks[0]) != "video" or kind(tracks[1]) != "audio":
            raise RuntimeError("Unsupported track combination")
        if not ffmpeg and not can_mux(tracks[0], tracks[1]):
            raise RuntimeError("The best video/audio requires FFmpeg. Without FFmpeg only MP4/H.264 + M4A/AAC can be merged; nothing was downloaded and no lower quality was chosen")
    return tracks


def download(ytid, folder, mode):
    import yt_dlp
    from android_video_download import _publish
    from android_encode import selected_profile, accepts, validate, apply_profile
    from android_dl_encode import keep_original
    c = _colors()
    folder = os.path.abspath(folder)
    options = {"quiet": True, "noplaylist": True, "postprocessors": [], "fixup": "never",
               "hls_prefer_native": True, "retries": 3, "fragment_retries": 3, "socket_timeout": 25}
    url = "https://www.youtube.com/watch?v=%s" % ytid
    ffmpeg = False if mode.startswith("da") else ffmpeg_ready()
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
        if not info or info.get("is_live"):
            raise RuntimeError("Live streams and empty extractions are not supported")
        tracks = plan(ydl, info, mode, ffmpeg)
    profile = None
    # The Settings switch decides whether the original is exported too (DELETE_ORIGINAL is not used).
    keep = False
    if len(tracks) == 1 and kind(tracks[0]) == "audio":
        selected, _ = selected_profile()
        if accepts(selected, tracks[0].get("ext")):
            validate(selected)
            profile = selected
            keep = keep_original()
        elif selected.get("ext") != "COPY":
            print("[encode] The profile does not support this extension; keeping the original.")
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".yewdroid-choice-", dir=folder)
    try:
        paths = []
        def progress(data):
            if data.get("status") == "downloading":
                total = data.get("total_bytes") or data.get("total_bytes_estimate")
                if total:
                    print("\r  Downloading %s%.1f%%%s" % (c.y, 100 * data.get("downloaded_bytes", 0) / total, c.w), end="", flush=True)
        for index, track in enumerate(tracks):
            format_id = str(track["format_id"])
            print("\n[download] Format %s (%s)" % (format_id, track.get("ext")))
            def exact(ctx, wanted=format_id):
                matches = [fmt for fmt in ctx.get("formats", []) if str(fmt.get("format_id")) == wanted
                           and fmt.get("url") and not fmt.get("has_drm")]
                if not matches:
                    raise RuntimeError("The chosen format is no longer available; it will not be replaced by another")
                yield matches[-1]
            opts = dict(options, format=exact, progress_hooks=[progress],
                        outtmpl=os.path.join(work, str(index) + "-%(title).100B-%(id)s.%(ext)s"))
            with yt_dlp.YoutubeDL(opts) as ydl:
                result = ydl.extract_info(url, download=True)
                if not result or result.get("is_live"):
                    raise RuntimeError("The chosen track was not received")
                if str(result.get("format_id")) != format_id or kind(result) != kind(track):
                    raise RuntimeError("The downloaded track does not match the selection")
                requests = result.get("requested_downloads") or []
                path = (requests[0].get("filepath") if requests else None) or ydl.prepare_filename(result)
            if os.path.commonpath([os.path.realpath(work), os.path.realpath(path)]) != os.path.realpath(work):
                raise RuntimeError("Unexpected download path")
            if not os.path.isfile(path) or os.path.getsize(path) == 0:
                raise RuntimeError("Downloaded file is missing or empty")
            if profile and not accepts(profile, os.path.splitext(path)[1].lstrip(".")):
                raise RuntimeError("The downloaded extension is no longer compatible with the profile")
            paths.append(path)
        output = paths[0]
        if len(paths) == 2:
            extension = ".mkv" if ffmpeg else ".mp4"
            output = os.path.splitext(paths[0])[0] + "-with-audio" + extension
            print("\n[download] Merging video and audio without re-encoding...")
            if ffmpeg:
                from android_ffmpeg import merge
                merge(paths[0], paths[1], output)
            else:
                from java import jclass
                if not can_mux(tracks[0], tracks[1]):
                    raise RuntimeError("Tracks are incompatible with the muxer")
                jclass("iecxiv.yewdroid.Mp4Muxer").merge(paths[0], paths[1], output)
        if profile:
            output = apply_profile(output, profile)
        if not os.path.isfile(output) or os.path.getsize(output) == 0:
            raise RuntimeError("Final file is missing or empty")
        destination = _publish(output)
        if not isinstance(destination, str) or not destination:
            raise RuntimeError("The export did not confirm the destination")
        if profile and keep and paths[0] != output:
            try:
                print("[encode] Original exported: " + str(_publish(paths[0])))
            except Exception as error:
                print("[encode] The original could not be exported: %s" % error)
    except BaseException:
        print("\n" + c.r + "Download interrupted. Files kept: " + work + c.w)
        raise
    if destination != os.path.abspath(output):
        shutil.rmtree(work, ignore_errors=True)
    elif profile and not keep and paths[0] != output:
        os.unlink(paths[0])
    print("\nSaved: " + c.g + destination + c.w)
    return destination
