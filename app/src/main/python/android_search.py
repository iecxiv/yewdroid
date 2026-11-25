"""Order search results locally: yewtube no longer sends the order option to YouTube."""
import re

UNITS = {"second": 1, "minute": 60, "hour": 3600, "day": 86400,
         "week": 604800, "month": 2592000, "year": 31536000}
AGE = re.compile(r"(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago", re.IGNORECASE)


def _age(item):
    match = AGE.search(str(item.get("publishedTime") or ""))
    if not match:
        return float("inf")
    return int(match.group(1)) * UNITS[match.group(2).lower()]


def _views(item):
    view = item.get("viewCount")
    text = view.get("text") if isinstance(view, dict) else view
    digits = re.sub(r"\D", "", str(text or ""))
    return int(digits) if digits else 0


def _title(item):
    return str(item.get("title") or "").casefold()


KEYS = {"date": _age, "views": lambda item: -_views(item), "title": _title}


def install():
    from mps_youtube import config, pafy
    if getattr(pafy, "_android_search_order", False):
        return
    original = pafy.search_videos

    def search_videos(query, pages):
        results = original(query, pages)
        key = KEYS.get(config.ORDER.get)
        if key is None or not isinstance(results, list):
            return results
        try:
            return sorted(results, key=key)
        except Exception:
            return results

    pafy.search_videos = search_videos
    pafy._android_search_order = True
