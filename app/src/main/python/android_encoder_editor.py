"""Edit the original private transcode file with validation and atomic replacement."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
import threading

LOCK = threading.RLock()


def parse(text):
    if len(text.encode("utf-8")) > 120000:
        raise ValueError("File exceeds the 120 KB editor limit")
    profiles = [{"name": "None", "ext": "COPY", "valid": "*"}]
    block, names = {}, {"None"}
    delete = True
    for number, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        def error(message):
            raise ValueError("Line %d: %s" % (number, message))
        if raw != raw.lstrip():
            error("Fields must start at the first column, as in the original file")
        if ":" not in raw:
            error("Expected a field followed by ':'")
        key, value = raw.split(":", 1)
        value = value.strip()
        if key in ("ENCODER_PATH", "TRANSCODER_PATH"):
            if value != "auto":
                error("Android uses its bundled FFmpeg; keep this path set to auto")
            continue
        if key == "DELETE_ORIGINAL":
            if value.lower() not in ("true", "false", "yes", "no", "enabled", "disabled", "on", "off"):
                error("Expected True or False")
            delete = value.lower() in ("true", "yes", "enabled", "on")
            continue
        if key == "name":
            if block:
                error("Previous profile has no command")
            if not value or value in names:
                error("Profile names must be nonempty and unique")
            names.add(value)
            block = {"name": value}
        elif key in ("extension", "valid for"):
            field = "ext" if key == "extension" else "valid"
            if not block or field in block:
                error("Missing name or repeated field")
            if not re.fullmatch(r"[A-Za-z0-9]+" if field == "ext" else r"[A-Za-z0-9]+(?:,[A-Za-z0-9]+)*", value):
                error("Invalid extension list; use comma-separated extensions without spaces")
            block[field] = value
        elif key == "command":
            if not all(field in block for field in ("name", "ext", "valid")):
                error("A command needs name, extension and valid for")
            try:
                tokens = shlex.split(value)
            except ValueError as exc:
                error(str(exc))
            if not tokens or tokens[0] != "ENCODER_PATH" or "IN" not in tokens or not any(t in ("OUT", "OUT.EXT") for t in tokens):
                error("Use ENCODER_PATH, IN and OUT or OUT.EXT")
            block["command"] = value
            profiles.append(block)
            block = {}
        else:
            error("Unknown field: " + key)
    if block:
        raise ValueError("Last profile has no command")
    return profiles, delete


def digest(data):
    return hashlib.sha256(data).hexdigest()


def atomic(path, data):
    path = Path(path)
    handle = tempfile.NamedTemporaryFile(dir=path.parent, prefix=".transcode-", delete=False)
    temporary = Path(handle.name)
    try:
        with handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def read_document():
    from mps_youtube import g
    with LOCK:
        path = Path(g.TCFILE)
        data = path.read_bytes()
        if len(data) > 120000:
            raise ValueError("File exceeds the 120 KB editor limit")
        return json.dumps({"text": data.decode("utf-8"), "sha256": digest(data), "path": str(path)})


def save_document(text, expected):
    from mps_youtube import g, config
    profiles, delete = parse(text)
    with LOCK:
        path = Path(g.TCFILE)
        original = path.read_bytes()
        if digest(original) != expected:
            raise ValueError("The file changed while editing. Cancel and reopen the editor")
        old_profiles, old_delete = g.encoders, g.delete_orig
        old_value, old_temp = config.ENCODER.value, config.ENCODER.temp_value
        config_path = Path(g.CFFILE)
        config_original = config_path.read_bytes() if config_path.exists() else None
        def remap(index):
            if isinstance(index, int) and 0 <= index < len(old_profiles):
                name = old_profiles[index]["name"]
                return next((i for i, p in enumerate(profiles) if p["name"] == name), 0)
            return 0
        data = text.encode("utf-8")
        atomic(Path(str(path) + ".bak"), original)
        try:
            atomic(path, data)
            g.encoders, g.delete_orig = profiles, delete
            config.ENCODER.value = remap(old_value)
            config.ENCODER.temp_value = remap(old_temp) if old_temp is not None else None
            config.save()
        except BaseException:
            atomic(path, original)
            g.encoders, g.delete_orig = old_profiles, old_delete
            config.ENCODER.value, config.ENCODER.temp_value = old_value, old_temp
            if config_original is not None:
                atomic(config_path, config_original)
            else:
                config_path.unlink(missing_ok=True)
            raise
        return json.dumps({"sha256": digest(data), "message": "Saved. Profiles reloaded. Backup: " + str(path) + ".bak"})


def help_topic():
    from mps_youtube import c
    return ("edit-encoders", "Editing encoding presets on Android", """
    {0}Editing encoding presets on Android{1}

    Enter {2}edit encoders{1} to edit the original transcoding configuration.

    {2}Save{1} validates the file, creates a backup and reloads the presets.
    {2}Cancel{1} returns without changing the configuration.

    Keep the original field names and command placeholders unchanged.
    If the selected preset is removed, the selection returns to None.
    An ongoing conversion keeps the preset it started with.

    Editing a preset does not install an encoder or change the FFmpeg build.
    """.format(c.ul, c.w, c.y))


def install():
    from mps_youtube import g
    from mps_youtube.commands import Command
    if getattr(g, "_android_encoder_editor_installed", False):
        return
    def open_editor():
        from java import jclass
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        Intent = jclass("android.content.Intent")
        intent = Intent(app, jclass("iecxiv.yewdroid.EncoderEditorActivity"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    g.commands.insert(0, Command(re.compile(r"edit\s+encoders\s*$", re.IGNORECASE), None, None, open_editor))
    g._android_encoder_editor_installed = True
