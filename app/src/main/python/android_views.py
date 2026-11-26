"""Keeps the search results aside while another view (history, a playlist) replaces them in yewtube.

yewtube holds a single result list. The native client stashes the search results before opening the
history or a playlist and restores them when the user returns to Search, without a new search.
"""
import re

_KEYS = ("current_page", "result_count", "browse_mode", "last_search_query", "last_opened", "ytpls",
         "selected_pafy_pls_id")
_kept = {}


def has_stash():
    """True when search results are being kept (the client calls this directly)."""
    return "search" in _kept


def stash():
    """viewstash: remembers the results currently shown as the search results."""
    from mps_youtube import g
    state = {key: getattr(g, key, None) for key in _KEYS}
    state["songs"] = list(g.model.songs)
    state["message"] = g.message
    _kept["search"] = state


def restore():
    """viewrestore: shows the remembered search results again (an empty list when there are none)."""
    from mps_youtube import content, g
    state = _kept.get("search")
    if state is None:
        g.model.songs = []
        g.current_page = 0
        g.result_count = 0
        g.browse_mode = "normal"
        g.last_opened = ""
        g.message = ""
    else:
        for key in _KEYS:
            setattr(g, key, state[key])
        g.model.songs = list(state["songs"])
        g.message = state["message"]
    g.content = content.generate_songlist_display()


def install():
    from mps_youtube import g
    from mps_youtube.commands import Command
    if getattr(g, "_android_views_installed", False):
        return
    flags = re.IGNORECASE
    g.commands.insert(0, Command(re.compile(r"viewstash\s*$", flags), None, None, stash))
    g.commands.insert(0, Command(re.compile(r"viewrestore\s*$", flags), None, None, restore))
    g._android_views_installed = True
