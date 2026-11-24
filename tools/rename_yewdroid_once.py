"""One-time checked YewDroid migration, executed by the accompanying workflow."""
import ast
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

OLD = "iecxiv.yewtube"
NEW = "iecxiv.yewdroid"
OLD_PATH = "iecxiv/yewtube/"
NEW_PATH = "iecxiv/yewdroid/"
URL = "https://github.com/iecxiv/yewtube-android"
CREDIT = "Modified version of yewtube, adapted and simplified for native Android use."
TEXT = {".kt", ".java", ".py", ".xml", ".kts", ".gradle", ".pro", ".md", ".properties", ".txt", ".json", ".toml", ".sh", ".yml", ".yaml"}


def plan(root):
    tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).decode().split("\0")
    output = {}
    removed = []
    for name in filter(None, tracked):
        path = root / name
        if path.resolve() == Path(__file__).resolve():
            continue
        if not (name.startswith(("app/", "tests/", "tools/")) or name in ("README.md", "settings.gradle.kts", "build.gradle.kts", "gradle.properties")):
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Unsafe path: " + name)
        target = name.replace(OLD_PATH, NEW_PATH)
        data = path.read_bytes()
        if path.suffix in TEXT:
            text = data.decode("utf-8").replace(OLD, NEW).replace(OLD_PATH, NEW_PATH)
            if name.startswith(("app/", "tests/")):
                text = text.replace("YewTube", "YewDroid")
            if name.endswith("/MainActivity.kt"):
                text = text.replace('Text("yewtube",', 'Text("YewDroid",')
                if 'Text("YewDroid",' not in text:
                    raise ValueError("Unexpected activity title")
            if name == "settings.gradle.kts":
                text, count = re.subn(r'rootProject\.name\s*=\s*"(?:YewTubeAndroid|YewDroid)"', 'rootProject.name = "YewDroid"', text)
                if count != 1:
                    raise ValueError("Unexpected project name")
            if name.endswith(".py"):
                text = text.replace(".yewtube-audio-", ".yewdroid-audio-").replace(".yewtube-video-", ".yewdroid-video-")
            if name.endswith("/yt_term.py") and CREDIT not in text:
                anchor = "            except (SystemExit, KeyboardInterrupt):\n                pass\n"
                if text.count(anchor) != 1:
                    raise ValueError("Exit handler changed; refusing partial refactor")
                replacement = (
                    "            except (SystemExit, KeyboardInterrupt):\n"
                    "                sys.stdout.write(\n"
                    '                    "\\nYewDroid\\n"\n'
                    '                    "' + CREDIT + '\\n"\n'
                    '                    "Based on yewtube %s and mps-youtube.\\n"\n'
                    '                    "Project: ' + URL + '\\n"\n'
                    '                    % getattr(ymain, "__version__", "unknown"))\n'
                )
                text = text.replace(anchor, replacement)
            if name.endswith("/yt_term.py"):
                text = text.replace("Pulsa ENTER para reiniciar yewtube", "Pulsa ENTER para reiniciar YewDroid")
            if name == "README.md":
                text = re.sub(r"\A# [^\n]+", "# YewDroid", text, count=1)
                if CREDIT not in text:
                    text += "\n\n## Attribution\n\n" + CREDIT + "\nBased on yewtube and mps-youtube; original credits and licensing are retained.\n\nProject: " + URL + "\n"
            if path.suffix == ".py":
                ast.parse(text, filename=target)
            if path.suffix == ".xml":
                ET.fromstring(text)
            data = text.encode("utf-8")
        if target in output or (target != name and (root / target).exists()):
            raise ValueError("Destination collision: " + target)
        output[target] = (data, path.stat().st_mode & 0o777)
        if target != name:
            removed.append(name)
    gradle = output["app/build.gradle.kts"][0].decode()
    for key in ("namespace", "applicationId"):
        if not re.search(r'\b' + key + r'\s*=\s*"' + re.escape(NEW) + r'"', gradle):
            raise ValueError("Incorrect " + key)
    manifest = ET.fromstring(output["app/src/main/AndroidManifest.xml"][0])
    if manifest.find("application").get("{http://schemas.android.com/apk/res/android}label") != "YewDroid":
        raise ValueError("Incorrect application label")
    native = [name for name in output if name.startswith("app/src/main/java/iecxiv/yewdroid/") and name.endswith(".kt")]
    if not native or any("package " + NEW not in output[name][0].decode() for name in native):
        raise ValueError("Incomplete Kotlin migration")
    for name, (data, _) in output.items():
        if name.startswith("app/") and Path(name).suffix in TEXT and OLD.encode() in data:
            raise ValueError("Old package reference: " + name)
    return output, removed


def main():
    root = Path.cwd().resolve()
    output, removed = plan(root)
    for name, (data, mode) in output.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(mode)
    for name in removed:
        (root / name).unlink()
    for directory in (root / "app/src").glob("*/java/iecxiv/yewtube"):
        for path in sorted((p for p in directory.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
            if not any(path.iterdir()): path.rmdir()
        directory.rmdir()
    print("Migrated %d source paths; all source checks passed." % len(removed))


if __name__ == "__main__":
    main()
