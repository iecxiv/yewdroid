"""Explicit choice, best selectors, and original audio encoding profiles."""
import os
import shutil
import subprocess
import tempfile


class DownloadCancelled(Exception):
    pass


def kind(fmt):
    video = fmt.get("vcodec") not in (None, "none")
    audio = fmt.get("acodec") not in (None, "none")
    return "video+audio" if video and audio else "video" if video else "audio" if audio else "otro"


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
            raise RuntimeError("El binario FFmpeg empaquetado no arranca: " + os.path.basename(path))
    return True


def choose(rows, title, ffmpeg=False):
    from mps_youtube import g, screen
    lines = [title, "N  ID  Tipo  Extension  Calidad  Codec video / audio  Tamaño aprox."]
    for number, fmt in enumerate(rows, 1):
        size = fmt.get("filesize") or fmt.get("filesize_approx")
        size = "%.1f MiB" % (size / 1048576) if size else "?"
        quality = fmt.get("resolution") or ("%sp" % fmt["height"] if fmt.get("height") else "%s kbps" % fmt.get("abr", "?"))
        lines.append("%d  %s  %s  %s  %s  %s / %s  %s" % (
            number, fmt.get("format_id"), kind(fmt), fmt.get("ext"), quality,
            fmt.get("vcodec"), fmt.get("acodec"), size))
    note = "FFmpeg: union a MKV sin recodificar; depende del soporte de la compilacion." if ffmpeg else "Sin FFmpeg: union solo MP4/H.264 + M4A/AAC."
    lines += ["", note, "Enter cancela sin descargar."]
    g.content = "\n".join(lines)
    screen.update()
    while True:
        answer = input("Formato (numero de fila): ").strip()
        if not answer:
            raise DownloadCancelled()
        if answer.isdigit() and 1 <= int(answer) <= len(rows):
            return rows[int(answer) - 1]
        print("Seleccion fuera de rango.")


def plan(ydl, info, mode, ffmpeg=False):
    rows = [fmt for fmt in info.get("formats", []) if fmt.get("url")
            and not fmt.get("has_drm") and kind(fmt) != "otro"]
    if not rows:
        raise RuntimeError("No hay formatos descargables en esta extraccion")
    if mode in ("d", "dl", "download"):
        selected = choose(rows, str(info.get("title") or info.get("id")), ffmpeg)
        if kind(selected) != "video":
            return [selected]
        audio = [fmt for fmt in rows if kind(fmt) == "audio" and (ffmpeg or can_mux(selected, fmt))]
        if not audio:
            raise RuntimeError("No hay audio compatible con el backend disponible; no se descargo nada")
        return [selected, choose(audio, "Selecciona el audio para unir al video", ffmpeg)]
    selector = "ba" if mode.startswith("da") else "bv*+ba/b"
    selections = list(ydl.build_format_selector(selector)({"formats": rows, "incomplete_formats": False, "has_merged_format": any(kind(fmt) == "video+audio" for fmt in rows)}))
    if not selections:
        raise RuntimeError("No hay formato disponible para " + selector)
    selected = selections[0]
    tracks = selected.get("requested_formats") or [selected]
    if mode.startswith("da") and any(kind(fmt) != "audio" for fmt in tracks):
        raise RuntimeError("El mejor formato no es exclusivamente de audio")
    if len(tracks) > 1:
        if len(tracks) != 2 or kind(tracks[0]) != "video" or kind(tracks[1]) != "audio":
            raise RuntimeError("Combinacion de pistas no admitida")
        if not ffmpeg and not can_mux(tracks[0], tracks[1]):
            raise RuntimeError("El mejor video/audio requiere FFmpeg. Sin FFmpeg solo se une MP4/H.264 + M4A/AAC; no se descargo nada ni se eligio una calidad inferior")
    return tracks


def download(ytid, folder, mode):
    import yt_dlp
    from android_video_download import _publish
    from android_encode import selected_profile, accepts, validate, apply_profile
    folder = os.path.abspath(folder)
    options = {"quiet": True, "noplaylist": True, "postprocessors": [], "fixup": "never",
               "hls_prefer_native": True, "retries": 3, "fragment_retries": 3, "socket_timeout": 25}
    url = "https://www.youtube.com/watch?v=%s" % ytid
    ffmpeg = False if mode.startswith("da") else ffmpeg_ready()
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
        if not info or info.get("is_live"):
            raise RuntimeError("No se admiten emisiones en directo o extracciones vacias")
        tracks = plan(ydl, info, mode, ffmpeg)
    profile = None
    delete_original = True
    if len(tracks) == 1 and kind(tracks[0]) == "audio":
        selected, delete_original = selected_profile()
        if accepts(selected, tracks[0].get("ext")):
            validate(selected)
            profile = selected
        elif selected.get("ext") != "COPY":
            print("[encode] El perfil no admite esta extension; se conserva el original.")
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".yewdroid-choice-", dir=folder)
    try:
        paths = []
        def progress(data):
            if data.get("status") == "downloading":
                total = data.get("total_bytes") or data.get("total_bytes_estimate")
                if total:
                    print("\r  Descargando %.1f%%" % (100 * data.get("downloaded_bytes", 0) / total), end="", flush=True)
        for index, track in enumerate(tracks):
            format_id = str(track["format_id"])
            print("\n[descarga] Formato %s (%s)" % (format_id, track.get("ext")))
            def exact(ctx, wanted=format_id):
                matches = [fmt for fmt in ctx.get("formats", []) if str(fmt.get("format_id")) == wanted
                           and fmt.get("url") and not fmt.get("has_drm")]
                if not matches:
                    raise RuntimeError("El formato elegido ya no esta disponible; no se sustituye por otro")
                yield matches[-1]
            opts = dict(options, format=exact, progress_hooks=[progress],
                        outtmpl=os.path.join(work, str(index) + "-%(title).100B-%(id)s.%(ext)s"))
            with yt_dlp.YoutubeDL(opts) as ydl:
                result = ydl.extract_info(url, download=True)
                if not result or result.get("is_live"):
                    raise RuntimeError("No se recibio la pista elegida")
                if str(result.get("format_id")) != format_id or kind(result) != kind(track):
                    raise RuntimeError("La pista descargada no coincide con la seleccion")
                requests = result.get("requested_downloads") or []
                path = (requests[0].get("filepath") if requests else None) or ydl.prepare_filename(result)
            if os.path.commonpath([os.path.realpath(work), os.path.realpath(path)]) != os.path.realpath(work):
                raise RuntimeError("Ruta de descarga inesperada")
            if not os.path.isfile(path) or os.path.getsize(path) == 0:
                raise RuntimeError("Archivo descargado ausente o vacio")
            if profile and not accepts(profile, os.path.splitext(path)[1].lstrip(".")):
                raise RuntimeError("La extension descargada ya no es compatible con el perfil")
            paths.append(path)
        output = paths[0]
        if len(paths) == 2:
            extension = ".mkv" if ffmpeg else ".mp4"
            output = os.path.splitext(paths[0])[0] + "-con-audio" + extension
            print("\n[descarga] Uniendo video y audio sin recodificar...")
            if ffmpeg:
                from android_ffmpeg import merge
                merge(paths[0], paths[1], output)
            else:
                from java import jclass
                if not can_mux(tracks[0], tracks[1]):
                    raise RuntimeError("Pistas incompatibles con el multiplexador")
                jclass("iecxiv.yewdroid.Mp4Muxer").merge(paths[0], paths[1], output)
        if profile:
            output = apply_profile(output, profile)
        if not os.path.isfile(output) or os.path.getsize(output) == 0:
            raise RuntimeError("Archivo final ausente o vacio")
        destination = _publish(output)
        if not isinstance(destination, str) or not destination:
            raise RuntimeError("La exportacion no confirmo el destino")
    except BaseException:
        print("\nDescarga interrumpida. Archivos conservados: " + work)
        raise
    if profile and not delete_original:
        print("[encode] Original conservado localmente: " + paths[0])
    elif destination != os.path.abspath(output):
        shutil.rmtree(work, ignore_errors=True)
    elif profile and delete_original and paths[0] != output:
        os.unlink(paths[0])
    print("\nGuardado: " + destination)
    return destination
