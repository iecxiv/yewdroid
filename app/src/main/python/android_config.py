"""Expose only the yewtube settings that have an effect in the Android app."""
import re

ALLOWED = ("AUDIO_FORMAT", "ALWAYS_REPEAT", "COLUMNS", "HISTORY", "SHOW_VIDEO", "MAX_RES", "VIDEO_FORMAT",
           "ORDER")
SHOWN = ("ENCODER",) + ALLOWED
DEFAULTS = {"SHOW_VIDEO": False}


def help_topic():
    from mps_youtube import c
    return ("config", "Configuration Options", """
    {0}Configuration{1}

    {2}set{1} - view the settings available in the Android app
    {2}set <item> <value>{1} - change a setting
    {2}set <item> default{1} - restore a setting to its default value
    {2}set all default{1} - restore all of them
    {2}set -t <item> <value>{1} - change a setting temporarily, without saving it

    {2}set encoder <number>{1} - set encoding preset for downloaded files
    {2}set audio_format <auto|m4a|webm>{1} - preferred audio format for playback
    {2}set always_repeat true|false{1} - always in repeat mode without repeat <number>
    {2}set columns <columns>{1} - select extra displayed fields in search results:
         (valid: views comments rating date time user likes dislikes category ytid)
    {2}set history true|false{1} - record play history
    {2}set show_video true|false{1} - show the video above the terminal (audio only if false)
    {2}set max_res <360-2160>{1} - maximum video height to play, e.g. 360 or 720
    {2}set video_format <auto|mp4|webm|3gp>{1} - preferred video format for playback
    {2}set order <relevance|date|views|title>{1} - order the search results shown
         (applied to the loaded results; rating keeps the original order)

    Use {2}1{1} and {2}0{1} in place of true and false.
    Add {2}-a{1} or {2}-w{1} after a selection to play it only this time as audio or with video,
    e.g. {2}1-3 -a{1}
    Other yewtube settings have no effect in the Android app.
    """.format(c.ul, c.w, c.y))


def install():
    from mps_youtube import c, config, g
    from mps_youtube.commands import Command
    if getattr(g, "_android_config_installed", False):
        return

    def default_of(name):
        return DEFAULTS.get(name, config[name].default)

    def showconfig():
        out = "  %s%-17s   %s%s\n" % (c.ul, "Key", "Value", c.w)
        for name in config:
            if name in SHOWN:
                item = config[name]
                line = "  %s%-17s%s : %s" % (c.g, name.lower(), c.w, item.display)
                if item.temp_value is not None:
                    line += "  %s(temporary: %s)%s" % (c.y, item.display_temp, c.w)
                out += line + "\n"
        g.content = out
        g.message = "Enter %sset <key> <value>%s to change\nEnter %sset all default%s to reset all" % (
            c.g, c.w, c.g, c.w)

    def change(key, val, temp=False):
        key = key.replace("-", "_").upper()
        val = val.strip()
        if key == "ALL" and val.upper() == "DEFAULT":
            for name in SHOWN:
                config[name].value = default_of(name)
                config[name].temp_value = None
            config.save()
            message = "Default configuration reinstated"
        elif key == "ENCODER":
            message = "Enter %sset encoder <number>%s; %sencoders%s lists the presets" % (c.g, c.w, c.g, c.w)
        elif key not in SHOWN:
            if key in config:
                message = "%s%s%s is not available in the Android app" % (c.y, key.lower(), c.w)
            else:
                message = "Unknown config item: %s%s%s" % (c.r, key.lower(), c.w)
        elif val.upper() == "DEFAULT":
            item = config[key]
            item.value = default_of(key)
            item.temp_value = None
            config.save()
            message = "%s%s%s set to %s%s%s (default)" % (c.y, key.lower(), c.w, c.y, item.display or "None", c.w)
        else:
            message = config[key].set(val, is_temp=temp)
        showconfig()
        g.message = message or ""

    def change_temp(key, val):
        change(key, val, True)

    flags = re.IGNORECASE
    g.commands.insert(0, Command(re.compile(r"set\s+([-\w]+)\s*(.*)$", flags), None, None, change))
    g.commands.insert(0, Command(re.compile(r"set\s+-t\s*([-\w]+)\s*(.*)$", flags), None, None, change_temp))
    g.commands.insert(0, Command(re.compile(r"(?:set|showconfig)\s*$", flags), None, None, showconfig))
    g._android_config_installed = True
