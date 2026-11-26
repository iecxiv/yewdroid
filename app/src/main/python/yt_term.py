"""Adapter: runs yewtube (mps_youtube) inside Android.

stdout/stdin -> Compose terminal; mpv/vlc player -> ExoPlayer (Media3).
The yewtube code is not modified: everything is patched at runtime.
"""
import json
import os
import queue
import sys
import threading
import time
import traceback
import types

# True: yewtube shows tracebacks on screen (debug mode). Set to False once everything works.
_DEBUG = True

_INTR = object()
_sink = None
_in = None
_size = [80, 30]
_orig_err = sys.stderr
# True while the native client is shown: playback then runs in its own thread so yewtube
# keeps answering commands (search, open, paging) while something plays.
_background = [False]


class _Out:
    encoding = "UTF-8"
    errors = "replace"

    def write(self, s):
        if s:
            try:
                _sink.write(str(s))
            except Exception:
                pass
        return len(s) if s else 0

    def flush(self):
        pass

    def isatty(self):
        return True

    def writable(self):
        return True


class _In:
    encoding = "UTF-8"
    errors = "replace"

    def __init__(self):
        self.q = queue.Queue()

    def readline(self, *args):
        item = self.q.get()
        if item is _INTR:
            raise KeyboardInterrupt
        return item + "\n"

    def isatty(self):
        return False

    def readable(self):
        return True


def start(sink, files_dir, cols, rows):
    global _sink, _in
    _sink = sink
    _size[0], _size[1] = int(cols), int(rows)
    _in = _In()
    out = _Out()
    sys.stdout = out
    sys.stderr = out
    sys.stdin = _in
    sys.argv = ["yt"]
    threading.Thread(target=_run, args=(files_dir,), daemon=True, name="yewtube").start()


def send(line):
    _in.q.put(str(line))


def interrupt():
    _in.q.put(_INTR)


def resize(cols, rows):
    _size[0], _size[1] = int(cols), int(rows)


def set_background(on):
    _background[0] = bool(on)


def player_event(action):
    try:
        from mps_youtube import g
        p = g.PLAYER_OBJ
        if hasattr(p, "finish"):
            p.finish(str(action))
    except Exception:
        traceback.print_exc(file=_orig_err)


def progress(sec):
    try:
        from mps_youtube import g
        p = g.PLAYER_OBJ
        if getattr(p, "_active", False):
            p.make_status_line(int(sec), p.songdata + "; ", p.song.length)
    except Exception:
        pass


def _prepare_env(files_dir):
    os.environ["HOME"] = files_dir
    os.environ["XDG_CONFIG_HOME"] = os.path.join(files_dir, "config")
    os.environ["TERM"] = "xterm"
    if _DEBUG:
        os.environ["mpsytdebug"] = "1"
    os.makedirs(os.path.join(files_dir, "config"), exist_ok=True)
    os.makedirs(os.path.join(files_dir, "Downloads"), exist_ok=True)


def _stub_pip():
    """mps_youtube/__init__.py does 'from pip._vendor import pkg_resources'
    to read its version; Android has no pip, so we simulate it."""
    import importlib.metadata as md

    pip = types.ModuleType("pip")
    pip.__path__ = []
    vendor = types.ModuleType("pip._vendor")
    vendor.__path__ = []
    pkg = types.ModuleType("pip._vendor.pkg_resources")

    class _Dist:
        pass

    ws = []
    try:
        d = _Dist()
        d.project_name = "yewtube"
        d.version = md.version("yewtube")
        ws.append(d)
    except Exception:
        pass
    pkg.working_set = ws
    vendor.pkg_resources = pkg
    pip._vendor = vendor
    sys.modules["pip"] = pip
    sys.modules["pip._vendor"] = vendor
    sys.modules["pip._vendor.pkg_resources"] = pkg


def _stub_readline():
    """main.py only creates 'completer' if 'import readline' works, and
    commands/__init__.py calls completer.add_cmd(). Android has no readline,
    so we provide an empty module (no autocompletion)."""
    try:
        import readline  # noqa: F401
        return
    except ImportError:
        pass
    rl = types.ModuleType("readline")
    noop = lambda *a, **k: None
    for name in ("set_history_length", "parse_and_bind", "set_completer",
                 "set_completer_delims", "read_history_file",
                 "write_history_file", "add_history", "clear_history",
                 "set_startup_hook", "set_pre_input_hook"):
        setattr(rl, name, noop)
    rl.get_history_length = lambda: -1
    rl.get_current_history_length = lambda: 0
    rl.get_line_buffer = lambda: ""
    sys.modules["readline"] = rl


def _patch_traceback():
    """Tracebacks also go to Logcat (tag python.stderr)."""
    orig = traceback.print_exception

    def pe(*a, **k):
        orig(*a, **k)
        if "file" not in k:
            try:
                orig(*a, file=_orig_err, **k)
            except Exception:
                pass

    traceback.print_exception = pe


def _find_qjs():
    """QuickJS binary packaged as libqjs.so in jniLibs (executable from nativeLibraryDir)."""
    app = None
    try:
        from com.chaquo.python import Python
        app = Python.getPlatform().getApplication()
    except Exception:
        pass
    if app is None:
        try:
            from java import jclass
            app = jclass("android.app.ActivityThread").currentApplication()
        except Exception:
            return None
    try:
        p = os.path.join(str(app.getApplicationInfo().nativeLibraryDir), "libqjs.so")
        return p if os.path.exists(p) else None
    except Exception:
        return None


def _patch_js_runtime():
    """Injects js_runtimes=quickjs into every yt_dlp.YoutubeDL instance."""
    import yt_dlp
    qjs = _find_qjs()
    if not qjs:
        _orig_err.write("[js] libqjs.so not found: yt-dlp has no JS runtime\n")
        return
    _orig_err.write("[js] quickjs: %s\n" % qjs)
    Orig = yt_dlp.YoutubeDL

    class YDL(Orig):
        def __init__(self, params=None, auto_init=True):
            p = dict(params or {})
            p.setdefault("js_runtimes", {"quickjs": {"path": qjs}})
            super().__init__(p, auto_init)

    yt_dlp.YoutubeDL = YDL


def _patch_downloads():
    """Audio without conversion; video through Android's native muxer.
    If audio fails, the error includes the list of formats yt-dlp sees."""
    import yt_dlp
    from mps_youtube import pafy as ypafy

    def hook(d):
        if d.get("status") == "downloading":
            tot = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if tot:
                pct = 100.0 * d.get("downloaded_bytes", 0) / tot
                sys.stdout.write("\r  Downloading %5.1f%%   " % pct)
        elif d.get("status") == "finished":
            sys.stdout.write("\r  Download complete        \n")

    def download_video(ytid, folder, audio_only=False):
        os.makedirs(folder, exist_ok=True)
        url = "https://www.youtube.com/watch?v=%s" % ytid
        chain = (["ba[ext=m4a]/ba/b", "b/w"] if audio_only
                 else ["b[ext=mp4]/b", "b/w"])
        last = None
        for fmt in chain:
            opts = {
                "outtmpl": os.path.join(folder, "%(title)s-%(id)s.%(ext)s"),
                "noplaylist": True,
                "quiet": True,
                "no_warnings": True,
                "progress_hooks": [hook],
                "format": fmt,
            }
            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    ydl.download([url])
                return True
            except Exception as e:
                last = e
        # The screen is cleared after the error, so the diagnostic goes INSIDE the exception.
        lines = []
        try:
            with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
                info = ydl.extract_info(url, download=False, process=False)
            for f in info.get("formats", []):
                lines.append("%s %s %s a=%s v=%s %s" % (
                    f.get("format_id"), f.get("ext"), f.get("protocol"),
                    f.get("acodec"), f.get("vcodec"), f.get("format_note")))
        except Exception as e2:
            lines.append("(could not list formats: %s)" % e2)
        msg = "%s\nFormats seen by yt-dlp (%d):\n%s" % (last, len(lines), "\n".join(lines))
        _orig_err.write(msg + "\n")
        raise RuntimeError(msg)

    ypafy.download_video = download_video
    from android_video_download import install
    install(ypafy)


def _write_config(g, files_dir):
    cfg = {}
    if os.path.exists(g.CFFILE):
        try:
            with open(g.CFFILE) as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}
    cfg["PLAYER"] = "androidplayer"
    cfg["MPRIS"] = False
    cfg["SET_TITLE"] = False
    cfg["CHECKUPDATE"] = False
    cfg.setdefault("SHOW_VIDEO", False)
    cfg.setdefault("LOOKUP_METADATA", False)
    cfg.setdefault("SHOW_PLAYER_KEYS", False)

    # Downloads go to the app's external folder (reachable through adb / a file explorer)
    pkg = os.path.basename(os.path.dirname(files_dir.rstrip("/")))
    ext = "/storage/emulated/0/Android/data/%s/files/Download" % pkg
    default = os.path.join(files_dir, "Downloads")
    try:
        os.makedirs(ext, exist_ok=True)
        default = ext
        if cfg.get("DDIR", "").startswith(files_dir):
            cfg["DDIR"] = ext
    except OSError:
        pass
    cfg.setdefault("DDIR", default)

    with open(g.CFFILE, "w") as f:
        json.dump(cfg, f, indent=2)


def _make_player_class(CmdPlayer):
    class androidplayer(CmdPlayer):
        def __init__(self, player):
            self.player = player
            self._evt = threading.Event()
            self._action = "next"
            self._active = False
            self._halt = False
            self._thread = None
            self.p = None

        def _generate_real_playerargs(self):
            return []

        def clean_up(self):
            pass

        def _help(self, short=True):
            return None

        def finish(self, action):
            self._action = action
            self._evt.set()

        def _stop_loop(self):
            """Ends a background playback loop; False when it did not end in time."""
            thread = self._thread
            if thread is not None and thread.is_alive():
                self._halt = True
                self._action = "stop"
                self._evt.set()
                thread.join(30)
                if thread.is_alive():
                    return False
            self._thread = None
            self._halt = False
            return True

        def play(self, *args, **kwargs):
            if not self._stop_loop():
                return None
            if not _background[0]:
                try:
                    return super().play(*args, **kwargs)
                finally:
                    self._active = False
                    try:
                        _sink.stopPlayback()
                    except Exception:
                        pass

            def loop():
                try:
                    CmdPlayer.play(self, *args, **kwargs)
                except BaseException:
                    traceback.print_exc(file=_orig_err)
                finally:
                    self._active = False
                    try:
                        _sink.stopPlayback()
                    except Exception:
                        pass

            self._thread = threading.Thread(target=loop, daemon=True, name="yewtube-player")
            self._thread.start()
            return None

        def _launch_player(self):
            if self._halt:
                self.stop()
                return
            self._evt.clear()
            self._action = "next"
            self._active = True
            song = self.song
            artist = (getattr(song, "uploader", None)
                      or getattr(song, "author", None) or "yewtube")
            thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % song.ytid
            video = isinstance(self.stream, dict) and self.stream.get("mtype") == "video"
            _sink.video(bool(video))
            _sink.mode(True)
            _sink.play(self.stream["url"], str(song.title), str(artist),
                       thumb, int(song.length or 0))
            if self._halt:
                self._action = "stop"
            else:
                self._evt.wait()
            self._active = False
            action = self._action
            if action == "prev":
                self.previous()
            elif action == "stop":
                self.stop()
            else:
                self.next()

        def next(self):
            self.song_no += 1

        def previous(self):
            self.song_no -= 1

        def stop(self):
            self.song_no = len(self.songlist)

    return androidplayer


def _run(files_dir):
    try:
        _prepare_env(files_dir)
        _stub_pip()
        _stub_readline()
        import mps_youtube.main as ymain
        from mps_youtube import g, util, screen, terminalsize
        from mps_youtube.player import CmdPlayer

        _patch_js_runtime()
        _patch_downloads()
        _patch_traceback()

        cls = _make_player_class(CmdPlayer)
        mod = types.ModuleType("mps_youtube.players.androidplayer")
        mod.androidplayer = cls
        sys.modules["mps_youtube.players.androidplayer"] = mod

        orig_has = util.has_exefile
        util.has_exefile = lambda name: name if name == "androidplayer" else orig_has(name)
        terminalsize.get_terminal_size = lambda: (_size[0], _size[1])
        screen.clear = lambda: sys.stdout.write("\x1b[2J\x1b[H")
        screen.reset_terminal = lambda: None

        while True:
            try:
                _write_config(g, files_dir)
                ymain.main()
            except (SystemExit, KeyboardInterrupt):
                sys.stdout.write(
                    "\nYewDroid\n"
                    "Modified version of yewtube, adapted and simplified for native Android use.\n"
                    "Based on yewtube %s and mps-youtube.\n"
                    "Project: https://github.com/iecxiv/yewtube-android\n"
                    % getattr(ymain, "__version__", "unknown"))
            except Exception:
                traceback.print_exc(file=_orig_err)
                traceback.print_exc(file=sys.stdout)
            sys.stdout.write("\n\x1b[33m[session ended] Press ENTER to restart YewDroid\x1b[0m\n")
            while True:
                item = _in.q.get()
                if item is not _INTR:
                    break
    except Exception:
        traceback.print_exc(file=_orig_err)
        try:
            traceback.print_exc(file=sys.stdout)
        except Exception:
            pass
