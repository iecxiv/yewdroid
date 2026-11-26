"""Conversion step of client audio downloads: applies the selected yewtube encoding preset with FFmpeg."""
import os


def selected():
    """The preset to apply to audio downloads, or None when the preset 'None' (copy) is selected."""
    try:
        from android_encode import selected_profile
        profile, _ = selected_profile()
    except Exception:
        return None
    return None if profile.get("ext") == "COPY" else profile


def check(profile):
    """Raises with a readable message when FFmpeg or the preset's encoder is not available."""
    from android_encode import validate
    validate(profile)


def encode(source, profile, tracker):
    """Converts [source] with [profile] into a sibling 'encoded' folder and returns the new path.
    Files the preset does not accept are returned unchanged."""
    from android_encode import accepts, arguments
    from android_ffmpeg import run_cancellable
    extension = os.path.splitext(source)[1].lstrip(".").lower()
    if not accepts(profile, extension):
        return source
    folder = os.path.join(os.path.dirname(source), "encoded")
    os.makedirs(folder, exist_ok=True)
    name = os.path.splitext(os.path.basename(source))[0] + "." + profile["ext"]
    output = os.path.join(folder, name)
    run_cancellable(arguments(profile, source, output), lambda: tracker.cancelled)
    if not os.path.isfile(output) or os.path.getsize(output) == 0:
        raise RuntimeError("The conversion did not produce a finished file")
    return output
