"""Download tracks, mux on Android, and publish completed files to Downloads."""
import os
import shutil
import sys
import tempfile

from android_dl_common import Cancelled, is_bot_error, say, youtube_opts


def _publish(path):
    from java import jclass
    if jclass("android.os.Build$VERSION").SDK_INT < 29:
        say("Note: Android 8/9 keeps the previous destination; the file was not exported to public Downloads.")
        return os.path.abspath(path)
    app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
    return str(jclass("iecxiv.yewdroid.DownloadPublisher").publish(app, path))


def download_video(ytid, folder, hook, format_id=None, tracker=None, clients=None):
    """Downloads video + audio and muxes them. `format_id` picks a specific video format;
    `tracker` (android_dl_common.Progress) receives progress and can cancel; `clients` forces
    YouTube player clients."""
    import yt_dlp
    from java import jclass
    from yt_dlp.utils import sanitize_filename

    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    muxer = jclass("iecxiv.yewdroid.Mp4Muxer")
    work = tempfile.mkdtemp(prefix=".yewdroid-video-", dir=folder)
    url = "https://www.youtube.com/watch?v=%s" % ytid
    default_video = "bv[ext=mp4][vcodec^=avc1]"
    try:
        def fetch(label, selector):
            say("\n[download] %s" % label)

            def on_progress(data):
                hook(data)
                if tracker is not None and data.get("status") == "downloading":
                    total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
                    if total:
                        tracker.stage(label, data.get("downloaded_bytes", 0) / total)

            opts = {
                "outtmpl": os.path.join(work, label + ".%(ext)s"),
                "format": selector,
                "noplaylist": True,
                "quiet": True,
                "no_warnings": False,
                "progress_hooks": [on_progress],
                "fixup": "never",
                "hls_prefer_native": True,
                "postprocessors": [],
                "match_filter": lambda info, *, incomplete=False: (
                    "Live streams are not supported" if info.get("is_live") else None),
            }
            opts.update(youtube_opts(clients))
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=True)
                if not info:
                    raise RuntimeError("No data received for %s" % label)
                path = ydl.prepare_filename(info)
            if not os.path.isfile(path) or os.path.getsize(path) == 0:
                raise RuntimeError("Incomplete track: %s" % label)
            return info, path

        # A chosen format id may not exist for another player client: fall back to the default pick.
        video_selector = ("%s/%s" % (format_id, default_video)) if format_id else default_video
        vi, video = fetch("video", video_selector)
        _, audio = fetch("audio", "ba[ext=m4a][acodec^=mp4a]")
        title = sanitize_filename(str(vi.get("title") or "video"), restricted=False)[:120]
        safe_id = sanitize_filename(str(ytid), restricted=True)[:32]
        output = os.path.join(work, "%s-%s.mp4" % (title, safe_id))
        # Track filenames must remain distinct even for a pathological title/id.
        if output in (video, audio):
            output = os.path.join(work, "result.mp4")
        if tracker is not None:
            tracker.stage("merge")
        say("\n[download] Merging video and audio without re-encoding...")
        muxer.merge(video, audio, output)
        if not os.path.isfile(output) or os.path.getsize(output) == 0:
            raise RuntimeError("The muxer did not produce a valid MP4")
        if tracker is not None:
            tracker.stage("export")
        say("[download] Exporting to public Downloads...")
        destination = _publish(output)
        # On Android 8/9 the local output is the final file: do not delete it.
        if destination == os.path.abspath(output):
            say("\nSaved locally: %s" % destination)
            return destination
    except BaseException as exc:
        if tracker is not None and tracker.cancelled:
            shutil.rmtree(work, ignore_errors=True)
            raise Cancelled() from None
        if is_bot_error(exc):
            # Nothing was downloaded: the caller may retry with another player client.
            shutil.rmtree(work, ignore_errors=True)
            raise
        message = "Video download/export interrupted. Files kept in: %s" % work
        say(message)
        if isinstance(exc, Exception):
            raise RuntimeError("%s\n%s" % (exc, message)) from exc
        raise
    else:
        shutil.rmtree(work, ignore_errors=True)
        say("\nSaved: %s" % destination)
        return destination


def _download_audio(original, ytid, folder):
    from android_audio_download import download_audio
    return download_audio(ytid, folder, _publish)


def install(pafy):
    """Use strict audio selection; return destinations and adapt commands."""
    if getattr(pafy.download_video, "_android_video_mux", False):
        return
    original = pafy.download_video

    def dispatch(ytid, folder, audio_only=False):
        if audio_only:
            return _download_audio(original, ytid, folder)

        def hook(data):
            if data.get("status") == "downloading":
                total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
                if total:
                    sys.stdout.write("\r  Downloading %.1f%%   " % (
                        100.0 * data.get("downloaded_bytes", 0) / total))
                    sys.stdout.flush()
        return download_video(ytid, folder, hook)

    dispatch._android_video_mux = True
    pafy.download_video = dispatch
    from android_commands import install as install_commands
    install_commands(pafy)
