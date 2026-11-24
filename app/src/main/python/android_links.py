"""Open and share result links; sharing never sends a message automatically."""
import re
import sys
from urllib.parse import urlencode

_installed = False
HELP = {
    "open": (
        "Abrir un enlace\n\n"
        "open N  Abre el enlace del resultado N en una app compatible.\n"
        "N corresponde a un video o una playlist de YouTube de la lista actual.\n\n"
        "Ayuda: help open o h open.\n"
        "Pulsa ENTER para volver a la lista."
    ),
    "share": (
        "Compartir un enlace\n\n"
        "share N  Abre el selector para compartir el enlace del resultado N.\n"
        "N corresponde a un video o una playlist de YouTube de la lista actual.\n"
        "Se comparte el enlace de YouTube, no un archivo descargado.\n"
        "No se envia nada automaticamente: confirma en la app elegida.\n\n"
        "Ayuda: help share o h share.\n"
        "Pulsa ENTER para volver a la lista."
    ),
}


def _help_topics(topics):
    result = []
    for name, title, body in topics:
        if name in HELP:
            continue
        if name == "basic":
            body = "\n".join(line for line in body.split("\n") if not re.match(
                r"\s*[xX]\s+<number>", re.sub(r"\x1b\[[0-9;]*m", "", line)))
            body += ("\n    open N  - Abre el enlace del resultado N.\n"
                     "    share N - Comparte el enlace del resultado N.\n")
        elif name == "playlists":
            body = body.replace("open <name or ID>", "open <name>")
        result.append((name, title, body))
    result.extend((name, "Abrir enlaces" if name == "open" else "Compartir enlaces", text)
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
            result = jclass("iecxiv.yewtube.AndroidLinks").perform(app, action, url)
            finish(str(result))
        except Exception as error:
            finish("No se pudo abrir o compartir el enlace: %s" % error)

    @command(r"(?:help|h)\s+(open|share)")
    @command(r"(open|share)")
    def link_help(action):
        g.content = HELP[action]
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
