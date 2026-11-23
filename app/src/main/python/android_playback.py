"""Contain playback/preload failures without changing download backends."""
import logging
from logging.handlers import RotatingFileHandler
import os
import re
import threading
import time
import traceback

_local = threading.local()
_lock = threading.Lock()
_log_lock = threading.Lock()


def _clean(value):
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", str(value))
    return re.sub(r"https?://\S+", "<URL>", text)


def _record(stage, song, error):
    try:
        with _log_lock:
            logger = logging.getLogger("yewtube.android.playback")
            logger.propagate = False
            logger.setLevel(logging.INFO)
            if not logger.handlers:
                folder = os.path.join(os.path.expanduser("~"), "config")
                os.makedirs(folder, exist_ok=True)
                handler = RotatingFileHandler(os.path.join(folder, "playback-errors.log"),
                                              maxBytes=524288, backupCount=1, encoding="utf-8")
                handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
                logger.addHandler(handler)
            detail = "".join(traceback.format_exception(type(error), error, error.__traceback__))
            logger.error("%s [%s] %s\n%s", stage, getattr(song, "ytid", "?"),
                         _clean(getattr(song, "title", "?")), _clean(detail))
    except Exception:
        pass


class _QueueExhausted(BaseException):
    """Stop an all-failed repeating queue without masking user interrupts."""


class _FailedStream(dict):
    """Private marker consumed before calling the real player."""
    def __init__(self, error):
        super().__init__()
        self.error = error


def install():
    from mps_youtube import config, g, pafy, player, screen, streams
    if getattr(player, "_android_playback_guard", False):
        return
    original_details = player.stream_details
    original_playsong = player.BasePlayer._playsong
    original_play = player.BasePlayer.play
    original_error = pafy.MyLogger.error
    original_subtitles = pafy.get_subtitles

    def logger_error(self, message):
        if getattr(_local, "playback", False):
            return  # The enclosing guard records the complete exception once.
        return original_error(self, message)

    def optional_subtitles(*args, **kwargs):
        # Media3 currently receives no subtitle tracks from this adapter.
        # Upstream checks configuration objects, so False values do not avoid this call.
        if getattr(_local, "android_queue", False):
            return None
        return original_subtitles(*args, **kwargs)

    def safe_preload(song, delay, override):
        registered = False
        old_context = getattr(_local, "playback", False)
        try:
            with _lock:
                if g.preload_disabled or song.ytid in g.preloading:
                    return
                g.preloading.append(song.ytid)
                registered = True
            _local.playback = True
            time.sleep(delay)
            video = config.SHOW_VIDEO.get
            if override in ("fullscreen", "window", "forcevid"):
                video = True
            elif override == "audio":
                video = False
            choices = streams.get(song)
            selected = streams.select(choices, audio=not video,
                                      m4a_ok="mplayer" not in config.PLAYER.get)
            if not selected and not video:
                selected = streams.select(choices, audio=False)
            if not selected or not selected.get("url"):
                raise ValueError("No hay un stream reproducible para precargar")
            # Do not probe Content-Length: Media3 does not need it to preload.
        except Exception as error:
            _record("precarga", song, error)
        finally:
            _local.playback = old_context
            if registered:
                with _lock:
                    if song.ytid in g.preloading:
                        g.preloading.remove(song.ytid)

    def safe_details(song, *args, **kwargs):
        old_context = getattr(_local, "playback", False)
        _local.playback = True
        try:
            result = original_details(song, *args, **kwargs)
            if not result or len(result) != 3 or not result[1] or not result[1].get("url"):
                raise ValueError(g.message or "No hay un stream reproducible")
            return result
        except Exception as error:
            _record("extraccion", song, error)
            return False, _FailedStream(error), kwargs.get("override", False)
        finally:
            _local.playback = old_context

    def skip(self, position, error):
        reason = " ".join(_clean(error).split())[-240:]
        title = " ".join(_clean(self.song.title).split())[:100]
        message = "Omitido: %s — %s" % (title, reason)
        g.message = message
        screen.writestatus(message)
        self.song_no = position + 1
        self._android_consecutive_failures = getattr(self, "_android_consecutive_failures", 0) + 1
        if self._android_consecutive_failures >= len(self.songlist):
            self.repeat = False
            self.softrepeat = False
            self.song_no = len(self.songlist)
            raise _QueueExhausted("Cola detenida: ninguna pista se pudo reproducir. " + message)

    def safe_playsong(self, *args, **kwargs):
        position = self.song_no
        if isinstance(self.stream, _FailedStream):
            skip(self, position, self.stream.error)
            return
        try:
            result = original_playsong(self, *args, **kwargs)
        except Exception as error:
            _record("reproduccion", self.song, error)
            skip(self, position, error)
        else:
            self._android_consecutive_failures = 0
            return result

    def safe_play(self, *args, **kwargs):
        self._android_consecutive_failures = 0
        old_queue = getattr(_local, "android_queue", False)
        _local.android_queue = config.PLAYER.get == "androidplayer"
        self.subtitle_path = None
        try:
            return original_play(self, *args, **kwargs)
        except _QueueExhausted as stopped:
            g.message = str(stopped)
        finally:
            _local.android_queue = old_queue

    streams._preload = safe_preload
    player.stream_details = safe_details
    player.BasePlayer._playsong = safe_playsong
    player.BasePlayer.play = safe_play
    pafy.MyLogger.error = logger_error
    pafy.get_subtitles = optional_subtitles
    player._android_playback_guard = True
