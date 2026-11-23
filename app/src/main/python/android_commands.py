"""Android command adapters: persistent downloads and native clipboard."""
import types


def install(pafy):
    from mps_youtube import config, content, g, util
    from mps_youtube.commands import download as downloads, misc

    if getattr(downloads, "_android_commands_installed", False):
        return

    def finish(message):
        g.content = content.generate_songlist_display()
        g.message = message

    def run_items(dltype, songs):
        import json
        from java import jclass
        from android_download_queue import enqueue
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        payload = [{"ytid": song.ytid, "title": str(song.title),
                    "folder": str(config.DDIR.get), "audio": dltype.startswith("da")}
                   for song in songs]
        if not payload:
            finish("No hay elementos para encolar.")
            return False
        try:
            batch = enqueue(str(app.getFilesDir().getAbsolutePath()), json.dumps(payload))
            jclass("iecxiv.yewtube.DownloadQueue").schedule(app)
        except Exception as error:
            finish("No se pudo iniciar la cola: %s. Consulta downloads status y usa downloads resume." % error)
            return False
        finish("Encoladas %d descargas (lote %s); todavia no son archivos guardados. "
               "Usa downloads status, cancel, resume o retry." % (len(payload), batch[:8]))
        return True

    original_single = downloads.download
    original_many = downloads.down_many

    def single(dltype, num):
        if g.browse_mode == "ytpl":
            if dltype not in ("da", "dv"):
                finish("Usa da o dv para descargar una playlist.")
                return False
            index = int(num) - 1
            if index < 0 or index >= len(g.ytpls):
                finish("Numero de playlist fuera de rango.")
                return False
            return downloads.down_plist(dltype, g.ytpls[index]["link"])
        if g.browse_mode != "normal":
            finish("La descarga debe referirse a un video de la lista actual.")
            return False
        index = int(num) - 1
        if index < 0 or index >= len(g.model):
            finish("Numero de video fuera de rango.")
            return False
        return run_items(dltype, [g.model[index]])

    def many(dltype, choice, subdir=None):
        if g.browse_mode != "normal":
            finish("Abre primero los videos de la playlist para descargar varios elementos.")
            return False
        numbers = list(dict.fromkeys(util.parse_multi(choice)))
        if not numbers or any(number < 1 or number > len(g.model) for number in numbers):
            finish("Seleccion de descarga fuera de rango; no se descargo ningun elemento.")
            return False
        return run_items(dltype, [g.model[number - 1] for number in numbers])

    def copy(text):
        from java import jclass
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        jclass("iecxiv.yewtube.AndroidClipboard").copy(app, str(text))

    misc.pyperclip = types.SimpleNamespace(copy=copy)
    misc.has_pyperclip = True
    replacements = {original_single: single, original_many: many}
    g.commands[:] = [entry._replace(function=replacements.get(entry.function, entry.function))
                     for entry in g.commands]
    downloads.download = single
    downloads.down_many = many
    from android_links import install as install_links
    install_links()
    from android_playback import install as install_playback
    install_playback()
    from android_streams import install as install_streams
    install_streams()
    from android_download_queue import install_commands as install_queue_commands
    install_queue_commands()
    downloads._android_commands_installed = True
