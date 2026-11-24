import collections
import importlib.util
import pathlib
import re
import sys
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("android_features", ROOT / "app/src/main/python/android_features.py")
features = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(features)


class FeatureTests(unittest.TestCase):
    def setUp(self):
        self.pkg = types.ModuleType("mps_youtube")
        self.cfg = types.ModuleType("mps_youtube.config")
        for key in ("DOWNLOAD_COMMAND", "ENCODER", "LOOKUP_METADATA", "CHECKUPDATE"):
            setattr(self.cfg, key, types.SimpleNamespace(value="old", temp_value="old"))
        def load():
            self.cfg.ENCODER.value = 9
            return "loaded"
        self.cfg.load = load
        self.help = types.ModuleType("mps_youtube.helptext")
        self.help.helptext = lambda: [(name, name, "\x1b[33mset encoder 1\x1b[0m\nkeep")
                                     for name in ["basic", "search", *features.REMOVED]]
        self.help.get_help = lambda choice: self.help.helptext()
        self.commands = types.ModuleType("mps_youtube.commands")
        self.commands.config = types.ModuleType("mps_youtube.commands.config")
        self.commands.lastfm = types.ModuleType("mps_youtube.commands.lastfm")
        self.commands.Command = collections.namedtuple("Command", "regex category usage function")
        def entry(name):
            def action(): return name
            action.__module__ = "mps_youtube.commands." + name
            return self.commands.Command(re.compile(name), None, None, action)
        self.g = types.SimpleNamespace(commands=[entry(name) for name in ("config", "lastfm", "download", "search", "misc")])
        self.pkg.config, self.pkg.helptext, self.pkg.g = self.cfg, self.help, self.g
        self.content = types.ModuleType("mps_youtube.content")
        self.content.generate_songlist_display = lambda: "list"
        self.pkg.content = self.content
        self.misc = types.ModuleType("mps_youtube.commands.misc")
        self.misc.get_help = self.help.get_help
        self.init = types.ModuleType("mps_youtube.init")
        self.init.helptext = self.help.helptext
        modules = {item.__name__: item for item in (self.pkg, self.cfg, self.help, self.commands,
                   self.commands.config, self.commands.lastfm, self.content, self.misc, self.init)}
        self.patcher = patch.dict(sys.modules, modules)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        features.install()

    def test_topics_and_imported_aliases(self):
        self.assertEqual([row[0] for row in self.init.helptext()], ["basic", "search"])
        self.assertTrue(all(row[2] == "keep" for row in self.help.helptext()))
        self.assertIn("no esta disponible", self.misc.get_help("tor"))
        self.assertIn("no esta disponible", self.misc.get_help("download_command"))
        self.assertEqual(len(self.misc.get_help("search")), 2)

    def test_blocked_commands_and_allowed_handlers(self):
        pattern = self.g.commands[0].regex
        for text in [*features.REMOVED, "set", "set encoder 2", "showconfig", "encoders", "encoder", "lastfm_connect"]:
            self.assertIsNotNone(pattern.fullmatch(text), text)
        for text in ("/tor", "da 1,3", "dv 1", "history", "save tor", "android help"):
            self.assertIsNone(pattern.fullmatch(text), text)
        self.assertEqual([entry.function() for entry in self.g.commands[1:]], ["download", "search", "misc"])
        self.g.commands[0].function()
        self.assertEqual(self.g.content, "list")

    def test_legacy_settings_stay_disabled_after_load(self):
        self.assertEqual(self.cfg.ENCODER.value, 0)
        self.assertEqual(self.cfg.load(), "loaded")
        self.assertEqual(self.cfg.ENCODER.value, 0)
        self.assertEqual(self.cfg.DOWNLOAD_COMMAND.value, "")
        self.assertFalse(self.cfg.LOOKUP_METADATA.value)

    def test_lastfm_disabled(self):
        self.assertFalse(self.commands.lastfm.has_pylast)
        self.assertIsNone(self.commands.lastfm.init_network())
        self.assertIsNone(self.commands.lastfm.scrobble_track("a", "b", "c"))
        self.assertIsNone(self.g.lastfm_network)

    def test_install_is_idempotent(self):
        before = list(self.g.commands)
        features.install()
        self.assertEqual(self.g.commands, before)


if __name__ == "__main__":
    unittest.main()
