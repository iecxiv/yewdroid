"""Resize and repaginate on the input thread, never by submitting commands."""
from collections import deque
from functools import wraps
import sys
import threading
import traceback


def _replace_aliases(original, replacement):
    for name, module in tuple(sys.modules.items()):
        if name.startswith("mps_youtube.") and module is not None:
            for attribute, value in tuple(vars(module).items()):
                if value is original:
                    setattr(module, attribute, replacement)


def install(term):
    if getattr(term, "_layout_installed", False):
        return
    term._layout_installed = True
    token = object()
    lock = threading.Lock()
    pending = [None, False]
    renders = deque(maxlen=32)
    last = [None]
    page_state = [None]
    redraw = [lambda: None]
    original_patch = term._patch_traceback

    def install_screen():
        original_patch()
        from mps_youtube import content, g, screen, util
        from mps_youtube.commands import songlist
        original_update = screen.update
        original_paginate = songlist.paginatesongs

        @wraps(original_paginate)
        def paginate(func, page=0, splash=True, dumps=False, length=None,
                     msg=None, failmsg=None, loadmsg=None):
            result = original_paginate(func, page=page, splash=splash, dumps=dumps,
                length=length, msg=msg, failmsg=failmsg, loadmsg=loadmsg)
            page_state[0] = None if dumps else {
                "source": func, "page": g.current_page,
                "size": max(1, util.getxy().max_results),
                "length": g.result_count, "query": g.last_search_query,
                "items": tuple(id(song) for song in g.model),
            }
            return result

        _replace_aliases(original_paginate, paginate)

        def repaginate():
            state = page_state[0]
            if not state or g.browse_mode != "normal":
                return
            if (g.last_search_query is not state["query"]
                    or g.current_page != state["page"]
                    or tuple(id(song) for song in g.model) != state["items"]):
                # Do not undo edits, shuffles or a switch to another result source.
                return
            size = max(1, util.getxy().max_results)
            if size == state["size"] or state["length"] <= 0:
                return
            anchor = state["page"] * state["size"]
            page = min(anchor // size, (state["length"] - 1) // size)
            start, end = page * size, (page + 1) * size
            source = state["source"]
            songs = list(source(start, end) if callable(source) else source[start:end])
            g.model.songs = songs
            g.current_page = page
            state.update(page=page, size=size, items=tuple(id(song) for song in songs))

        def remember_renderer(fn):
            @wraps(fn)
            def generate(*args, **kwargs):
                g.detectable_size = True
                result = fn(*args, **kwargs)
                renders.append((result, fn, args, kwargs))
                return result
            return generate

        for name in ("logo", "generate_songlist_display", "generate_playlist_display", "playlists_display"):
            fn = getattr(content, name, None)
            if callable(fn):
                _replace_aliases(fn, remember_renderer(fn))

        @wraps(original_update)
        def update(*args, **kwargs):
            g.detectable_size = True
            value = g.content
            renderer = next((entry[1:] for entry in reversed(renders) if entry[0] is value), None)
            last[0] = (value, renderer, g.message, g.rprompt)
            return original_update(*args, **kwargs)

        def repaint():
            if last[0] is None:
                return
            value, renderer, message, prompt = last[0]
            saved = (g.content, g.message, g.rprompt)
            try:
                g.detectable_size = True
                g.message, g.rprompt = message, prompt
                if renderer is not None:
                    fn, args, kwargs = renderer
                    if fn.__name__ == "generate_songlist_display":
                        repaginate()
                    value = fn(*args, **kwargs)
                g.content = value
                # Keep the newly calculated page indicator on subsequent resizes.
                last[0] = (value, renderer, g.message, g.rprompt)
                original_update()
            finally:
                g.content, g.message, g.rprompt = saved

        screen.update = update
        redraw[0] = repaint

    def resize(cols, rows):
        size = (max(1, int(cols)), max(1, int(rows)))
        with lock:
            pending[0] = size
            if term._in is None:
                term._size[:] = size
                return
            if not pending[1]:
                pending[1] = True
                term._in.q.put(token)

    def readline(self, *args):
        while True:
            item = self.q.get()
            if item is token:
                with lock:
                    size, pending[1] = pending[0], False
                if size != tuple(term._size):
                    term._size[:] = size
                    try:
                        redraw[0]()
                    except Exception:
                        traceback.print_exc(file=term._orig_err)
                continue
            if item is term._INTR:
                raise KeyboardInterrupt
            return item + "\n"

    term._patch_traceback = install_screen
    term._In.readline = readline
    term.resize = resize
