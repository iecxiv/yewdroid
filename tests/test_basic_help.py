import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("android_features", ROOT / "app/src/main/python/android_features.py")
features = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(features)

BASIC = """        x <number> - copy item <number> url to clipboard. (See the note below)
        Note: This feature requires `pyperclip` which is installed automatically when you install yewtube but
        Linux users further need to install `xsel` or `xclip` manually using apt, dnf, pacman, zypper or whatever package manager you use.
        Visit https://pyperclip.readthedocs.io/en/latest/index.html#not-implemented-error for more info.

        q, quit - exit yewtube
"""


class BasicHelpTests(unittest.TestCase):
    def test_removes_note_and_keeps_commands(self):
        result = features._clean_basic_help(BASIC)
        self.assertIn("copy item <number> url to clipboard.", result)
        self.assertIn("q, quit - exit yewtube", result)
        for text in ("See the note below", "pyperclip", "xsel", "xclip", "Visit https://"):
            self.assertNotIn(text, result)

    def test_other_notes_unchanged(self):
        text = "Note: Keep this unrelated note.\nx <number> - copy URL."
        self.assertEqual(features._clean_basic_help(text), text)

    def test_idempotent(self):
        clean = features._clean_basic_help
        self.assertEqual(clean(clean(BASIC)), clean(BASIC))


if __name__ == "__main__":
    unittest.main()
