"""Fetch fixed source releases; verify hashes and FFmpeg's release signature."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import shlex
import shutil
import subprocess
import tarfile
import tempfile
from urllib.request import urlopen

FFMPEG_KEY = "FCF986EA15E6E293A5644F10B4322F04D67658D8"


def fetch(url, target, limit=100_000_000):
    with urlopen(url, timeout=90) as response, target.open("wb") as output:
        total = 0
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > limit:
                raise ValueError("Source download exceeds safety limit")
            output.write(chunk)


def extract(archive, destination, directory):
    with tarfile.open(archive) as handle:
        members = handle.getmembers()
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != directory:
                raise ValueError("Unexpected source archive path: " + member.name)
        handle.extractall(destination, members=members, filter="data")
    if not (destination / directory / "configure").is_file():
        raise ValueError("Source package lacks configure")


def verify_ffmpeg(archive, work):
    signature = work / "ffmpeg.asc"
    key = work / "ffmpeg-key.asc"
    fetch("https://ffmpeg.org/releases/" + archive.name + ".asc", signature, 1_000_000)
    fetch("https://ffmpeg.org/ffmpeg-devel.asc", key, 1_000_000)
    home = work / "gnupg"
    home.mkdir(mode=0o700)
    subprocess.run(["gpg", "--homedir", str(home), "--batch", "--import", str(key)], check=True, capture_output=True)
    result = subprocess.run(["gpg", "--homedir", str(home), "--batch", "--status-fd", "1", "--verify", str(signature), str(archive)], check=True, capture_output=True, text=True)
    valid = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
    if not any(FFMPEG_KEY in line[2:] for line in valid):
        raise ValueError("FFmpeg signature did not match the pinned release key")


def main():
    root = Path(__file__).resolve().parents[1]
    lock_path = root / "tools/media-sources.lock.json"
    sources = json.loads(lock_path.read_text())["sources"]
    if shutil.which("gpg") is None:
        raise RuntimeError("Install GnuPG before preparing sources")
    base = root / "build/media-sources"
    base.mkdir(parents=True, exist_ok=True)
    destination = base / "verified"
    if destination.exists():
        raise RuntimeError("Verified sources already exist; preserve them and choose a fresh workspace before rerunning")
    with tempfile.TemporaryDirectory(dir=base) as temporary:
        work = Path(temporary)
        result = work / "verified"
        result.mkdir()
        archives = result / "archives"
        archives.mkdir()
        for item in sources:
            archive = archives / item["archive"]
            fetch(item["url"], archive)
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            if digest != item["sha256"]:
                raise ValueError("Source SHA-256 mismatch: " + item["name"])
            if item["name"] == "ffmpeg":
                verify_ffmpeg(archive, work)
                shutil.copy2(work / "ffmpeg.asc", archives / (archive.name + ".asc"))
                shutil.copy2(work / "ffmpeg-key.asc", archives / "ffmpeg-key.asc")
            extract(archive, result, item["directory"])
        shutil.copy2(lock_path, result / "media-sources.lock.json")
        environment = "".join("export %s_SOURCE=%s\n" % (item["name"].upper(), shlex.quote(str(destination / item["directory"]))) for item in sources)
        (result / "sources.env").write_text(environment)
        result.rename(destination)
    print("Verified source releases prepared. No native compilation was executed.")


if __name__ == "__main__":
    main()
