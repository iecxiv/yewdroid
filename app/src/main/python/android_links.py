"""Open and share result links; sharing never sends a message automatically."""
import re
import sys
from urllib.parse import urlencode

_installed = False
HELP = {
    "open": (
        "\n    Opening Links\n\n"
        "    open N - open result N's link in a compatible app.\n"
        "    N refers to a video or YouTube playlist in the current list.\n"
    ),
    "share": (
        "\n    Sharing Links\n\n"
        "    share N - open the share chooser for result N's link.\n"
        "    N refers to a video or YouTube playlist in the current list.\n"
        "    Shares the YouTube link, not a downloaded file.\n"
        "    Nothing is sent automatically: confirm in the app you choose.\n"
    ),
}


def _format_help(text):
    # Match the extra two-space indentation applied by upstream get_help.
    return "\n  ".join(text.split("\n"))


def _help_topics(topics):
    result = []
    for name, title, body in topics:
        if name in HELP:
            continue
        if name == "basic":
            body = "\n".join(line for line in body.split("\n") if not re.match(
                r"\s*[xX]\s+<number>", re.sub(r"\x1b\[[0-9;]*m", "", line)))
        elif name == "playlists":
            body = body.replace("open <name or ID>", "open <name>")
        result.append((name, title, body))
    result.extend((name, "Opening Links" if name == "open" else "Sharing Links", text)
                  for name, text in HELP.items())
    return result


def install():
    global _installed
    if _installed:
        return
    from mps_youtube import content, g, helptext
    from mps_youtube.commands import command

    def finish(message):
        g.content = content.generate_songlist_display()
        g.message = message

    def current_link(number):
        index = int(number) - 1
        if g.browse_mode == "ytpl":
            if index < 0 or index >= len(g.ytpls):
                raise ValueError("Numero de playlist fuera de rango")
            identifier = g.ytpls[index].get("link")
            path, key = "playlist", "list"
        elif g.browse_mode == "normal":
            if index < 0 or index >= len(g.model):
                raise ValueError("Numero de video fuera de rango")
            identifier = getattr(g.model[index], "ytid", None)
            path, key = "watch", "v"
        else:
            raise ValueError("Este modo no contiene enlaces de videos o playlists")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError("El elemento no contiene un identificador valido")
        return "https://www.youtube.com/%s?%s" % (path, urlencode({key: identifier}))

    @command(r"(open|share)\s+(\d{1,4})", "open", "share")
    def link_action(action, number):
        try:
            url = current_link(number)
            from java import jclass
            app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
            result = jclass("iecxiv.yewdroid.AndroidLinks").perform(app, action, url)
            finish(str(result))
        except Exception as error:
            finish("No se pudo abrir o compartir el enlace: %s" % error)

    @command(r"(?:help|h)\s+(open|share)")
    @command(r"(open|share)")
    def link_help(action):
        g.content = _format_help(HELP[action])
        g.message = ""

    # Numeric open now targets result links; open <name> remains a local playlist command.
    ours = [entry for entry in g.commands if entry.function in (link_action, link_help)]
    g.commands[:] = ours + [entry for entry in g.commands if entry.function not in (link_action, link_help)]
    original_topics = helptext.helptext

    def topics():
        return _help_topics(original_topics())

    for name, module in tuple(sys.modules.items()):
        if name.startswith("mps_youtube.") and module is not None:
            for attribute, value in tuple(vars(module).items()):
                if value is original_topics:
                    setattr(module, attribute, topics)
    _installed = True
