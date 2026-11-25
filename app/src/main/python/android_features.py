"""Remove desktop-only commands while preserving original encoding profiles."""
import re
import sys

REMOVED = frozenset("dl-command encode invoke config lastfm tips new changelog tor".split())
CONFIG_ALIASES = frozenset((
    "config set showconfig configuration configure settings columns audio default reset "
    "max_res video_format quality resolution order"
).split())
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
TOPIC_LINE = re.compile(r"^((?:\x1b\[[0-9;]*m)? {5})([^\s\x1b]+) *((?:\x1b\[[0-9;]*m)?) : (.*)$", re.MULTILINE)


def _align_topics(text):
    if not isinstance(text, str) or "Help Topics" not in text:
        return text
    found = TOPIC_LINE.findall(text)
    if not found:
        return text
    width = max(len(item[1]) for item in found)
    return TOPIC_LINE.sub(
        lambda m: "%s%s%s%s : %s" % (m.group(1), m.group(2), " " * (width - len(m.group(2))),
                                     m.group(3), m.group(4)), text)


def _clean_basic_help(body):
    body = body.replace(" (See the note below)", "")
    return re.sub(
        r"^[ \t]*Note: This feature requires `pyperclip`.*?for more info\.[ \t]*(?:\n|$)",
        "", body, flags=re.MULTILINE | re.DOTALL)


def _add_editor_hint(body, path, colors):
    marker = "       " + path
    if body.count(marker) != 1 or "edit encoders" in ANSI.sub("", body):
        return body
    hint = "You can also edit them within the app by entering %sedit encoders%s." % (colors.y, colors.w)
    return body.replace(marker, marker + "\n\n    " + hint, 1)


def install():
    from mps_youtube import c, config, g, helptext
    from mps_youtube.commands import Command, config as config_commands, lastfm
    from android_config import install as install_config, help_topic as config_help
    from android_encode import install as install_encode
    from android_encoder_editor import install as install_editor
    from android_encoder_defaults import help_topic as editor_help
    from android_search import install as install_search
    if getattr(g, "_android_features_installed", False):
        return

    original_topics = helptext.helptext
    original_help = helptext.get_help

    def topics():
        result = []
        for name, title, body in original_topics():
            if name == "encode":
                result.append((name, title, _add_editor_hint(body, g.TCFILE, c)))
                continue
            if name not in REMOVED:
                if name == "basic":
                    body = _clean_basic_help(body)
                body = "\n".join(line for line in body.split("\n")
                                 if not re.match(r"\s*set(?:\s|$)", ANSI.sub("", line)))
                result.append((name, title, body))
        result.append(config_help())
        result.append(editor_help())
        return result

    def get_help(choice=None):
        name = (choice or "").strip().lower()
        if name in ("edit-encoders", "edit_encoders", "edit encoders"):
            return original_help("edit-encoders")
        if name in CONFIG_ALIASES:
            return original_help("config")
        if name in ("encode", "encoder", "encoders", "encoding", "mp3", "wma", "format", "transcode", "transcoding"):
            return original_help("encode")
        if name in REMOVED or name in HELP_ALIASES:
            return "This section is not available in the Android app.\nUse help to see the available options."
        return _align_topics(original_help(choice))

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
        g.message = "Option disabled in the Android app. Use help."

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
        for key, value in {"DOWNLOAD_COMMAND": "",
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
    install_config()
    install_encode()
    install_editor()
    install_search()
    g._android_features_installed = True
