"""Resize the visible result window without changing logical page boundaries."""
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
    redraw_token = object()
    lock = threading.Lock()
    pending = [None, False]
    renders = deque(maxlen=32)
    last = [None]
    page_state = [None]
    rendering = [False]
    forced_stride = [None]
    redraw = [lambda: None]
    original_patch = term._patch_traceback
    screen_installed = [False]

    def idle():
        """Tell the sink that yewtube is waiting for a command (the native client refreshes then)."""
        notify = getattr(term._sink, "idle", None)
        if notify is None:
            return
        try:
            notify()
        except Exception:
            traceback.print_exc(file=term._orig_err)

    def install_screen():
        original_patch()
        if screen_installed[0]:
            return
        screen_installed[0] = True
        from mps_youtube import content, g, screen, util
        from mps_youtube.commands import songlist
        original_update = screen.update
        original_paginate = songlist.paginatesongs
        real_getxy = util.getxy
        original_page_msg = content.page_msg

        @wraps(real_getxy)
        def getxy():
            xy = real_getxy()
            if rendering[0] or isinstance(g.content, content.PaginatedContent):
                return xy
            stride = forced_stride[0]
            state = page_state[0]
            if (stride is None and state and g.browse_mode == "normal"
                    and g.last_search_query is state["query"]):
                stride = state["stride"]
            return xy if stride is None else xy._replace(max_results=stride)

        _replace_aliases(real_getxy, getxy)

        @wraps(original_page_msg)
        def page_msg(*args, **kwargs):
            previous = rendering[0]
            rendering[0] = False
            try:
                return original_page_msg(*args, **kwargs)
            finally:
                rendering[0] = previous

        _replace_aliases(original_page_msg, page_msg)

        def resize_results():
            state = page_state[0]
            if not state or g.browse_mode != "normal":
                return False
            if (g.last_search_query is not state["query"]
                    or g.current_page != state["page"]
                    or tuple(id(song) for song in g.model) != state["items"]):
                return False
            visible = max(1, real_getxy().max_results)
            if visible == state["visible"] or state["length"] <= 0:
                return False
            start = state["page"] * state["stride"]
            source = state["source"]
            songs = list(source(start, start + visible) if callable(source)
                         else source[start:start + visible])
            g.model.songs = songs
            state.update(visible=visible, items=tuple(id(song) for song in songs))
            return True

        @wraps(original_paginate)
        def paginate(func, page=0, splash=True, dumps=False, length=None,
                     msg=None, failmsg=None, loadmsg=None):
            previous = page_state[0]
            same_source = (previous and previous["source"] is func
                           and g.last_search_query is previous["query"])
            stride = previous["stride"] if same_source else max(1, real_getxy().max_results)
            old_forced = forced_stride[0]
            forced_stride[0] = None if dumps else stride
            if dumps:
                page_state[0] = None
            try:
                result = original_paginate(func, page=page, splash=splash, dumps=dumps,
                    length=length, msg=msg, failmsg=failmsg, loadmsg=loadmsg)
            finally:
                forced_stride[0] = old_forced
            if not dumps:
                page_state[0] = {
                    "source": func, "page": g.current_page, "stride": stride,
                    "visible": stride, "length": g.result_count,
                    "query": g.last_search_query,
                    "items": tuple(id(song) for song in g.model),
                }
                if resize_results():
                    g.content = content.generate_songlist_display()
            return result

        _replace_aliases(original_paginate, paginate)

        def remember_renderer(fn):
            @wraps(fn)
            def generate(*args, **kwargs):
                g.detectable_size = True
                previous = rendering[0]
                rendering[0] = True
                try:
                    result = fn(*args, **kwargs)
                finally:
                    rendering[0] = previous
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
                        resize_results()
                    # Go through the wrapper so only drawing uses the visible row count.
                    value = remember_renderer(fn)(*args, **kwargs)
                g.content = value
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

    def request_redraw():
        if term._in is not None:
            term._in.q.put(redraw_token)

    def readline(self, *args):
        idle()
        while True:
            item = self.q.get()
            if item is redraw_token:
                try:
                    redraw[0]()
                except Exception:
                    traceback.print_exc(file=term._orig_err)
                idle()
                continue
            if item is token:
                with lock:
                    size, pending[1] = pending[0], False
                if size != tuple(term._size):
                    term._size[:] = size
                    try:
                        redraw[0]()
                    except Exception:
                        traceback.print_exc(file=term._orig_err)
                idle()
                continue
            if item is term._INTR:
                raise KeyboardInterrupt
            return item + "\n"

    term._patch_traceback = install_screen
    term._In.readline = readline
    term.resize = resize
    term.request_redraw = request_redraw
    term.last_message = lambda: (last[0] or (None, None, "", None))[2]
