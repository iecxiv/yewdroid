"""Structured view of the yewtube session for the native client."""
import itertools
import json
import math
import re
import sys

_ANSI = re.compile(r"\x1b\[[0-9;?%]*[A-Za-z]")
_INITIAL = "Enter /search-term to search or [h]elp"
# Grows with every snapshot, so the client can tell a fresh list from an identical earlier one.
_seq = itertools.count(1)


def _text(value):
    return "" if value is None or value is False else str(value)


def _known(value):
    text = _text(value).strip()
    return "" if text in ("", "?") else text


def _message(raw):
    text = _text(raw)
    if _ANSI.sub("", text).strip() == _INITIAL:
        return ""
    try:
        import android_i18n
        text = android_i18n.translate_message(text)
    except Exception:
        pass
    return _ANSI.sub("", text).strip()


def snapshot():
    """JSON with the current results page, for the native client."""
    from mps_youtube import g, util
    last = getattr(sys.modules.get("yt_term"), "last_message", None)
    message = _message(last()) if callable(last) else ""
    size = max(1, int(util.getxy().max_results))
    items = []
    if g.browse_mode == "ytpl":
        for number, entry in enumerate(g.ytpls, 1):
            items.append({"n": number, "id": _text(entry.get("link")), "title": _text(entry.get("title")),
                          "author": _text(entry.get("author")), "count": _text(entry.get("size"))})
    else:
        for number, video in enumerate(g.model, 1):
            meta = g.meta.get(video.ytid, {})
            items.append({"n": number, "id": video.ytid, "title": _text(video.title),
                          "length": int(video.length or 0),
                          "author": _known(meta.get("uploaderName")),
                          "views": _known(meta.get("viewCount")),
                          "date": _known(meta.get("uploaded"))})
    total = int(g.result_count or 0)
    return json.dumps({"mode": g.browse_mode, "page": int(g.current_page or 0),
                       "pages": math.ceil(total / size) if total else 0, "total": total,
                       "items": items, "message": message, "seq": next(_seq)}, ensure_ascii=False)


def queue():
    """JSON with the tracks of the current playback and the position being played."""
    from mps_youtube import g
    player = getattr(g, "PLAYER_OBJ", None)
    items = []
    for video in list(getattr(player, "songlist", None) or []):
        meta = g.meta.get(video.ytid, {})
        items.append({"id": video.ytid, "title": _text(video.title), "length": int(video.length or 0),
                      "author": _known(meta.get("uploaderName"))})
    return json.dumps({"items": items, "index": int(getattr(player, "song_no", 0) or 0)},
                      ensure_ascii=False)


def jump(index):
    """Skip to track `index` of the current playback (the player advances by one after this)."""
    from mps_youtube import g
    player = getattr(g, "PLAYER_OBJ", None)
    if player is None or not getattr(player, "_active", False):
        return False
    index = int(index)
    if not 0 <= index < len(player.songlist):
        return False
    player.song_no = index - 1
    player.finish("next")
    return True


def playlists():
    """JSON with the saved local playlists, numbered as the open/play/rmp commands expect."""
    from mps_youtube import g
    items = []
    for number, name in enumerate(sorted(g.userpl), 1):
        playlist = g.userpl[name]
        items.append({"n": number, "name": name, "count": len(playlist),
                      "duration": _text(getattr(playlist, "duration", ""))})
    return json.dumps({"items": items}, ensure_ascii=False)
