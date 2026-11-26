"""Settings of the yewtube session for the native Settings screen: read, change and reset."""
import json
import sys
import traceback

from android_config import DEFAULTS

KEYS = ("AUDIO_FORMAT", "VIDEO_FORMAT", "MAX_RES", "ORDER", "COLUMNS", "SHOW_VIDEO", "ALWAYS_REPEAT",
        "HISTORY")
COLUMNS = ("views", "comments", "rating", "date", "time", "user", "likes", "dislikes", "category", "ytid")
RESOLUTIONS = (360, 480, 720, 1080, 1440, 2160)


def _log():
    err = getattr(sys.modules.get("yt_term"), "_orig_err", None) or sys.__stderr__
    try:
        traceback.print_exc(file=err)
    except Exception:
        pass


def snapshot():
    """JSON with the options, the encoding presets and whether FFmpeg is packaged."""
    from mps_youtube import config, g
    options = []
    for key in KEYS:
        try:
            item = config[key]
            entry = {"key": key, "kind": "choice", "flag": False, "value": "", "options": []}
            if key == "COLUMNS":
                entry.update(kind="columns", value=str(item.value), options=list(COLUMNS))
            elif key == "MAX_RES":
                values = sorted(set(RESOLUTIONS) | {int(item.value)})
                entry.update(value=str(item.value), options=[str(v) for v in values])
            elif item.type is bool:
                entry.update(kind="bool", flag=bool(item.value))
            else:
                entry.update(value=str(item.value), options=[str(v) for v in item.allowed_values])
            options.append(entry)
        except Exception:
            _log()
    encoders = [{"index": index, "name": str(preset.get("name", index)), "ext": str(preset.get("ext", ""))}
                for index, preset in enumerate(g.encoders)]
    try:
        from android_ffmpeg import binaries
        binaries()
        ffmpeg = True
    except Exception:
        ffmpeg = False
    return json.dumps({"options": options, "encoders": encoders,
                       "encoder": int(config.ENCODER.value), "ffmpeg": ffmpeg}, ensure_ascii=False)


def _expected(item, text):
    if item.type is bool:
        return text.lower() in ("1", "true", "yes", "on")
    if item.type is int:
        return int(text) if text.isdigit() else None
    return text


def set_value(key, value):
    """Changes one option with yewtube's own validation and saves it; reports whether it took effect."""
    from mps_youtube import config
    key = str(key).upper()
    if key not in KEYS + ("ENCODER",):
        return json.dumps({"ok": False})
    ok = False
    try:
        item = config[key]
        text = str(value).strip()
        expected = _expected(item, text)
        if expected is not None:
            item.set(text)
            ok = item.value == expected
    except Exception:
        _log()
    return json.dumps({"ok": ok})


def reset():
    """Restores the options shown in the app (and the encoder) to their defaults."""
    from mps_youtube import config
    for key in KEYS + ("ENCODER",):
        item = config[key]
        item.value = DEFAULTS.get(key, item.default)
        item.temp_value = None
    config.save()
    return True
