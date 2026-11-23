"""Download separate AVC/AAC tracks and mux them using Android, without FFmpeg."""
import os
import shutil
import sys
import tempfile
import uuid


def download_video(ytid, folder, hook):
    import yt_dlp
    from java import jclass
    from yt_dlp.utils import sanitize_filename

    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    muxer = jclass("com.example.ytlocal.Mp4Muxer")
    work = tempfile.mkdtemp(prefix=".yewtube-video-", dir=folder)
    url = "https://www.youtube.com/watch?v=%s" % ytid
    try:
        def fetch(label, selector):
            print("\n[descarga] %s" % label)
            opts = {
                "outtmpl": os.path.join(work, label + ".%(ext)s"),
                "format": selector,
                "noplaylist": True,
                "quiet": True,
                "no_warnings": False,
                "progress_hooks": [hook],
                "fixup": "never",
                "hls_prefer_native": True,
                "postprocessors": [],
            }
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=True)
                if not info:
                    raise RuntimeError("No se recibieron datos de %s" % label)
                path = ydl.prepare_filename(info)
            if not os.path.isfile(path) or os.path.getsize(path) == 0:
                raise RuntimeError("Pista incompleta: %s" % label)
            return info, path

        vi, video = fetch("video", "bv[ext=mp4][vcodec^=avc1]")
        if vi.get("is_live"):
            raise RuntimeError("Esta descarga no admite emisiones en directo")
        _, audio = fetch("audio", "ba[ext=m4a][acodec^=mp4a]")
        title = sanitize_filename(str(vi.get("title") or "video"), restricted=False)[:120]
        safe_id = sanitize_filename(str(ytid), restricted=True)[:32]
        stem = "%s-%s" % (title, safe_id)
        output = os.path.join(folder, stem + ".mp4")
        if os.path.exists(output):
            output = os.path.join(folder, stem + "-" + uuid.uuid4().hex[:12] + ".mp4")
        print("\n[descarga] Uniendo video y audio sin recodificar...")
        muxer.merge(video, audio, output)
        if not os.path.isfile(output) or os.path.getsize(output) == 0:
            raise RuntimeError("El multiplexador no genero un MP4 valido")
    except BaseException as exc:
        message = "Descarga de video interrumpida. Temporales conservados en: %s" % work
        print(message)
        sys.stderr.write(message + "\n")
        if isinstance(exc, Exception):
            raise RuntimeError("%s\n%s" % (exc, message)) from exc
        raise
    else:
        shutil.rmtree(work, ignore_errors=True)
        print("\nGuardado: %s" % output)
        return True


def install(pafy):
    """Preserve the existing audio implementation, replacing only video downloads."""
    if getattr(pafy.download_video, "_android_video_mux", False):
        return
    original = pafy.download_video

    def dispatch(ytid, folder, audio_only=False):
        if audio_only:
            return original(ytid, folder, audio_only=True)

        def hook(data):
            if data.get("status") == "downloading":
                total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
                if total:
                    sys.stdout.write("\r  Descargando %.1f%%   " % (
                        100.0 * data.get("downloaded_bytes", 0) / total))
                    sys.stdout.flush()
        return download_video(ytid, folder, hook)

    dispatch._android_video_mux = True
    pafy.download_video = dispatch
