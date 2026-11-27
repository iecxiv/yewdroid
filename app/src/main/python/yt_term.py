"""Adapter: runs yewtube (mps_youtube) inside Android.

stdout/stdin -> Compose terminal; mpv/vlc player -> ExoPlayer (Media3).
The yewtube code is not modified: everything is patched at runtime.
"""
import json
import os
import queue
import random
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
# Playback always runs in its own thread, in the terminal and in the native client alike, so yewtube keeps
# answering commands (search, open, paging) while something plays and both views behave the same.
_background = [True]
# Playback modes chosen in the player: shuffle on/off and repeat 0 = off, 1 = the list, 2 = one track.
_mode = {"shuffle": False, "repeat": 0}
# Value of the "show video" setting the last time it was looked at (None before the first look).
_last_video = [None]
# Timing log: when the Python side was loaded and when the current play request was made.
_T0 = time.monotonic()
_T = {"play": None}
# Longest a preload waits for the track being started to get its stream (seconds).
_PRELOAD_WAIT = 20.0
# Channel name of each video yt-dlp has looked up (video id -> name), for tracks without search metadata.
_UPLOADERS = {}
# Artist shown when the channel of a track is unknown.
_DEFAULT_ARTIST = "YewDroid"
# What to do when a video has no stream within max_res: "up" (next higher resolution) or "audio" (audio only).
# None until read from the file.
_LOWRES = [None]
# URLs of audio streams chosen instead of a video one, so the "show video" check does not restart them.
_AUDIO_FALLBACK_URLS = set()


def _tlog(message):
    """One timing line in Logcat (tag python.stderr), to see where the seconds of a play request go."""
    try:
        _orig_err.write("[time] +%.1fs %s\n" % (time.monotonic() - _T0, message))
    except Exception:
        pass


def _clean_artist(name):
    """Channel name as an artist: YouTube's auto-generated channels end in ' - Topic'."""
    text = str(name or "").strip()
    if text.endswith(" - Topic"):
        text = text[:-len(" - Topic")].strip()
    return "" if text in ("", "?") else text


def _remember_uploader(info):
    """Keeps the channel name from the result of a yt-dlp lookup."""
    if isinstance(info, dict) and info.get("id"):
        name = _clean_artist(info.get("uploader") or info.get("channel"))
        if name:
            _UPLOADERS[str(info["id"])] = name


def _artist(song):
    """Artist of a track for the notification and the car: the channel name from the search results,
    else the one yt-dlp reported, else the app's name."""
    ytid = getattr(song, "ytid", None)
    try:
        g = _loaded("g")
        meta = g.meta.get(ytid, {}) if g is not None else {}
        name = _clean_artist(meta.get("uploaderName"))
        if name:
            return name
    except Exception:
        pass
    name = _UPLOADERS.get(str(ytid))
    if name:
        return name
    return _clean_artist(getattr(song, "uploader", None) or getattr(song, "author", None)) or _DEFAULT_ARTIST


def _lowres_file():
    return os.path.join(os.environ.get("HOME", ""), "lowres_mode.txt")


def lowres_mode():
    """What to do when a video has no stream within max_res: "up" (default) or "audio"."""
    if _LOWRES[0] is None:
        mode = "up"
        try:
            with open(_lowres_file()) as f:
                if f.read().strip() == "audio":
                    mode = "audio"
        except OSError:
            pass
        _LOWRES[0] = mode
    return _LOWRES[0]


def set_lowres_mode(mode):
    """Chooses and stores what to do when a video has no stream within max_res."""
    mode = "audio" if str(mode) == "audio" else "up"
    _LOWRES[0] = mode
    try:
        with open(_lowres_file(), "w") as f:
            f.write(mode)
    except OSError:
        pass
    return mode


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
    """Kept for the app's calls; playback is always in the background now (see _background)."""
    return None


def _loaded(name):
    """A yewtube module only if it is already loaded. Importing one before the session has started would
    fail (the stand-in for pip is installed when it starts), so the app's early calls must not import."""
    return sys.modules.get("mps_youtube." + name)


def player_event(action):
    try:
        from mps_youtube import g
        p = g.PLAYER_OBJ
        if hasattr(p, "finish"):
            p.finish(str(action))
    except Exception:
        traceback.print_exc(file=_orig_err)


def sync_video_mode():
    """True when the "show video" setting changed and the track playing has the other kind of stream.
    The app calls it every second; it also runs while nothing plays, to keep the last value current."""
    try:
        config, g = _loaded("config"), _loaded("g")
        if config is None or g is None or not hasattr(config, "SHOW_VIDEO"):
            return False
        wanted = bool(config.SHOW_VIDEO.get)
        previous = _last_video[0]
        _last_video[0] = wanted
        if previous is None or previous == wanted:
            return False
        p = getattr(g, "PLAYER_OBJ", None)
        if p is None or not getattr(p, "_active", False):
            return False
        # A choice made for this playback (audio only, video window...) beats the setting, as in yewtube.
        if getattr(p, "override", False) in ("audio", "fullscreen", "window", "forcevid"):
            return False
        stream = getattr(p, "stream", None)
        # Audio chosen because the video had nothing within max_res is not a mismatch with the setting.
        if isinstance(stream, dict) and stream.get("url") in _AUDIO_FALLBACK_URLS:
            return False
        current = isinstance(stream, dict) and stream.get("mtype") == "video"
        return wanted != current
    except Exception:
        return False


def restream():
    """Starts the current track again with the stream the "show video" setting asks for."""
    player_event("restream")


def set_play_mode(shuffle, repeat):
    """Shuffle on/off and repeat (0 off, 1 the list, 2 one track), chosen in the player."""
    _mode["shuffle"] = bool(shuffle)
    _mode["repeat"] = int(repeat)
    try:
        g = _loaded("g")
        p = getattr(g, "PLAYER_OBJ", None)
        if p is not None and getattr(p, "_active", False) and hasattr(p, "apply_shuffle"):
            p.apply_shuffle(_mode["shuffle"])
    except Exception:
        traceback.print_exc(file=_orig_err)


def _record_history(song):
    """Adds a track to the history without repeats: a track played again moves to the newest position."""
    from mps_youtube import g, history
    from mps_youtube.playlist import Playlist
    if not g.userhist.get("history"):
        g.userhist["history"] = Playlist("history")
    songs = g.userhist["history"].songs
    seen, newest_first = set(), []
    for item in reversed(list(songs) + [song]):
        if item.ytid not in seen:
            seen.add(item.ytid)
            newest_first.append(item)
    songs[:] = list(reversed(newest_first))
    history.save()


def track_started():
    """Called by the app when the current track really starts sounding: only then it goes to the history."""
    try:
        from mps_youtube import config, g
        p = g.PLAYER_OBJ
        if getattr(p, "_active", False):
            p._ok = True
            if config.HISTORY.get:
                _record_history(p.song)
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


def _patch_preload():
    """yewtube looks up the stream of other tracks in threads of its own. On a phone those lookups compete
    for the CPU with the one the user is waiting for, so:
    - the look-up of the first result of every list (the only one asked with delay 0) is dropped: it ran
      before any tap and delayed the track tapped next;
    - the look-up of the track after the one being played waits until the track being started has its
      stream (or a few seconds pass). When nothing is being started it runs at once, as before."""
    from mps_youtube import streams
    original = streams.preload

    def preload(song, delay=2, override=False):
        if delay == 0:
            return

        def later():
            waited = 0.0
            while _T["play"] is not None and waited < _PRELOAD_WAIT:
                time.sleep(0.2)
                waited += 0.2
            if waited > 0:
                _tlog("preload of %s waited %.1fs for the track being started" % (
                    getattr(song, "ytid", "?"), waited))
            try:
                original(song, delay, override)
            except Exception:
                traceback.print_exc(file=_orig_err)

        threading.Thread(target=later, daemon=True, name="yewtube-preload-wait").start()

    streams.preload = preload


def _patch_select():
    """yewtube only plays streams that carry audio and video together, and drops those taller than max_res.
    Many videos have none at or below a low max_res, and then nothing plays. When that happens, playback
    (and the preload that prepares it) follows the user's choice: the lowest resolution above the limit, or
    the audio alone. Downloads keep yewtube's own behaviour."""
    from mps_youtube import streams
    original = streams.select

    def select(slist, q=0, audio=False, m4a_ok=True, maxres=None):
        result = original(slist, q, audio, m4a_ok, maxres)
        if result is not None or audio:
            return result
        try:
            caller = sys._getframe(1).f_globals.get("__name__", "")
        except Exception:
            caller = ""
        if caller not in ("mps_youtube.player", "mps_youtube.streams"):
            return None
        items = slist["meta"] if isinstance(slist, dict) else slist
        limit = maxres
        try:
            from mps_youtube import config
            limit = limit or config.MAX_RES.get
        except Exception:
            pass
        if lowres_mode() == "audio":
            fallback = original(slist, q, True, m4a_ok, maxres)
            if fallback is not None:
                _AUDIO_FALLBACK_URLS.add(fallback["url"])
                what = "playing audio only"
        else:
            videos = []
            for item in items:
                if item.get("mtype") == "video":
                    try:
                        videos.append((int(item["quality"].split("x")[1]), item))
                    except (ValueError, IndexError, KeyError):
                        pass
            videos.sort(key=lambda pair: pair[0])
            fallback = None
            if videos:
                height, fallback = videos[min(q, len(videos) - 1)]
                what = "playing at %sp" % height
        if fallback is not None and caller == "mps_youtube.player":
            try:
                sys.stdout.write("\n\x1b[33m[max_res] No stream up to %sp for this video: %s.\x1b[0m\n" % (
                    limit, what))
                _tlog("max_res fallback: %s" % what)
            except Exception:
                pass
        return fallback

    streams.select = select


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


def _patch_subprocess_timing():
    """Logs how long each run of the QuickJS binary takes (the JavaScript challenges of YouTube)."""
    import subprocess
    original = subprocess.Popen.communicate

    def communicate(self, *args, **kwargs):
        started = time.monotonic()
        try:
            return original(self, *args, **kwargs)
        finally:
            try:
                command = self.args if isinstance(self.args, (list, tuple)) else [self.args]
                if any("qjs" in str(part) for part in command):
                    _tlog("quickjs run took %.1fs" % (time.monotonic() - started))
            except Exception:
                pass

    subprocess.Popen.communicate = communicate


def _patch_js_runtime():
    """Injects js_runtimes=quickjs and the imported YouTube cookies (files_dir/cookies.txt, when
    present) into every yt_dlp.YoutubeDL instance. Also logs the time of each extract_info call and keeps
    the channel name of every video it looks up."""
    import yt_dlp
    qjs = _find_qjs()
    if qjs:
        _orig_err.write("[js] quickjs: %s\n" % qjs)
    else:
        _orig_err.write("[js] libqjs.so not found: yt-dlp has no JS runtime\n")
    try:
        _patch_subprocess_timing()
    except Exception:
        pass
    Orig = yt_dlp.YoutubeDL

    class YDL(Orig):
        def __init__(self, params=None, auto_init=True):
            # [seconds waiting for the servers to answer, number of requests]
            self._net = [0.0, 0]
            p = dict(params or {})
            if qjs:
                p.setdefault("js_runtimes", {"quickjs": {"path": qjs}})
            cookies = os.path.join(os.environ.get("HOME", ""), "cookies.txt")
            if os.path.isfile(cookies):
                p.setdefault("cookiefile", cookies)
            super().__init__(p, auto_init)

        def urlopen(self, req):
            started = time.monotonic()
            try:
                return super().urlopen(req)
            finally:
                try:
                    self._net[0] += time.monotonic() - started
                    self._net[1] += 1
                except Exception:
                    pass

        def extract_info(self, url, *args, **kwargs):
            started = time.monotonic()
            before = list(self._net)
            info = None
            try:
                info = super().extract_info(url, *args, **kwargs)
                return info
            finally:
                try:
                    _remember_uploader(info)
                except Exception:
                    pass
                try:
                    _tlog("extract_info took %.1fs (%d requests, %.1fs until the first byte of each) %s" % (
                        time.monotonic() - started, self._net[1] - before[1],
                        self._net[0] - before[0], str(url)[-40:]))
                except Exception:
                    pass

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
            # Shuffle: the order before shuffling (None while not shuffled).
            self._orig = None
            # True for the first track of a playback run (the shuffle mode is applied then).
            self._fresh = False
            # True once a track started sounding since the list last wrapped (stops a repeat loop of failures).
            self._ok = False

        def _generate_real_playerargs(self):
            return []

        def clean_up(self):
            pass

        def _help(self, short=True):
            return None

        def finish(self, action):
            self._action = action
            self._evt.set()

        def apply_shuffle(self, on):
            """Shuffles the tracks after the current one, or restores the original order."""
            songs = list(self.songlist or [])
            index = self.song_no
            if not 0 <= index < len(songs):
                return
            if on:
                if self._orig is not None:
                    return
                self._orig = songs
                rest = songs[index + 1:]
                random.shuffle(rest)
                self.songlist = songs[:index + 1] + rest
            elif self._orig is not None:
                current = songs[index]
                original = self._orig
                self._orig = None
                self.songlist = original
                self.song_no = next((i for i, s in enumerate(original) if s.ytid == current.ytid), index)

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
            self._orig = None
            self._fresh = True
            self._ok = False
            _T["play"] = time.monotonic()
            _tlog("play requested")
            if not _background[0]:
                try:
                    return super().play(*args, **kwargs)
                finally:
                    self._active = False
                    _T["play"] = None
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
                    # A request that never got a stream must not keep the preloads waiting.
                    _T["play"] = None
                    try:
                        if self._halt:
                            # Ended only because a new play request replaces it (_stop_loop is waiting for this
                            # thread): the app must not take it for a stop, or it would drop its notice that the
                            # new request is being resolved.
                            _sink.replaced()
                        else:
                            _sink.stopPlayback()
                    except Exception:
                        pass

            self._thread = threading.Thread(target=loop, daemon=True, name="yewtube-player")
            self._thread.start()
            return None

        def _refresh_stream(self):
            """Picks the stream of the current track again, following the "show video" setting now in force."""
            try:
                from mps_youtube import player as yplayer
                details = yplayer.stream_details(self.song, override=self.override,
                                                 softrepeat=getattr(self, "softrepeat", False))
                if details:
                    self.video, self.stream, self.override = details
            except Exception:
                traceback.print_exc(file=_orig_err)

        def _launch_player(self):
            if self._halt:
                self.stop()
                return
            if self._fresh:
                self._fresh = False
                if _mode["shuffle"]:
                    self.apply_shuffle(True)
            while True:
                self._evt.clear()
                self._action = "next"
                self._active = True
                song = self.song
                artist = _artist(song)
                thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % song.ytid
                video = isinstance(self.stream, dict) and self.stream.get("mtype") == "video"
                if _T["play"] is not None:
                    _tlog("stream ready %.1fs after the request (%s)" % (
                        time.monotonic() - _T["play"], "video" if video else "audio"))
                    _T["play"] = None
                _sink.video(bool(video))
                _sink.mode(True)
                _sink.play(self.stream["url"], str(song.title), str(artist),
                           thumb, int(song.length or 0))
                if self._halt:
                    self._action = "stop"
                else:
                    self._evt.wait()
                self._active = False
                if self._action != "restream":
                    break
                # The "show video" setting changed: the same track starts again with the other kind of
                # stream (the app resumes it at the same position).
                _T["play"] = time.monotonic()
                self._refresh_stream()
            action = self._action
            _T["play"] = time.monotonic()
            if action == "prev":
                self.previous()
            elif action == "stop":
                self.stop()
            elif action == "ended" and _mode["repeat"] == 2:
                # Repeat one: the track ended by itself, so the same one plays again.
                pass
            else:
                self.next()

        def next(self):
            self.song_no += 1
            if self.song_no >= len(self.songlist) and _mode["repeat"] == 1 and self._ok:
                self.song_no = 0
                self._ok = False

        def previous(self):
            self.song_no -= 1
            if self.song_no < 0 and _mode["repeat"] == 1:
                self.song_no = len(self.songlist) - 1

        def stop(self):
            self.song_no = len(self.songlist)

    return androidplayer


class _NoHistoryAdd:
    """Stands in for the history module inside player.py: yewtube adds a track when it ends, but the
    app records it when it starts sounding (track_started), so the player's own add is ignored."""

    def __init__(self, real):
        self._real = real

    def add(self, song):
        return None

    def __getattr__(self, name):
        return getattr(self._real, name)


def _run(files_dir):
    try:
        _prepare_env(files_dir)
        _stub_pip()
        _stub_readline()
        imported = time.monotonic()
        import mps_youtube.main as ymain
        from mps_youtube import g, util, screen, terminalsize
        from mps_youtube import history as yhistory, player as yplayer
        from mps_youtube.player import CmdPlayer
        _tlog("yewtube imported in %.1fs" % (time.monotonic() - imported))

        _patch_js_runtime()
        _patch_downloads()
        _patch_traceback()
        try:
            _patch_preload()
        except Exception:
            traceback.print_exc(file=_orig_err)
        try:
            _patch_select()
        except Exception:
            traceback.print_exc(file=_orig_err)
        yplayer.history = _NoHistoryAdd(yhistory)

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
