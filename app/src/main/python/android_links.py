"""Open and share result links; sharing never sends a message automatically."""
import re
import sys
from urllib.parse import urlencode

_installed = False
TOPICS = ("openwith", "share")


def help_text():
    """Help pages, coloured like the other yewtube topics (built when shown, so colours are available)."""
    from mps_youtube import c
    return {
        "openwith": (
            "\n    {0}Opening Links{1}\n\n"
            "    {2}openwith N{1} - open result N's link in a compatible app.\n"
            "    N refers to a video or YouTube playlist in the current list.\n"
        ).format(c.ul, c.w, c.y),
        "share": (
            "\n    {0}Sharing Links{1}\n\n"
            "    {2}share N{1} - open the share chooser for result N's link.\n"
            "    N refers to a video or YouTube playlist in the current list.\n"
            "    Shares the YouTube link, not a downloaded file.\n"
            "    Nothing is sent automatically: confirm in the app you choose.\n"
        ).format(c.ul, c.w, c.y),
    }


def _format_help(text):
    # Match the extra two-space indentation applied by upstream get_help.
    return "\n  ".join(text.split("\n"))


def _help_topics(topics):
    result = []
    texts = help_text()
    for name, title, body in topics:
        if name in TOPICS:
            continue
        if name == "basic":
            body = "\n".join(line for line in body.split("\n") if not re.match(
                r"\s*[xX]\s+<number>", re.sub(r"\x1b\[[0-9;]*m", "", line)))
        result.append((name, title, body))
    result.extend((name, "Opening Links" if name == "openwith" else "Sharing Links", texts[name])
                  for name in TOPICS)
    return result


def install():
    global _installed
    if _installed:
        return
    from mps_youtube import content, g, helptext
    from mps_youtube.commands import command

    def finish(message):
        g.content = content.generate_songlist_display()
        g.message = message

    def current_link(number):
        index = int(number) - 1
        if g.browse_mode == "ytpl":
            if index < 0 or index >= len(g.ytpls):
                raise ValueError("Playlist number out of range")
            identifier = g.ytpls[index].get("link")
            path, key = "playlist", "list"
        elif g.browse_mode == "normal":
            if index < 0 or index >= len(g.model):
                raise ValueError("Video number out of range")
            identifier = getattr(g.model[index], "ytid", None)
            path, key = "watch", "v"
        else:
            raise ValueError("This mode contains no video or playlist links")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError("The item has no valid identifier")
        return "https://www.youtube.com/%s?%s" % (path, urlencode({key: identifier}))

    @command(r"(openwith|share)\s+(\d{1,4})", "openwith", "share")
    def link_action(action, number):
        try:
            url = current_link(number)
            from java import jclass
            app = jclass("com.chaquo.python.Python").getPlatform().getApplication()
            android_action = "open" if action == "openwith" else action
            result = jclass("iecxiv.yewdroid.AndroidLinks").perform(app, android_action, url)
            finish(str(result))
        except Exception as error:
            finish("Could not open or share the link: %s" % error)

    @command(r"(?:help|h)\s+(openwith|share)")
    @command(r"(openwith|share)")
    def link_help(action):
        g.content = _format_help(help_text()[action])
        g.message = ""

    # Keep link help ahead of generic help; never register or intercept open.
    ours = [entry for entry in g.commands if entry.function in (link_action, link_help)]
    g.commands[:] = ours + [entry for entry in g.commands if entry.function not in (link_action, link_help)]
    original_topics = helptext.helptext

    def topics():
        return _help_topics(original_topics())

    for name, module in tuple(sys.modules.items()):
        if name.startswith("mps_youtube.") and module is not None:
            for attribute, value in tuple(vars(module).items()):
                if value is original_topics:
                    setattr(module, attribute, topics)
    _installed = True
