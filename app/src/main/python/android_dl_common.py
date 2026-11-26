"""Shared helpers for client downloads: quiet logging, cancellation, stage progress and YouTube retries."""
import re
import threading

QUIET = threading.local()

# Player clients tried in order when YouTube asks for a sign-in; None is yt-dlp's default set.
FALLBACK_CLIENTS = (None, ["tv"], ["web_safari"], ["mweb"])

_ANSI = re.compile(r"\x1b?\[[0-9;]*m")


class Cancelled(Exception):
    """Raised inside a download when the user cancels it."""


def plain(text):
    """The text without terminal colour codes (yt-dlp colours its error messages)."""
    return _ANSI.sub("", str(text))


def is_bot_error(error):
    """True for YouTube's 'Sign in to confirm you're not a bot' answer."""
    text = plain(error).lower()
    return "not a bot" in text or "sign in to confirm" in text


def youtube_opts(clients=None):
    """yt-dlp options that force the given YouTube player clients (nothing for the default)."""
    if not clients:
        return {}
    return {"extractor_args": {"youtube": {"player_client": list(clients)}}}


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
