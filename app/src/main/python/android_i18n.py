"""Translate yewtube output to the phone language one line at a time, keeping colours."""
import importlib
import re
import threading

# yewtube's underline is "\x1b[2m\x1b[4m\x1b[%33m" (a stray % in the last sequence), so allow % here.
_ANSI = re.compile(r"\x1b\[[0-9;?%]*[A-Za-z]")
_ERASE = re.compile(r"(?:\x1b\[[0-9]*K)+$")
_SPLIT = re.compile(r"(\r\n|\r|\n)")
_PLACEHOLDER = re.compile(r"(\{#\}|\{\})")
_TARGET = re.compile(r"\{#?\}")
_GAPS = re.compile(r"(\s{2,})")
_LANGUAGE = re.compile(r"[a-z]{2,3}")
_PARTS = ("", "_yt", "_app", "_help")
_RESET = "\x1b[0m"
_HEADINGS = frozenset((
    "Num Title Length View Rtng Comm Date Time User Like Dslk Category Username Description "
    "Item Playlist Author Updated Count ID Name Duration Format Quality Media Size Notes Key Value"
).split()) | {"Video ID"}
_lock = threading.Lock()
_state = {"language": None, "enabled": False, "plain": {}, "patterns": [], "headings": {}}


def _compile(catalog):
    plain, patterns = {}, []
    for source, target in catalog.items():
        pieces = _PLACEHOLDER.split(source)
        source_pieces, kinds = pieces[0::2], pieces[1::2]
        target_pieces = _TARGET.split(target)
        if len(source_pieces) != len(target_pieces):
            continue
        if not kinds:
            plain[source] = (None, source_pieces, target_pieces)
            continue
        regex = re.escape(source_pieces[0])
        for kind, piece in zip(kinds, source_pieces[1:]):
            regex += (r"(\d+)" if kind == "{#}" else "(.*?)") + re.escape(piece)
        patterns.append((re.compile(regex, re.DOTALL), source_pieces, target_pieces))
    patterns.sort(key=lambda entry: len(entry[0].pattern), reverse=True)
    return plain, patterns


def configure(tag, enabled):
    language = str(tag or "").replace("_", "-").split("-")[0].lower()
    with _lock:
        if language != _state["language"]:
            _state.update(language=language, plain={}, patterns=[], headings={})
            if _LANGUAGE.fullmatch(language) and language != "en":
                catalog, headings = {}, {}
                for suffix in _PARTS:
                    try:
                        module = importlib.import_module("i18n_" + language + suffix)
                    except Exception:
                        continue
                    catalog.update(getattr(module, "CATALOG", {}))
                    headings.update(getattr(module, "HEADINGS", {}))
                _state["plain"], _state["patterns"] = _compile(catalog)
                _state["headings"] = headings
        _state["enabled"] = bool(enabled)
        return bool(_state["plain"] or _state["patterns"])


def set_enabled(enabled):
    _state["enabled"] = bool(enabled)


def _parse(line):
    plain, states, ansi, state, position = [], [], {}, "", 0
    length = 0
    for item in _ANSI.finditer(line):
        chunk = line[position:item.start()]
        plain.append(chunk)
        states.extend([state] * len(chunk))
        length += len(chunk)
        sequence = item.group()
        ansi[length] = ansi.get(length, "") + sequence
        if sequence.endswith("m"):
            state = "" if sequence in ("\x1b[0m", "\x1b[m") else state + sequence
        position = item.end()
    chunk = line[position:]
    plain.append(chunk)
    states.extend([state] * len(chunk))
    return "".join(plain), states, ansi


def _translate_header(text, ansi):
    headings = _state["headings"]
    parts = _GAPS.split(text)
    words = [part.strip() for part in parts[0::2] if part.strip()]
    if len(words) < 2 or not all(word in _HEADINGS for word in words):
        return None
    old_starts, offset = [], 0
    for original in parts:
        old_starts.append(offset)
        offset += len(original)
    for index in range(0, len(parts), 2):
        word = parts[index]
        new = headings.get(word)
        if not word or not new:
            continue
        if index + 1 < len(parts):
            gap = index + 1
        elif index - 1 >= 0:
            gap = index - 1
        else:
            continue
        room = len(word) + len(parts[gap]) - 1
        new = new[:room]
        parts[index] = new
        parts[gap] = " " * (len(word) + len(parts[gap]) - len(new))
    new_starts, offset = [], 0
    for part in parts:
        new_starts.append(offset)
        offset += len(part)
    result = "".join(parts)
    inserts = {}
    for position, sequence in ansi.items():
        target = len(result)
        if position < len(text):
            part = max(i for i, start in enumerate(old_starts) if start <= position)
            target = new_starts[part] + min(position - old_starts[part], len(parts[part]))
        inserts[target] = inserts.get(target, "") + sequence
    out = []
    for index in range(len(result) + 1):
        out.append(inserts.get(index, ""))
        if index < len(result):
            out.append(result[index])
    return "".join(out)


def _translate_line(line):
    tail = ""
    erase = _ERASE.search(line)
    if erase:
        tail, line = erase.group(), line[:erase.start()]
    if any(not item.group().endswith("m") for item in _ANSI.finditer(line)):
        return line + tail
    text, states, ansi = _parse(line)
    body = text.strip()
    if not body:
        return line + tail
    if _state["headings"]:
        header = _translate_header(text, ansi)
        if header is not None:
            return header + tail
    lead = len(text) - len(text.lstrip())
    trail = len(text) - len(text.rstrip())
    entry = _state["plain"].get(body)
    match = None
    if entry is None:
        for candidate in _state["patterns"]:
            match = candidate[0].fullmatch(body)
            if match:
                entry = candidate
                break
    if entry is None:
        return line + tail
    _, source_pieces, target_pieces = entry
    count = len(source_pieces) - 1
    groups = [match.group(i + 1) for i in range(count)] if match else []

    def state_at(index):
        return states[lead + index] if lead + index < len(states) else ""

    default = state_at(0)
    out, shown, current = [], [], ""

    def emit(wanted, piece):
        nonlocal current
        if not piece:
            return
        if wanted != current:
            if current:
                out.append(_RESET)
            out.append(wanted)
            current = wanted
        out.append(piece)
        shown.append(piece)

    for k, piece in enumerate(target_pieces):
        start = 0 if k == 0 else match.end(k)
        emit(state_at(start) if source_pieces[k] else default, piece)
        if k < count:
            emit(state_at(match.start(k + 1)) if groups[k] else default, groups[k])
    if current:
        out.append(_RESET)
    padding = max(trail - (len("".join(shown)) - len(body)), 1) if trail else 0
    return " " * lead + "".join(out) + " " * padding + tail


def translate(text):
    if not _state["enabled"] or not (_state["plain"] or _state["patterns"]):
        return text
    try:
        parts = _SPLIT.split(text)
        for index in range(0, len(parts), 2):
            parts[index] = _translate_line(parts[index])
        return "".join(parts)
    except Exception:
        return text


def translate_message(text):
    """Translate regardless of the terminal toggle (used by the native client)."""
    if not (_state["plain"] or _state["patterns"]):
        return text
    try:
        return "\n".join(_translate_line(line) for line in text.split("\n"))
    except Exception:
        return text


def install(term):
    if getattr(term, "_i18n_installed", False):
        return
    term._i18n_installed = True
    original = term._Out.write

    def write(self, s):
        shown = s
        if s:
            try:
                shown = translate(str(s))
            except Exception:
                shown = s
        original(self, shown)
        return len(s) if s else 0

    term._Out.write = write
