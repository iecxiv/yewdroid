import importlib.util
import io
import pathlib
import queue
import sys
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("terminal_layout", ROOT / "app/src/main/python/terminal_layout.py")
layout = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(layout)


class Model:
    def __init__(self): self.songs = []
    def __iter__(self): return iter(self.songs)


class LayoutTests(unittest.TestCase):
    def setUp(self):
        class Input:
            def __init__(self): self.q = queue.Queue()
        self.term = types.SimpleNamespace(_In=Input, _in=Input(), _INTR=object(),
            _size=[80, 10], _orig_err=io.StringIO(), _patch_traceback=lambda: None)
        self.g = types.SimpleNamespace(model=Model(), current_page=0, result_count=0,
            browse_mode="normal", detectable_size=True, last_search_query=None,
            content=False, message="", rprompt="")
        self.outputs = []
        pkg = types.ModuleType("mps_youtube")
        pkg.g = self.g
        content = types.ModuleType("mps_youtube.content")
        screen = types.ModuleType("mps_youtube.screen")
        util = types.ModuleType("mps_youtube.util")
        util.getxy = lambda: types.SimpleNamespace(max_results=max(1, min(50, self.term._size[1] - 4)))
        def generate_songlist_display():
            self.g.rprompt = (self.g.current_page, util.getxy().max_results)
            return repr(self.g.model.songs[:util.getxy().max_results])
        content.generate_songlist_display = generate_songlist_display
        def update():
            self.outputs.append((self.g.content, self.g.rprompt))
            self.g.content = self.g.message = self.g.rprompt = False
        screen.update = update
        songlist = types.ModuleType("mps_youtube.commands.songlist")
        def paginatesongs(func, page=0, splash=True, dumps=False, length=None, **kwargs):
            size = util.getxy().max_results
            start, end = (0, None) if dumps else (page * size, (page + 1) * size)
            self.g.model.songs = list(func(start, end) if callable(func) else func[start:end])
            self.g.current_page = page
            self.g.result_count = len(func) if length is None else length
            self.g.last_search_query = (songlist.paginatesongs, {"func": func})
            self.g.content = content.generate_songlist_display()
        songlist.paginatesongs = paginatesongs
        commands = types.ModuleType("mps_youtube.commands")
        commands.songlist = songlist
        alias = types.ModuleType("mps_youtube.commands.misc")
        alias.generate_songlist_display = generate_songlist_display
        pkg.content, pkg.screen, pkg.util = content, screen, util
        modules = {item.__name__: item for item in (pkg, content, screen, util, commands, songlist, alias)}
        self.patcher = patch.dict(sys.modules, modules)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.content, self.screen, self.songlist, self.alias = content, screen, songlist, alias
        layout.install(self.term)
        self.term._patch_traceback()
        self.source = [object() for _ in range(40)]

    def show(self, page=0, dumps=False):
        self.songlist.paginatesongs(self.source, page=page, dumps=dumps)
        self.screen.update()

    def drain(self):
        self.term._in.q.put("typed command")
        self.assertEqual(self.term._in.readline(), "typed command\n")

    def resize(self, rows):
        self.term.resize(80, rows)
        self.drain()

    def test_keyboard_hide_and_show(self):
        self.show()
        self.assertEqual(len(self.g.model.songs), 6)
        query = self.g.last_search_query
        self.resize(20)
        self.assertEqual(self.g.model.songs, self.source[:16])
        self.assertIs(self.g.last_search_query, query)
        self.assertEqual(self.outputs[-1][1], (0, 16))
        self.resize(10)
        self.assertEqual(self.g.model.songs, self.source[:6])
        self.assertEqual(self.outputs[-1][1], (0, 6))

    def test_page_anchor_and_remaining_results(self):
        self.show(page=2)
        self.resize(20)
        self.assertIn(self.source[12], self.g.model.songs)
        self.assertEqual(self.g.current_page, 0)
        self.assertEqual(len(self.g.model.songs), 16)

    def test_edited_results_not_restored(self):
        self.show()
        self.g.model.songs.reverse()
        expected = list(self.g.model.songs)
        self.g.content = self.content.generate_songlist_display()
        self.screen.update()
        self.resize(20)
        self.assertEqual(self.g.model.songs, expected)

    def test_help_not_replaced(self):
        self.show()
        self.g.content = "Help text"
        self.screen.update()
        self.resize(20)
        self.assertEqual(self.outputs[-1][0], "Help text")
        self.assertEqual(len(self.g.model.songs), 6)

    def test_imported_renderer_alias(self):
        self.show()
        self.g.content = self.alias.generate_songlist_display()
        self.screen.update()
        self.resize(20)
        self.assertEqual(len(self.g.model.songs), 16)

    def test_resize_coalesced_without_submitting_commands(self):
        self.show()
        self.term.resize(80, 12)
        self.term.resize(80, 24)
        self.assertEqual(self.term._in.q.qsize(), 1)
        self.drain()
        self.assertEqual(len(self.g.model.songs), 20)
        self.assertTrue(self.term._in.q.empty())

    def test_dump_keeps_full_model(self):
        self.show(dumps=True)
        self.resize(20)
        self.assertEqual(self.g.model.songs, self.source)

    def test_interrupt_preserved(self):
        self.term._in.q.put(self.term._INTR)
        with self.assertRaises(KeyboardInterrupt):
            self.term._in.readline()


if __name__ == "__main__":
    unittest.main()
