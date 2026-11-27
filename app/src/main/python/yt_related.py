"""Related videos for the queue, read from YouTube's InnerTube "next" endpoint (no yt-dlp).

related(video_id) -> JSON list of {id, title, length, author}; enqueue(video_id, items_json) appends them to
the queue of the playback in progress, only if the track playing is video_id.
"""
import json
import re
import sys
import time
import urllib.request

_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
_VERSION = "2.20250101.00.00"
_MAX_ITEMS = 40
_cfg = {}


def _log(text):
    sys.stderr.write("[related] " + text + "\n")
    sys.stderr.flush()


def _http(url, data=None, headers=None):
    request = urllib.request.Request(url, data=data, headers={"User-Agent": _UA, **(headers or {})})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read().decode("utf-8", "replace")


def _read_ytcfg():
    """API key and client version the YouTube web page uses right now."""
    page = _http("https://www.youtube.com/", headers={"Accept-Language": "en-US,en;q=0.9"})
    key = re.search(r'"INNERTUBE_API_KEY":"([^"]+)"', page)
    version = re.search(r'"INNERTUBE_CONTEXT_CLIENT_VERSION":"([^"]+)"', page)
    _cfg["key"] = key.group(1) if key else None
    _cfg["version"] = version.group(1) if version else _VERSION


def _call_next(video_id):
    body = {
        "context": {"client": {"clientName": "WEB", "clientVersion": _cfg.get("version", _VERSION),
                               "hl": "en", "gl": "US"}},
        "videoId": video_id,
    }
    key = _cfg.get("key")
    url = "https://www.youtube.com/youtubei/v1/next" + ("?key=" + key if key else "")
    raw = _http(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    return json.loads(raw)


def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


def _text(node):
    if not isinstance(node, dict):
        return ""
    if "simpleText" in node:
        return node["simpleText"]
    if "content" in node:
        return node["content"]
    return "".join(run.get("text", "") for run in node.get("runs", []))


def _seconds(text):
    """"3:45" or "1:02:03" to seconds (0 when it is not a time)."""
    parts = str(text or "").strip().split(":")
    if not parts or not all(part.isdigit() for part in parts) or len(parts) > 3:
        return 0
    total = 0
    for part in parts:
        total = total * 60 + int(part)
    return total


def _parse(data):
    """Two layouts: compactVideoRenderer (older) and lockupViewModel (newer)."""
    found, seen = [], set()
    for node in _walk(data):
        item = None
        if "compactVideoRenderer" in node:
            renderer = node["compactVideoRenderer"]
            item = {"id": renderer.get("videoId", ""), "title": _text(renderer.get("title")),
                    "length": _seconds(_text(renderer.get("lengthText"))),
                    "author": _text(renderer.get("longBylineText") or renderer.get("shortBylineText"))}
        elif "lockupViewModel" in node:
            renderer = node["lockupViewModel"]
            if renderer.get("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO":
                meta = renderer.get("metadata", {}).get("lockupMetadataViewModel", {})
                length = ""
                for sub in _walk(renderer.get("contentImage", {})):
                    badge = sub.get("thumbnailBadgeViewModel")
                    if badge:
                        length = badge.get("text", "")
                        break
                author = ""
                for sub in _walk(meta.get("metadata", {})):
                    parts = sub.get("metadataParts")
                    if parts:
                        author = _text(parts[0].get("text"))
                        break
                item = {"id": renderer.get("contentId", ""), "title": _text(meta.get("title")),
                        "length": _seconds(length), "author": author}
        if item and item["id"] and item["title"] and item["id"] not in seen:
            seen.add(item["id"])
            found.append(item)
    return found


def related(video_id):
    """JSON list of the videos YouTube suggests after video_id ("[]" when there are none or it fails)."""
    start = time.time()
    items = []
    try:
        try:
            items = _parse(_call_next(video_id))
        except Exception as error:
            _log("first try failed (%s); reading the page config" % error)
            items = []
        if not items:
            _read_ytcfg()
            items = _parse(_call_next(video_id))
    except Exception as error:
        _log("failed: %s" % error)
        return "[]"
    items = [item for item in items if item["id"] != video_id][:_MAX_ITEMS]
    _log("%d suggestions in %.2f s" % (len(items), time.time() - start))
    return json.dumps(items, ensure_ascii=False)


def enqueue(video_id, items_json):
    """Appends the suggestions to the queue being played. Returns "ok", "none" (nothing new), "wait" (the
    player is not up yet), "mismatch" (another track is playing) or "error"."""
    try:
        from mps_youtube import g
        from mps_youtube.playlist import Video
        player = getattr(g, "PLAYER_OBJ", None)
        if player is None or not getattr(player, "_active", False):
            return "wait"
        songs = player.songlist
        index = int(player.song_no)
        if not 0 <= index < len(songs):
            return "wait"
        if songs[index].ytid != video_id:
            return "mismatch"
        have = {song.ytid for song in songs}
        added = 0
        for item in json.loads(items_json):
            if item["id"] in have:
                continue
            songs.append(Video(item["id"], item["title"], int(item.get("length") or 0)))
            meta = g.meta.setdefault(item["id"], {})
            if item.get("author"):
                meta.setdefault("uploaderName", item["author"])
            have.add(item["id"])
            added += 1
        _log("queued %d suggestions" % added)
        return "ok" if added else "none"
    except Exception as error:
        _log("enqueue failed: %s" % error)
        return "error"
