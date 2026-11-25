"""Read original presets from the installed yewtube without writing files."""
import importlib
import json
from types import CodeType
from android_encoder_editor import parse


def extract_defaults(code):
    candidates = []
    def visit(item):
        for value in item.co_consts:
            if isinstance(value, CodeType):
                visit(value)
            elif isinstance(value, str) and value.startswith("# transcoding presets") and "ENCODER_PATH:" in value and "command:" in value:
                candidates.append(value)
    visit(code)
    if len(candidates) != 1:
        raise ValueError("Unable to locate the original presets in this yewtube version")
    text = candidates[0]
    parse(text)
    return text


def default_document():
    init = importlib.import_module("mps_youtube.init")
    return json.dumps({"text": extract_defaults(init._init_transcode.__code__)})


def help_topic():
    from mps_youtube import c
    return ("edit-encoders", "Editing encoding presets on Android", """
    {0}Editing encoding presets on Android{1}

    Enter {2}edit encoders{1} to edit the original transcoding configuration.

    {2}Save & Close{1} validates the file, creates a backup,
    reloads the presets and returns to the terminal.

    {2}Restore defaults{1} loads the original encoding presets
    into the editor. Select {2}Save & Close{1} to apply them.

    {2}Cancel{1} returns without saving your edits.
    If saving fails, the editor stays open and displays the error.

    Rotating the device preserves your draft, selection and scroll position.
    Keep the original field names and command placeholders unchanged.
    If the selected preset is removed, the selection returns to None.
    An ongoing conversion keeps the preset it started with.

    Editing a preset does not install an encoder or change the FFmpeg build.
    """.format(c.ul, c.w, c.y))
