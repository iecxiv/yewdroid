"""Locate packaged FFmpeg CLI binaries; do not copy executables to writable storage."""
import os
import subprocess


def binaries():
    from java import jclass
    app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
    directory = str(app.getApplicationInfo().nativeLibraryDir)
    result = tuple(os.path.join(directory, name) for name in ("libffmpeg.so", "libffprobe.so"))
    for path in result:
        if not os.path.isfile(path) or not os.access(path, os.X_OK):
            raise RuntimeError("FFmpeg no esta empaquetado o no es ejecutable: " + path)
    return result


def run(arguments):
    ffmpeg, _ = binaries()
    return subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-n", *arguments], check=True)


def merge(video, audio, output):
    run(["-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", output])


def encode_mp3(source, output, bitrate=192):
    if bitrate not in (128, 192, 256, 320):
        raise ValueError("Bitrate MP3 no admitido")
    run(["-i", source, "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", str(bitrate) + "k", output])
