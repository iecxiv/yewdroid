"""Locate packaged FFmpeg CLI binaries; do not copy executables to writable storage."""
import os
import subprocess
import tempfile
import time


def binaries():
    from java import jclass
    app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
    directory = str(app.getApplicationInfo().nativeLibraryDir)
    result = tuple(os.path.join(directory, name) for name in ("libffmpeg.so", "libffprobe.so"))
    for path in result:
        if not os.path.isfile(path) or not os.access(path, os.X_OK):
            raise RuntimeError("FFmpeg is not packaged or not executable: " + path)
    return result


def run(arguments):
    ffmpeg, _ = binaries()
    return subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-n", *arguments], check=True)


def run_cancellable(arguments, cancelled):
    """Like run(), but kills FFmpeg and raises Cancelled once cancelled() returns true."""
    from android_dl_common import Cancelled
    ffmpeg, _ = binaries()
    with tempfile.TemporaryFile() as log:
        process = subprocess.Popen([ffmpeg, "-nostdin", "-hide_banner", "-n", *arguments],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=log)
        try:
            while process.poll() is None:
                if cancelled():
                    raise Cancelled()
                time.sleep(0.2)
        except BaseException:
            if process.poll() is None:
                process.kill()
                process.wait()
            raise
        if process.returncode != 0:
            log.seek(0)
            lines = log.read().decode("utf-8", "replace").strip().splitlines()
            raise RuntimeError("FFmpeg failed: " + " | ".join(lines[-2:]))


def merge(video, audio, output):
    run(["-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", output])


def encode_mp3(source, output, bitrate=192):
    if bitrate not in (128, 192, 256, 320):
        raise ValueError("MP3 bitrate not supported")
    run(["-i", source, "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", str(bitrate) + "k", output])
