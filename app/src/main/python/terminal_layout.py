"""Resize/redraw on yewtube's input thread, never by submitting commands."""
from collections import deque
from functools import wraps
import threading
import traceback


def install(term):
    if getattr(term, "_layout_installed", False):
        return
    term._layout_installed = True
    token = object()
    lock = threading.Lock()
    pending = [None, False]
    renders = deque(maxlen=32)
    last = [None]
    redraw = [lambda: None]
    original_patch = term._patch_traceback

    def install_screen():
        original_patch()
        from mps_youtube import content, g, screen
        original_update = screen.update

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
                setattr(content, name, remember_renderer(fn))

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
                if renderer is not None:
                    fn, args, kwargs = renderer
                    value = fn(*args, **kwargs)
                g.content = value
                g.message, g.rprompt = message, prompt
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
