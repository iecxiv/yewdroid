"""Commands the native client uses to edit saved playlists: add a result and remove an entry.

Both work on the saved playlist by name and save it, so changes survive closing the app.
"""
import math
import re


def _show(saved, page, message):
    """Reloads the opened playlist from [saved] on [page] (or the last page that still exists)."""
    from mps_youtube import g, util
    from mps_youtube.commands.songlist import paginatesongs
    size = max(1, int(util.getxy().max_results))
    g.active.songs = list(saved.songs)
    last_page = max(0, math.ceil(len(saved.songs) / size) - 1)
    paginatesongs(g.active, page=min(page, last_page), splash=False, msg=message)


def add_entry(name, number):
    """pladd NAME N: appends result N of the current list to the saved playlist (created if new)."""
    from mps_youtube import content, g, playlists, util
    from mps_youtube.playlist import Playlist
    index = int(number) - 1
    if not 0 <= index < len(g.model):
        g.message = "No such result: %s" % number
        g.content = content.generate_songlist_display()
        return
    saved = g.userpl.get(name)
    if saved is None:
        saved = Playlist(name)
        g.userpl[name] = saved
    saved.songs.append(g.model[index])
    playlists.save()
    g.message = util.F("added to saved pl") % (1, name, len(saved), saved.duration)
    g.content = content.generate_songlist_display()


def remove_entry(name, number):
    """plrm NAME N: removes entry N of the page being shown from the saved playlist and reloads the page."""
    from mps_youtube import content, g, playlists, util
    saved = g.userpl.get(name)
    size = max(1, int(util.getxy().max_results))
    page = int(g.current_page or 0)
    index = page * size + int(number) - 1
    if saved is None or not 0 <= index < len(saved.songs):
        g.message = "Playlist entry not found"
        g.content = content.generate_songlist_display()
        return
    del saved.songs[index]
    playlists.save()
    _show(saved, page, util.F("songs rm") % (1, "(%s)" % number))


def reload_view(name):
    """plreload NAME: shows the saved playlist again on the current page, keeping the last message."""
    from mps_youtube import g
    saved = g.userpl.get(name)
    if saved is None:
        return
    _show(saved, int(g.current_page or 0), g.message)


def install():
    from mps_youtube import g
    from mps_youtube.commands import Command
    if getattr(g, "_android_playlist_edit_installed", False):
        return
    flags = re.IGNORECASE
    g.commands.insert(0, Command(re.compile(r"pladd\s+([-\w]+)\s+(\d+)\s*$", flags), None, None, add_entry))
    g.commands.insert(0, Command(re.compile(r"plrm\s+([-\w]+)\s+(\d+)\s*$", flags), None, None, remove_entry))
    g.commands.insert(0, Command(re.compile(r"plreload\s+([-\w]+)\s*$", flags), None, None, reload_view))
    g._android_playlist_edit_installed = True
