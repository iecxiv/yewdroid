"""Shared helpers for client downloads: quiet logging, cancellation, stage progress and YouTube clients."""
import re
import threading

QUIET = threading.local()

# YouTube restricts each internal client at different times. When the default client is asked to
# sign in, formats() retries with these; the one that worked is remembered for the downloads.
FALLBACK_CLIENTS = (("tv",), ("web_safari",), ("mweb",))
CLIENTS = [None]

_ANSI = re.compile(r"\x1b?\[[0-9;]*m")


class Cancelled(Exception):
    """Raised inside a download when the user cancels it."""


def plain(text):
    """Text without terminal colour codes (yt-dlp colours its errors)."""
    return _ANSI.sub("", str(text))


def is_bot_check(error):
    text = plain(error).lower()
    return "confirm you" in text and "bot" in text


def extractor_options():
    """yt-dlp options that select the YouTube client that last worked."""
    client = CLIENTS[0]
    return {"extractor_args": {"youtube": {"player_client": list(client)}}} if client else {}


def say(*args, **kwargs):
    """print() unless the current thread is a client download (its progress goes to the UI)."""
    if not getattr(QUIET, "on", False):
        print(*args, **kwargs)


class Progress:
    """Receives (stage, fraction) updates from a download and aborts it once cancelled."""

    def __init__(self, callback=None):
        self.callback = callback
        self.cancelled = False

    def stage(self, name, fraction=0.0):
        if self.cancelled:
            raise Cancelled()
        if self.callback is not None:
            self.callback(name, max(0.0, min(1.0, float(fraction))))
