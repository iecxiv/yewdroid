"""Android stream adaptation: codec-based selection, optional size, HTTP metadata."""
import json
import math
import sys
import threading
import time
from urllib.parse import parse_qs, urlparse

_lock = threading.RLock()


def _number(value, default=-1):
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError, OverflowError):
        return default


def _normalise(info):
    result = []
    base_headers = info.get("http_headers") or {}
    for fmt in info.get("formats") or []:
        url = fmt.get("url")
        protocol = fmt.get("protocol") or (urlparse(url).scheme if isinstance(url, str) else "")
        if not isinstance(url, str) or urlparse(url).scheme not in ("https", "http"):
            continue
        if fmt.get("has_drm") or protocol not in ("https", "http", "m3u8", "m3u8_native"):
            continue
        audio = fmt.get("acodec") not in (None, "none")
        video = fmt.get("vcodec") not in (None, "none")
        if not audio or (not video and fmt.get("vcodec") != "none"):
            continue
        height = int(_number(fmt.get("height"), 0))
        width = int(_number(fmt.get("width"), 0))
        if video and (height <= 0 or width <= 0):
            continue
        size = _number(fmt.get("filesize"))
        if size < 0:
            size = _number(fmt.get("filesize_approx"))
        headers = dict(base_headers)
        headers.update(fmt.get("http_headers") or {})
        result.append({
            "url": url, "ext": fmt.get("ext") or "",
            "quality": "%dx%d" % (width, height) if video else "audio",
            "rawbitrate": _number(fmt.get("abr"), _number(fmt.get("tbr"))),
            "mtype": "video" if video else "audio", "size": int(size),
            "http_headers": {str(k): str(v) for k, v in headers.items() if v is not None},
            "mime": "application/x-mpegURL" if protocol.startswith("m3u8") else "",
        })
    return result


def _expiry(choices, now):
    deadlines = [now + 300]
    for item in choices:
        values = parse_qs(urlparse(item["url"]).query).get("expire", [])
        if values:
            deadline = _number(values[0], 0)
            if deadline > 0:
                deadlines.append(deadline - 30)
    return min(deadlines)


def install():
    from mps_youtube import config, g, pafy, streams
    import yt_dlp
    if getattr(streams, "_android_stream_adapter", False):
        return
    original_select = streams.select
    terminal = sys.modules["yt_term"]
    original_sink = terminal._sink

    def extraction_format(ctx):
        formats = ctx.get("formats") or []
        audio = [f for f in formats if f.get("vcodec") == "none"
                 and f.get("acodec") not in (None, "none")
                 and f.get("url") and not f.get("has_drm")]
        preferred = [f for f in audio if f.get("ext") == "m4a"]
        combined = [f for f in formats if f.get("vcodec") not in (None, "none")
                    and f.get("acodec") not in (None, "none")
                    and f.get("url") and not f.get("has_drm")]
        choices = preferred or audio or combined
        if not choices:
            raise ValueError("No playable audio track")
        yield choices[-1]

    def get(song, force=False, callback=None, threeD=False):
        with _lock:
            now = time.time()
            cached = g.streams.get(song.ytid)
            if not force and cached and _number(cached.get("expiry"), 0) > now:
                return cached["meta"]
            options = {"format": extraction_format, "noplaylist": True, "quiet": True,
                       "logger": pafy.MyLogger(), "postprocessors": [], "fixup": "never"}
            with yt_dlp.YoutubeDL(options) as ydl:
                info = ydl.extract_info("https://www.youtube.com/watch?v=%s" % song.ytid,
                                        download=False)
            choices = _normalise(info or {})
            if not choices:
                raise ValueError("The extraction returned no audio-compatible streams")
            g.streams[song.ytid] = {"expiry": _expiry(choices, now), "meta": choices}
            while len(g.streams) > max(1, getattr(g, "max_cached_streams", 50)):
                g.streams.pop(next(iter(g.streams)))
            return choices

    def select(slist, q=0, audio=False, m4a_ok=True, maxres=None):
        if not audio:
            return original_select(slist, q=q, audio=False, m4a_ok=m4a_ok, maxres=maxres)
        items = slist["meta"] if isinstance(slist, dict) else slist
        candidates = [s for s in items if s["mtype"] == "audio"
                      and (m4a_ok or s["ext"] != "m4a")]
        wanted = config.AUDIO_FORMAT.get
        preferred_ext = "m4a" if wanted == "auto" and m4a_ok else wanted
        preferred = [s for s in candidates if s["ext"] == preferred_ext]
        candidates = sorted(preferred or candidates, key=lambda s: s["rawbitrate"], reverse=True)
        if not candidates:
            return None
        return candidates[q] if 0 <= q < len(candidates) else candidates[0]

    def get_size(ytid, url, preloading=False):
        with _lock:
            for item in g.streams.get(ytid, {}).get("meta", []):
                if item["url"] == url:
                    return item["size"]
        return -1  # An unknown size is not a playback failure; do not issue a request.

    class Sink:
        def __getattr__(self, name):
            return getattr(original_sink, name)

        def play(self, url, title, artist, thumb, length):
            from java import jclass
            current = getattr(getattr(g, "PLAYER_OBJ", None), "stream", {})
            metadata = current if isinstance(current, dict) and current.get("url") == url else {}
            payload = {"headers": metadata.get("http_headers", {}), "mime": metadata.get("mime", "")}
            jclass("iecxiv.yewdroid.AndroidStreamSources").register(str(url), json.dumps(payload))
            return original_sink.play(url, title, artist, thumb, length)

    g.streams.clear()  # Discard metadata produced by the old adapter.
    streams.get = get
    streams.select = select
    streams.get_size = get_size
    terminal._sink = Sink()
    streams._android_stream_adapter = True
