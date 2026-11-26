"""Translate yewtube output to the phone language one line at a time, keeping colours."""
import importlib
import re
import threading

_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
_ERASE = re.compile(r"(?:\x1b\[[0-9]*K)+$")
_SPLIT = re.compile(r"(\r\n|\r|\n)")
_LANGUAGE = re.compile(r"[a-z]{2,3}")
_RESET = "\x1b[0m"
_lock = threading.Lock()
_state = {"language": None, "enabled": False, "plain": {}, "patterns": []}


def _compile(catalog):
    plain, patterns = {}, []
    for source, target in catalog.items():
        source_pieces = source.split("{}")
        target_pieces = target.split("{}")
        if len(source_pieces) != len(target_pieces):
            continue
        if len(source_pieces) == 1:
            plain[source] = (None, source_pieces, target_pieces)
        else:
            regex = re.compile("(.*?)".join(re.escape(piece) for piece in source_pieces), re.DOTALL)
            patterns.append((regex, source_pieces, target_pieces))
    patterns.sort(key=lambda entry: len(entry[0].pattern), reverse=True)
    return plain, patterns


def configure(tag, enabled):
    language = str(tag or "").replace("_", "-").split("-")[0].lower()
    with _lock:
        if language != _state["language"]:
            _state.update(language=language, plain={}, patterns=[])
            if _LANGUAGE.fullmatch(language) and language != "en":
                try:
                    module = importlib.import_module("i18n_" + language)
                    _state["plain"], _state["patterns"] = _compile(module.CATALOG)
                except Exception:
                    pass
        _state["enabled"] = bool(enabled)
        return bool(_state["plain"] or _state["patterns"])


def set_enabled(enabled):
    _state["enabled"] = bool(enabled)


def _translate_line(line):
    tail = ""
    erase = _ERASE.search(line)
    if erase:
        tail, line = erase.group(), line[:erase.start()]
    sequences = list(_ANSI.finditer(line))
    if any(not item.group().endswith("m") for item in sequences):
        return line + tail
    plain, states, state, position = [], [], "", 0
    for item in sequences:
        chunk = line[position:item.start()]
        plain.append(chunk)
        states.extend([state] * len(chunk))
        sequence = item.group()
        state = "" if sequence in ("\x1b[0m", "\x1b[m") else state + sequence
        position = item.end()
    chunk = line[position:]
    plain.append(chunk)
    states.extend([state] * len(chunk))
    text = "".join(plain)
    body = text.strip()
    if not body:
        return line + tail
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
    padding = max(trail - (len("".join(shown)) - len(body)), 0) if trail else 0
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
