"""Sort loaded search results by the order setting, which the keyless search ignores."""
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
    text = str(text or "")
    digits = re.sub(r"\D", "", text)
    if digits:
        return int(digits)
    return 0 if "no views" in text.lower() else -1


def sort_results(results, order):
    if order == "date":
        return sorted(results, key=_age)
    if order == "views":
        return sorted(results, key=_views, reverse=True)
    if order == "title":
        return sorted(results, key=lambda item: str(item.get("title") or "").casefold())
    return list(results)


def install():
    from mps_youtube import config, g, pafy
    if getattr(g, "_android_search_order_installed", False):
        return
    original = pafy.search_videos

    def search_videos(query, pages):
        results = original(query, pages)
        try:
            return sort_results(results, config.ORDER.get)
        except Exception:
            return results

    pafy.search_videos = search_videos
    g._android_search_order_installed = True
