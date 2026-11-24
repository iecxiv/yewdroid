import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "iecxiv.yewdroid"
OLD_PACKAGE = "iecxiv." + "yewtube"


class YewDroidIdentityTests(unittest.TestCase):
    def test_gradle_identity(self):
        text = (ROOT / "app/build.gradle.kts").read_text()
        for key in ("namespace", "applicationId"):
            self.assertRegex(text, r'\b' + key + r'\s*=\s*"' + re.escape(PACKAGE) + '"')

    def test_manifest_name(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        self.assertEqual(manifest.find("application").get("{http://schemas.android.com/apk/res/android}label"), "YewDroid")

    def test_source_paths_and_packages(self):
        base = ROOT / "app/src/main/java/iecxiv"
        self.assertFalse((base / "yewtube").exists())
        sources = list((base / "yewdroid").rglob("*.kt"))
        self.assertTrue(sources)
        for source in sources:
            self.assertIn("package " + PACKAGE, source.read_text())

    def test_python_bridges(self):
        for source in (ROOT / "app/src/main/python").rglob("*.py"):
            self.assertNotIn(OLD_PACKAGE, source.read_text())

    def test_exit_attribution(self):
        text = (ROOT / "app/src/main/python/yt_term.py").read_text()
        self.assertIn("Modified version of yewtube, adapted and simplified for native Android use.", text)
        self.assertIn("mps-youtube", text)
        self.assertIn("https://github.com/iecxiv/yewtube-android", text)


if __name__ == "__main__":
    unittest.main()
