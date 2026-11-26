"""Shared helpers for client downloads: quiet logging, cancellation and stage progress."""
import threading

QUIET = threading.local()


class Cancelled(Exception):
    """Raised inside a download when the user cancels it."""


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
