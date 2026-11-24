import collections
import importlib.util
import io
import math
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
        content.PaginatedContent = type("PaginatedContent", (), {})
        screen = types.ModuleType("mps_youtube.screen")
        util = types.ModuleType("mps_youtube.util")
        xy = collections.namedtuple("XY", "width height max_results")
        util.getxy = lambda: xy(80, self.term._size[1], max(1, min(50, self.term._size[1] - 4)))
        def page_msg(page=0):
            size = util.getxy().max_results
            return (page, math.ceil(self.g.result_count / size))
        content.page_msg = page_msg
        def generate_songlist_display():
            self.g.rprompt = content.page_msg(self.g.current_page)
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
        self.content, self.screen, self.songlist, self.alias, self.util = content, screen, songlist, alias, util
        layout.install(self.term)
        self.term._patch_traceback()
        self.source = list(range(40))

    def show(self, page=0, dumps=False):
        self.songlist.paginatesongs(self.source, page=page, dumps=dumps)
        self.screen.update()

    def drain(self):
        self.term._in.q.put("typed command")
        self.assertEqual(self.term._in.readline(), "typed command\n")
        self.assertEqual(self.term._orig_err.getvalue(), "")

    def resize(self, rows):
        self.term.resize(80, rows)
        self.drain()

    def test_keyboard_hide_and_show(self):
        self.show()
        query = self.g.last_search_query
        self.resize(20)
        self.assertEqual(self.g.model.songs, self.source[:16])
        self.assertIs(self.g.last_search_query, query)
        self.assertEqual(self.outputs[-1][1], (0, 7))
        self.assertEqual(self.util.getxy().max_results, 6)
        self.resize(10)
        self.assertEqual(self.g.model.songs, self.source[:6])

    def test_page_two_stays_page_two_and_keeps_first_result(self):
        self.show(page=1)
        for rows, count in ((20, 16), (10, 6), (24, 20), (10, 6)):
            self.resize(rows)
            self.assertEqual(self.g.current_page, 1)
            self.assertEqual(self.g.model.songs, self.source[6:6 + count])
            self.assertEqual(self.outputs[-1][1], (1, 7))
            self.assertIn(repr(self.source[6:6 + count]), self.outputs[-1][0])

    def test_last_page_does_not_collapse(self):
        self.source = list(range(10))
        self.show(page=1)
        self.resize(40)
        self.assertEqual(self.g.current_page, 1)
        self.assertEqual(self.g.model.songs, [6, 7, 8, 9])
        self.assertEqual(self.outputs[-1][1], (1, 2))

    def test_navigation_after_resize_uses_original_boundaries(self):
        self.show(page=1)
        self.resize(20)
        self.show(page=2)
        self.assertEqual(self.g.model.songs, self.source[12:28])
        self.assertEqual(self.outputs[-1][1], (2, 7))
        self.show(page=1)
        self.assertEqual(self.g.model.songs, self.source[6:22])

    def test_new_source_gets_new_page_size(self):
        self.show()
        self.resize(20)
        self.source = list(range(100, 140))
        self.show(page=1)
        self.assertEqual(self.g.model.songs, self.source[16:32])
        self.assertEqual(self.util.getxy().max_results, 16)

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

    def test_restart_does_not_stack_size_wrappers(self):
        self.show(page=1)
        self.resize(20)
        self.term._patch_traceback()
        self.source = list(range(100, 140))
        self.show(page=1)
        self.assertEqual(self.util.getxy().max_results, 16)
        self.assertEqual(self.g.model.songs, self.source[16:32])

    def test_interrupt_preserved(self):
        self.term._in.q.put(self.term._INTR)
        with self.assertRaises(KeyboardInterrupt):
            self.term._in.readline()


if __name__ == "__main__":
    unittest.main()
