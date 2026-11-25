"""Remove desktop-only help and commands without deleting internal settings."""
import re
import sys

REMOVED = frozenset("dl-command encode invoke config lastfm tips new changelog tor".split())
HELP_ALIASES = frozenset((
    "dlcmd dl-cmd download-cmd dl_cmd download_cmd download-command download_command "
    "encoding transcoding transcode wma mp3 format encoder encoders "
    "command commands yt invocation undump dump -f -w -a adv advanced "
    "set showconfig checkupdate colours colors ddir directory player arguments args playerargs "
    "music search_music keys status show_status video configuration fullscreen full screen folder "
    "mpv mplayer settings default reset configure audio results max_results size lines rows height "
    "window position window_pos quality resolution max_res columns width console overwrite "
    "lastfm_connect"
).split())
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def _clean_basic_help(body):
    body = body.replace(" (See the note below)", "")
    return re.sub(
        r"^[ \t]*Note: This feature requires `pyperclip`.*?for more info\.[ \t]*(?:\n|$)",
        "", body, flags=re.MULTILINE | re.DOTALL)


def install():
    from mps_youtube import config, g, helptext
    from mps_youtube.commands import Command, config as config_commands, lastfm
    from android_encode import HELP as encode_help, install as install_encode
    if getattr(g, "_android_features_installed", False):
        return

    original_topics = helptext.helptext
    original_help = helptext.get_help

    def topics():
        result = []
        for name, title, body in original_topics():
            if name not in REMOVED:
                if name == "basic":
                    body = _clean_basic_help(body)
                # Other retained topics must not advertise the disabled set command.
                body = "\n".join(line for line in body.split("\n")
                                 if not re.match(r"\s*set(?:\s|$)", ANSI.sub("", line)))
                result.append((name, title, body))
        result.append(("encode", "Conversion MP3 en Android", encode_help))
        return result

    def get_help(choice=None):
        name = (choice or "").strip().lower()
        if name in ("encode", "encoding", "mp3", "transcode", "transcoding"):
            return encode_help
        if name in REMOVED or name in HELP_ALIASES:
            return "Este apartado no esta disponible en la app Android.\nUsa help para ver las opciones disponibles."
        return original_help(choice)

    # Replace imported-by-value aliases too, notably misc.get_help and init.helptext.
    for name, module in tuple(sys.modules.items()):
        if name.startswith("mps_youtube.") and module is not None:
            for attribute, value in tuple(vars(module).items()):
                if value is original_topics:
                    setattr(module, attribute, topics)
                elif value is original_help:
                    setattr(module, attribute, get_help)

    def unavailable(*args, **kwargs):
        from mps_youtube import content
        g.content = content.generate_songlist_display()
        g.message = "Opcion desactivada en la app Android. Usa help."

    blocked_modules = {config_commands.__name__, lastfm.__name__}
    g.commands[:] = [entry for entry in g.commands
                     if getattr(entry.function, "__module__", "") not in blocked_modules]
    names = REMOVED | {"set", "showconfig", "encoder", "encoders", "lastfm_connect"}
    pattern = r"(?:%s)(?:\s+.*)?$" % "|".join(re.escape(name) for name in sorted(names))
    g.commands.insert(0, Command(re.compile(pattern, re.IGNORECASE), None, None, unavailable))

    def no_lastfm(*args, **kwargs):
        return None

    lastfm.has_pylast = False
    lastfm.init_network = no_lastfm
    lastfm.scrobble_track = no_lastfm
    lastfm.set_now_playing = no_lastfm
    g.lastfm_network = None
    g.scrobble = False

    def enforce():
        for key, value in {"DOWNLOAD_COMMAND": "", "ENCODER": 0,
                           "LOOKUP_METADATA": False, "CHECKUPDATE": False}.items():
            setting = getattr(config, key, None)
            if setting is not None:
                setting.value = value
                setting.temp_value = None

    original_load = config.load

    def load(*args, **kwargs):
        result = original_load(*args, **kwargs)
        enforce()
        return result

    config.load = load
    enforce()
    install_encode()
    g._android_features_installed = True
