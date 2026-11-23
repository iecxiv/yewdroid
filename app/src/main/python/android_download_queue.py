"""Persistent download queue, independent from terminal and playback state."""
import contextlib
import json
import os
import re
import sqlite3
import threading
import time
import uuid

_runner = threading.Lock()
_runtime_lock = threading.Lock()


class Cancelled(BaseException):
    pass


@contextlib.contextmanager
def database(root):
    os.makedirs(root, exist_ok=True)
    db = sqlite3.connect(os.path.join(root, "download-queue.sqlite3"), timeout=30)
    db.row_factory = sqlite3.Row
    try:
        db.execute("""CREATE TABLE IF NOT EXISTS downloads (
            id INTEGER PRIMARY KEY, batch TEXT NOT NULL, ytid TEXT NOT NULL,
            title TEXT NOT NULL, folder TEXT NOT NULL, audio INTEGER NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
            destination TEXT, error TEXT, created REAL NOT NULL)""")
        with db:
            yield db
    finally:
        db.close()


def enqueue(root, payload):
    items = json.loads(payload)
    if not isinstance(items, list) or not items:
        raise ValueError("La cola esta vacia")
    for item in items:
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", str(item.get("ytid", ""))):
            raise ValueError("Identificador de video no valido")
        if not os.path.isabs(item.get("folder", "")):
            raise ValueError("La carpeta debe ser absoluta")
    batch = uuid.uuid4().hex
    with database(root) as db:
        db.executemany("""INSERT INTO downloads
            (batch, ytid, title, folder, audio, created) VALUES (?, ?, ?, ?, ?, ?)""",
            [(batch, item["ytid"], str(item["title"]), item["folder"],
              int(bool(item["audio"])), time.time()) for item in items])
    return batch


def cancel(root):
    with database(root) as db:
        db.execute("UPDATE downloads SET state='cancelled' WHERE state IN ('pending','running')")


def reset(root, state):
    if state not in ("failed", "cancelled"):
        raise ValueError("Estado no recuperable")
    with database(root) as db:
        db.execute("UPDATE downloads SET state='pending', attempts=0, error=NULL WHERE state=?", (state,))


def status(root):
    with database(root) as db:
        rows = db.execute("SELECT * FROM downloads ORDER BY id DESC LIMIT 50").fetchall()
        counts = db.execute("SELECT state, count(*) AS n FROM downloads GROUP BY state").fetchall()
    labels = {"pending": "pendiente", "running": "en curso", "done": "guardado",
              "failed": "fallo", "cancelled": "cancelado"}
    summary = "; ".join("%s: %d" % (labels[row["state"]], row["n"]) for row in counts)
    lines = ["Cola persistente (historial global): " + (summary or "vacia")]
    for row in reversed(rows):
        lines.append("#%d [%s] %s" % (row["id"], labels[row["state"]], row["title"]))
        if row["destination"]:
            lines.append("  " + row["destination"])
        if row["error"]:
            lines.append("  " + row["error"])
    return "\n".join(lines)


def claim(root):
    with database(root) as db:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT * FROM downloads WHERE state='pending' ORDER BY id LIMIT 1").fetchone()
        if row:
            db.execute("UPDATE downloads SET state='running', attempts=attempts+1 WHERE id=?", (row["id"],))
            return dict(row)
    return None


def finish(root, job_id, state, destination=None, error=None):
    with database(root) as db:
        db.execute("""UPDATE downloads SET state=?, destination=?, error=?
                      WHERE id=? AND state='running'""", (state, destination, error, job_id))


def stopped(root, job_id, bridge):
    if bridge.cancelled():
        return True
    with database(root) as db:
        row = db.execute("SELECT state FROM downloads WHERE id=?", (job_id,)).fetchone()
    return not row or row["state"] == "cancelled"


def _clean(error):
    return re.sub(r"https?://\S+", "<URL>", str(error))[-4000:]


def _network_error(error):
    return any(part in str(error).lower() for part in (
        "failed to resolve", "name resolution", "network is unreachable",
        "connection reset", "timed out", "timeout", "temporary failure", "connection aborted"))


def _runtime():
    from java import jclass
    import yt_dlp
    with _runtime_lock:
        if getattr(yt_dlp.YoutubeDL, "_queue_runtime", False):
            return
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        path = os.path.join(str(app.getApplicationInfo().nativeLibraryDir), "libqjs.so")
        cache = os.path.join(str(app.getFilesDir().getAbsolutePath()), "yt-dlp-cache")
        if not os.path.isfile(path):
            raise RuntimeError("Falta libqjs.so en la APK; recompila con QuickJS")
        original = yt_dlp.YoutubeDL

        class QueueYDL(original):
            _queue_runtime = True

            def __init__(self, params=None, auto_init=True):
                options = dict(params or {})
                options.setdefault("js_runtimes", {"quickjs": {"path": path}})
                options.setdefault("socket_timeout", 25)
                options.setdefault("cachedir", cache)
                super().__init__(options, auto_init)

        yt_dlp.YoutubeDL = QueueYDL


def _download(job, workspace, progress):
    _runtime()
    from android_video_download import _publish, download_video
    if job["audio"]:
        from android_audio_download import download_audio
        return download_audio(job["ytid"], job["folder"], _publish,
                              progress_callback=progress, work_dir=workspace)
    return download_video(job["ytid"], job["folder"], progress, work_dir=workspace)


def run(root, bridge):
    if not _runner.acquire(blocking=False):
        return "retry"
    try:
        with database(root) as db:
            db.execute("UPDATE downloads SET state='pending' WHERE state='running'")
        while not bridge.cancelled():
            job = claim(root)
            if not job:
                return "done"
            workspace = os.path.join(root, "download-work", str(job["id"]))
            last_check = [0.0]

            def progress(data):
                if bridge.cancelled():
                    raise Cancelled("Descarga interrumpida; parciales conservados")
                now = time.monotonic()
                if now - last_check[0] >= 0.5 or data.get("status") in ("finished", "exporting"):
                    last_check[0] = now
                    if stopped(root, job["id"], bridge):
                        raise Cancelled("Cola cancelada; parciales conservados")
                total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
                percent = min(100, int(100 * data.get("downloaded_bytes", 0) / total)) if total else -1
                bridge.update(job["title"], percent)

            try:
                progress({})
                destination = _download(job, workspace, progress)
                if not isinstance(destination, str) or not destination:
                    raise RuntimeError("La exportacion no confirmo el destino")
            except Cancelled:
                finish(root, job["id"], "pending")
                if bridge.cancelled():
                    return "stopped"
            except Exception as error:
                retry = _network_error(error) and job["attempts"] < 2
                finish(root, job["id"], "pending" if retry else "failed", error=_clean(error))
                if retry:
                    return "retry"
            else:
                finish(root, job["id"], "done", destination=destination)
        return "stopped"
    finally:
        _runner.release()


def install_commands():
    from mps_youtube import content, g
    from mps_youtube.commands import command
    if getattr(g, "_download_queue_commands", False):
        return

    @command(r"downloads(?:\s+(status|cancel|resume|retry))?", "downloads")
    def commands(action=None):
        from java import jclass
        app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        root = str(app.getFilesDir().getAbsolutePath())
        native = jclass("iecxiv.yewtube.DownloadQueue")
        try:
            if action == "cancel":
                native.cancel(app)
            elif action in ("resume", "retry"):
                reset(root, "cancelled" if action == "resume" else "failed")
                native.schedule(app)
            g.content = status(root)
            g.message = "downloads status | cancel | resume | retry"
        except Exception as error:
            g.content = content.generate_songlist_display()
            g.message = "Cola: %s" % error

    g._download_queue_commands = True
