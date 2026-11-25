"""Original yewtube profile selection with Android FFmpeg execution."""
import os
import re
import shlex
import subprocess

HELP = """encoder / encoders: original yewtube profiles.
set encoder N: persistent selection. set encoder default: None.
None keeps the original. da applies the profile if it supports the extension.
Parameters and valid formats are read from the original transcode file.
"""


def selected_profile():
    from mps_youtube import config, g
    index = config.ENCODER.get
    if not isinstance(index, int) or not 0 <= index < len(g.encoders):
        raise RuntimeError("Profile out of range; use encoders and set encoder N")
    return dict(g.encoders[index]), bool(g.delete_orig)


def accepts(profile, extension):
    return profile.get("ext") != "COPY" and extension in profile.get("valid", "").split(",")


def arguments(profile, source, output):
    tokens = shlex.split(profile.get("command", ""))
    if not tokens or tokens[0] != "ENCODER_PATH":
        raise RuntimeError("The profile must start with ENCODER_PATH")
    if "IN" not in tokens or not any(token in ("OUT", "OUT.EXT") for token in tokens):
        raise RuntimeError("The profile does not declare input and output")
    extension = profile.get("ext", "")
    if not re.fullmatch(r"[a-zA-Z0-9]+", extension):
        raise RuntimeError("Invalid profile extension")
    replacements = {"IN": source, "OUT": os.path.splitext(output)[0], "OUT.EXT": output}
    return [replacements.get(token, token) for token in tokens[1:]]


def validate(profile):
    from android_ffmpeg import binaries
    ffmpeg, ffprobe = binaries()
    for path in (ffmpeg, ffprobe):
        subprocess.run([path, "-version"], check=True, capture_output=True, timeout=15)
    tokens = arguments(profile, "input", "output." + profile.get("ext", ""))
    codec = None
    for flag in ("-codec:a", "-c:a", "-acodec"):
        if flag in tokens:
            index = tokens.index(flag) + 1
            if index >= len(tokens):
                raise RuntimeError("Profile has no encoder")
            codec = tokens[index]
            break
    if codec and codec != "copy":
        result = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], check=True,
                                capture_output=True, text=True, timeout=20)
        if not re.search(r"^\s*[A-Z.]+\s+" + re.escape(codec) + r"\s", result.stdout, re.MULTILINE):
            raise RuntimeError("Required encoder is missing: " + codec)


def apply_profile(source, profile):
    from android_ffmpeg import run
    output = os.path.splitext(source)[0] + "-encoded." + profile["ext"]
    print("\n[encode] " + profile.get("name", "profile"))
    run(arguments(profile, source, output))
    if not os.path.isfile(output) or os.path.getsize(output) == 0:
        raise RuntimeError("The conversion did not produce a finished file")
    return output


def install():
    from mps_youtube import g, config
    from mps_youtube.commands import Command
    from mps_youtube.commands.config import show_encs
    if getattr(g, "_android_encode_installed", False):
        return

    def select(value):
        if value.strip().lower() == "default":
            old_value, old_temp = config.ENCODER.value, config.ENCODER.temp_value
            config.ENCODER.value = config.ENCODER.default
            config.ENCODER.temp_value = None
            try:
                config.save()
            except BaseException:
                config.ENCODER.value, config.ENCODER.temp_value = old_value, old_temp
                raise
            message = "Encoder reset to None"
        else:
            message = config.ENCODER.set(value)
        show_encs()
        g.message = message

    g.commands.insert(0, Command(re.compile(r"encoders?\s*$", re.IGNORECASE), None, None, show_encs))
    g.commands.insert(0, Command(re.compile(r"set\s+encoder\s+(\S+)\s*$", re.IGNORECASE), None, None, select))
    g._android_encode_installed = True
