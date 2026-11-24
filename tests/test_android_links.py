import collections
import importlib.util
import pathlib
import re
import sys
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("android_links", ROOT / "app/src/main/python/android_links.py")
links = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(links)


class LinkTests(unittest.TestCase):
    def setUp(self):
        links._installed = False
        self.calls = []
        self.g = types.SimpleNamespace(commands=[], browse_mode="normal",
            model=[types.SimpleNamespace(ytid="aaaaaaaaaaa")], ytpls=[{"link": "PLtest"}])
        pkg = types.ModuleType("mps_youtube")
        pkg.g = self.g
        content = types.ModuleType("mps_youtube.content")
        content.generate_songlist_display = lambda: "results"
        pkg.content = content
        commands = types.ModuleType("mps_youtube.commands")
        entry = collections.namedtuple("Command", "regex category usage function")
        def command(pattern, *names):
            def register(fn):
                self.g.commands.append(entry(re.compile(pattern), None, None, fn))
                return fn
            return register
        commands.command = command
        @command(r"open\s*(\d{1,4})")
        def old_numeric(number):
            self.calls.append(("old numeric", number))
        @command(r"open\s+([A-Za-z][-\w]*)")
        def old_named(name):
            self.calls.append(("playlist", name))
        @command(r"(?:help|h)(?:\s+([-_a-zA-Z]+))?")
        def old_help(topic):
            self.g.content = "old help"
        helptext = types.ModuleType("mps_youtube.helptext")
        helptext.helptext = lambda: [("basic", "Basic", "x <number> - copy\nX <number> - stream\nq - quit"),
                                    ("playlists", "Playlists", "open <name or ID> - local")]
        pkg.helptext = helptext
        self.helptext = helptext
        java = types.ModuleType("java")
        def perform(app, action, url):
            self.calls.append((action, url))
            return "ok"
        platform = types.SimpleNamespace(getApplication=lambda: "app")
        java.jclass = lambda name: (types.SimpleNamespace(getPlatform=lambda: platform)
            if name == "com.chaquo.python.Python" else types.SimpleNamespace(perform=perform))
        self.patcher = patch.dict(sys.modules, {"mps_youtube": pkg, "mps_youtube.content": content,
            "mps_youtube.commands": commands, "mps_youtube.helptext": helptext, "java": java})
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        links.install()

    def dispatch(self, text):
        for entry in self.g.commands:
            match = entry.regex.fullmatch(text)
            if match:
                entry.function(*match.groups())
                return True
        return False

    def test_open_overrides_numeric_playlist(self):
        self.dispatch("open 1")
        self.assertEqual(self.calls, [("open", "https://www.youtube.com/watch?v=aaaaaaaaaaa")])

    def test_share_playlist(self):
        self.g.browse_mode = "ytpl"
        self.dispatch("share 1")
        self.assertEqual(self.calls, [("share", "https://www.youtube.com/playlist?list=PLtest")])

    def test_all_help_aliases(self):
        for action in ("open", "share"):
            for prefix in ("help", "h"):
                self.dispatch(prefix + " " + action)
                self.assertEqual(self.g.content, links._format_help(links.HELP[action]))
                self.assertNotIn("android ", self.g.content)
                self.assertNotIn("x N", self.g.content)
                self.assertNotIn("X N", self.g.content)
        self.assertEqual(self.calls, [])

    def test_help_style_and_language(self):
        for action, heading in (("open", "Opening Links"), ("share", "Sharing Links")):
            self.dispatch("h " + action)
            self.assertIn(heading, self.g.content)
            self.assertNotIn("Press ENTER", self.g.content)
            self.assertTrue(all(line.startswith("      ") for line in self.g.content.splitlines() if line.strip()))
            for text in ("help open", "h open", "help share", "h share", "Ayuda:", "Pulsa"):
                self.assertNotIn(text, self.g.content)
        topics = {name: (title, body) for name, title, body in self.helptext.helptext()}
        self.assertEqual(topics["open"][0], "Opening Links")
        self.assertEqual(topics["share"][0], "Sharing Links")
        self.assertNotIn("open N", topics["basic"][1])
        self.assertNotIn("share N", topics["basic"][1])

    def test_enter_uses_default_flow(self):
        self.dispatch("h open")
        self.assertFalse(self.dispatch(""))
        self.assertEqual(self.calls, [])

    def test_named_playlists_preserved(self):
        self.dispatch("open favorites")
        self.assertEqual(self.calls, [("playlist", "favorites")])

    def test_invalid_selection_does_not_launch(self):
        for command in ("open 0", "share 2"):
            self.dispatch(command)
            self.assertIn("fuera de rango", self.g.message)
        self.g.browse_mode = "local"
        self.dispatch("open 1")
        self.assertEqual(self.calls, [])

    def test_topics_cleaned_and_added(self):
        topics = {name: body for name, title, body in self.helptext.helptext()}
        self.assertIn("open", topics)
        self.assertIn("share", topics)
        self.assertNotIn("x <number>", topics["basic"])
        self.assertNotIn("X <number>", topics["basic"])
        self.assertIn("q - quit", topics["basic"])
        self.assertNotIn("open N", topics["basic"])
        self.assertNotIn("share N", topics["basic"])
        self.assertNotIn("open <name or ID>", topics["playlists"])

    def test_no_android_commands_or_duplicate_install(self):
        for text in ("android", "android help", "android open 1", "android share 1"):
            self.assertFalse(self.dispatch(text))
        before = list(self.g.commands)
        links.install()
        self.assertEqual(self.g.commands, before)


if __name__ == "__main__":
    unittest.main()
