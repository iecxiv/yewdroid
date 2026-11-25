"""Explicit Android MP3 conversion; never changes automatic audio downloads."""
import os
import re
import shutil
import subprocess
import tempfile

HELP = """encode [N]
Abre el menu de conversion a MP3 de un video de la lista actual.
Si no indicas N, pregunta el numero. Elige 128, 192, 256 o 320 kbps.
Enter cancela antes de descargar. Requiere FFmpeg con libmp3lame incluido.
Descarga el mejor audio y lo convierte; da mantiene el audio original.
No convierte archivos ya descargados ni configura conversion automatica.
"""


def ask_number(prompt, allowed):
    while True:
        answer = input(prompt).strip()
        if not answer:
            return None
        if answer.isdigit() and int(answer) in allowed:
            return int(answer)
        print("Seleccion fuera de rango.")


def convert(song, folder, bitrate):
    import yt_dlp
    from android_ffmpeg import binaries, encode_mp3
    from android_video_download import _publish
    ffmpeg, ffprobe = binaries()
    for path in (ffmpeg, ffprobe):
        subprocess.run([path, "-version"], check=True, capture_output=True, timeout=15)
    encoders = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], check=True,
                             capture_output=True, text=True, timeout=20)
    if not re.search(r"^\s*[A-Z.]+\s+libmp3lame\s", encoders.stdout, re.MULTILINE):
        raise RuntimeError("Esta compilacion FFmpeg no dispone de libmp3lame; no se descargo nada")
    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".yewdroid-encode-", dir=folder)
    options = {"format": "ba", "quiet": True, "noplaylist": True, "postprocessors": [],
               "fixup": "never", "hls_prefer_native": True, "retries": 3,
               "fragment_retries": 3, "socket_timeout": 25,
               "outtmpl": os.path.join(work, "original-%(title).100B-%(id)s.%(ext)s"),
               "match_filter": lambda info, *, incomplete=False: (
                   "No se admiten emisiones en directo" if info.get("is_live") else None)}
    try:
        print("[encode] Descargando el mejor audio original...")
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % song.ytid, download=True)
            if not info or info.get("vcodec") != "none" or info.get("acodec") in (None, "none"):
                raise RuntimeError("No se recibio una pista exclusivamente de audio")
            requests = info.get("requested_downloads") or []
            source = (requests[0].get("filepath") if requests else None) or ydl.prepare_filename(info)
        if os.path.commonpath([os.path.realpath(work), os.path.realpath(source)]) != os.path.realpath(work):
            raise RuntimeError("Ruta de audio inesperada")
        if not os.path.isfile(source) or os.path.getsize(source) == 0:
            raise RuntimeError("Audio ausente o vacio")
        filename = os.path.basename(os.path.splitext(source)[0])
        output = os.path.join(work, filename.removeprefix("original-") + "-%dk.mp3" % bitrate)
        print("[encode] Convirtiendo a MP3 %d kbps..." % bitrate)
        encode_mp3(source, output, bitrate)
        if not os.path.isfile(output) or os.path.getsize(output) == 0:
            raise RuntimeError("FFmpeg no genero un MP3 terminado")
        destination = _publish(output)
        if not isinstance(destination, str) or not destination:
            raise RuntimeError("La exportacion no confirmo el destino")
    except BaseException:
        print("[encode] Interrumpido. Archivos conservados: " + work)
        raise
    if destination != os.path.abspath(output):
        shutil.rmtree(work, ignore_errors=True)
    return destination


def install():
    from mps_youtube import g, config, content, screen
    from mps_youtube.commands import Command
    if getattr(g, "_android_encode_installed", False):
        return

    def command(number=None):
        try:
            if g.browse_mode != "normal" or not len(g.model):
                raise RuntimeError("Abre una lista de videos antes de usar encode")
            g.content = HELP
            screen.update()
            selected = int(number) if number else ask_number("Numero de video (Enter cancela): ", range(1, len(g.model) + 1))
            if selected is None:
                message = "Conversion cancelada; no se descargo nada."
            elif not 1 <= selected <= len(g.model):
                raise RuntimeError("Numero de video fuera de rango")
            else:
                bitrate = ask_number("MP3 kbps [128/192/256/320] (Enter cancela): ", (128, 192, 256, 320))
                if bitrate is None:
                    message = "Conversion cancelada; no se descargo nada."
                else:
                    destination = convert(g.model[selected - 1], config.DDIR.get, bitrate)
                    message = "MP3 guardado: " + destination
        except KeyboardInterrupt:
            message = "Conversion interrumpida; revisa la ruta de archivos conservados."
        except Exception as error:
            message = "Error de conversion: " + str(error)
        g.content = content.generate_songlist_display()
        g.message = message

    g.commands.insert(0, Command(re.compile(r"encode(?:\s+(\d{1,4}))?\s*$", re.IGNORECASE), None, None, command))
    g._android_encode_installed = True
