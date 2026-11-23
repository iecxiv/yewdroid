"""Android command adapters: one download backend and native clipboard."""
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
        saved, failed = [], []
        interrupted = False
        total = len(songs)
        for position, song in enumerate(songs, 1):
            print("\n[cola %d/%d] %s" % (position, total, song.title))
            try:
                result = pafy.download_video(song.ytid, config.DDIR.get, audio_only=dltype.startswith("da"))
                if not result:
                    raise RuntimeError("La descarga no confirmo que el archivo se guardara")
                destination = result if isinstance(result, str) else "carpeta local: %s" % config.DDIR.get
                saved.append("%s -> %s" % (song.title, destination))
            except KeyboardInterrupt:
                interrupted = True
                break
            except Exception as error:
                failed.append("%s: %s" % (song.title, error))
        remaining = total - len(saved) - len(failed)
        message = "Guardados %d/%d; fallos %d; pendientes %d." % (len(saved), total, len(failed), remaining)
        if interrupted:
            message += " Cola interrumpida."
        if saved:
            message += "\n" + "\n".join(saved)
        if failed:
            message += "\nErrores:\n" + "\n".join(failed)
        finish(message)
        return not failed and not interrupted

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
        # All files use the public destination returned by the Android backend.
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
    downloads._android_commands_installed = True
