"""Structured view of the yewtube session for the native client."""
import json
import math
import re
import sys

_ANSI = re.compile(r"\x1b\[[0-9;?%]*[A-Za-z]")
_INITIAL = "Enter /search-term to search or [h]elp"


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
                       "items": items, "message": message}, ensure_ascii=False)
