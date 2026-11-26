"""Android command adapters: one download backend and native clipboard."""
import types


def install(pafy):
    from mps_youtube import c, config, content, g, util
    from mps_youtube.commands import download as downloads, misc
    from android_download_choice import download as download_selected, DownloadCancelled

    if getattr(downloads, "_android_commands_installed", False):
        return

    def finish(message):
        g.content = content.generate_songlist_display()
        g.message = message

    def warn(message):
        finish(c.y + message + c.w)

    def track(song, dltype):
        """Lists a terminal download in the Downloads tab (it has no progress or cancel there)."""
        try:
            import android_downloads
            kind = "audio" if dltype.lower().startswith("da") else "video"
            return android_downloads.external_start(song.ytid, song.title, kind, "Terminal")
        except Exception:
            return None

    def settle(job, destination="", error="", cancelled=False):
        if job is None:
            return
        try:
            import android_downloads
            android_downloads.external_finish(job, destination, error, cancelled)
        except Exception:
            pass

    def run_items(dltype, songs):
        saved, failed = [], []
        interrupted = False
        total = len(songs)
        for position, song in enumerate(songs, 1):
            print("\n%s[queue %d/%d]%s %s%s%s" % (c.y, position, total, c.w, c.r, song.title, c.w))
            job = track(song, dltype)
            try:
                result = download_selected(song.ytid, config.DDIR.get, dltype.lower())
                if not result:
                    raise RuntimeError("The download did not confirm that the file was saved")
                destination = result if isinstance(result, str) else "local folder: %s" % config.DDIR.get
                saved.append("%s -> %s%s%s" % (song.title, c.g, destination, c.w))
                settle(job, result if isinstance(result, str) else "")
            except (KeyboardInterrupt, DownloadCancelled):
                settle(job, cancelled=True)
                interrupted = True
                break
            except Exception as error:
                settle(job, error=str(error))
                failed.append("%s: %s" % (song.title, error))
        remaining = total - len(saved) - len(failed)
        color = c.g if not failed and not interrupted else c.y
        message = "%sSaved %d/%d; failed %d; pending %d.%s" % (color, len(saved), total, len(failed), remaining, c.w)
        if interrupted:
            message += " " + c.r + "Queue interrupted or cancelled." + c.w
        if saved:
            message += "\n" + "\n".join(saved)
        if failed:
            message += "\n" + c.r + "Errors:\n" + "\n".join(failed) + c.w
        finish(message)
        return not failed and not interrupted

    original_single = downloads.download
    original_many = downloads.down_many

    def single(dltype, num):
        if g.browse_mode == "ytpl":
            if dltype not in ("da", "dv"):
                warn("Use da or dv to download a playlist.")
                return False
            index = int(num) - 1
            if index < 0 or index >= len(g.ytpls):
                warn("Playlist number out of range.")
                return False
            return downloads.down_plist(dltype, g.ytpls[index]["link"])
        if g.browse_mode != "normal":
            warn("The download must refer to a video in the current list.")
            return False
        index = int(num) - 1
        if index < 0 or index >= len(g.model):
            warn("Video number out of range.")
            return False
        return run_items(dltype, [g.model[index]])

    def many(dltype, choice, subdir=None):
        if g.browse_mode != "normal":
            warn("Open the playlist videos first to download several items.")
            return False
        numbers = list(dict.fromkeys(util.parse_multi(choice)))
        if not numbers or any(number < 1 or number > len(g.model) for number in numbers):
            warn("Download selection out of range; nothing was downloaded.")
            return False
        # All files use the public destination returned by the Android backend.
        return run_items(dltype, [g.model[number - 1] for number in numbers])

    def copy(text):
        from java import jclass
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        jclass("iecxiv.yewdroid.AndroidClipboard").copy(app, str(text))

    misc.pyperclip = types.SimpleNamespace(copy=copy)
    misc.has_pyperclip = True
    replacements = {original_single: single, original_many: many}
    g.commands[:] = [entry._replace(function=replacements.get(entry.function, entry.function))
                     for entry in g.commands if entry.function is not misc.clipcopy_stream]
    downloads.download = single
    downloads.down_many = many
    from android_links import install as install_links
    install_links()
    from android_playback import install as install_playback
    install_playback()
    from android_streams import install as install_streams
    install_streams()
    from android_features import install as install_features
    install_features()
    downloads._android_commands_installed = True
