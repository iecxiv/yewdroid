"""User-invoked Android link actions; never sends a message automatically."""
from urllib.parse import urlencode

_installed = False


def install():
    global _installed
    if _installed:
        return
    from mps_youtube import content, g
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

    @command(r"android\s+(open|share)\s+(\d{1,4})", "android open", "android share")
    def link_action(action, number):
        try:
            url = current_link(number)
            from java import jclass
            app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
            result = jclass("iecxiv.yewtube.AndroidLinks").perform(app, action, url)
            finish(str(result))
        except Exception as error:
            finish("No se pudo ejecutar la accion Android: %s" % error)

    @command(r"android(?:\s+help)?", "android", "android help")
    def help_android():
        g.content = (
            "Integracion Android\n\n"
            "android open N   Abre el enlace del resultado N en una app compatible.\n"
            "android share N  Abre el selector para compartir su enlace.\n"
            "x N              Copia el enlace de YouTube.\n"
            "X N              Copia el stream, si se puede extraer.\n\n"
            "Compartir no envia nada automaticamente: confirma en la app elegida.\n"
            "Se comparten enlaces de YouTube, no archivos descargados.\n"
            "Pulsa ENTER para volver a la lista."
        )
        g.message = ""

    _installed = True
