"""Updating the bridge (sol_watch.py) from GitHub inside the app, safely.

- The new file is checked before it's used: it must compile, have a VERSION newer than the one
  running, and still have every name pocket_runner hooks into (read from its syntax tree, without
  running any of it).
- It's kept in the app's own folder (code_dir), next to the copy it replaces (sol_watch.prev.py).
- Its first run is a trial: if that bridge fails before it has made TRIAL_POLLS checks, the app goes
  back to the previous copy and won't try that version again.
- A newer bridge bundled with an app update always wins over an older download.
"""
import ast
import importlib
import importlib.machinery
import json
import re
import sys
from pathlib import Path

DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/Jomangork/sol-watch-bridge/main/sol_watch.py"
VERSION_RE = re.compile(r'^VERSION\s*=\s*"(\d+\.\d+\.\d+)"', re.MULTILINE)
CURRENT = "sol_watch.py"
PREVIOUS = "sol_watch.prev.py"
TRIAL = "trial.json"
REJECTED = "rejected.json"
TRIAL_POLLS = 20
_ADDED = set()  # code folders activate() put on sys.path


def version_of(source):
    found = VERSION_RE.search(source or "")
    return found.group(1) if found else None


def version_tuple(text):
    return tuple(int(part) for part in text.split(".")) if text else (0,)


def _file_version(path):
    try:
        return version_of(Path(path).read_text(encoding="utf-8")[:20000])
    except OSError:
        return None


def _bundled_spec(code_dir):
    """The sol_watch shipped with the app. On Android it lives inside the APK (Chaquopy's importer),
    not as a plain file, so it's found and read through the import system."""
    skip = str(Path(code_dir)) if code_dir else None
    return importlib.machinery.PathFinder.find_spec("sol_watch", [p for p in sys.path if p != skip])


def _bundled_version(code_dir):
    spec = _bundled_spec(code_dir)
    if spec is None or spec.loader is None:
        return None
    try:
        return version_of(spec.loader.get_data(spec.origin)[:20000].decode("utf-8", "replace"))
    except Exception:  # an importer that can't hand out its source: treat the bundled copy as unknown
        return None


def missing_names(source, required):
    """Names from `required` ("Discord.post", "HERE", …) that the source doesn't define at top level."""
    tree = ast.parse(source)
    top, members = set(), {}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            top.add(node.name)
        elif isinstance(node, ast.ClassDef):
            top.add(node.name)
            members[node.name] = {n.name for n in node.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
        elif isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            top.update(t.id for t in targets if isinstance(t, ast.Name))
    missing = []
    for name in required:
        head, _, member = name.partition(".")
        if head not in top or (member and member not in members.get(head, set())):
            missing.append(name)
    return missing


def check_source(source, running_version, required):
    """None when the source may be installed, else the reason it may not."""
    version = version_of(source)
    if not version:
        return "it has no VERSION line"
    if version_tuple(version) <= version_tuple(running_version):
        return f"v{version} isn't newer than v{running_version}"
    try:
        compile(source, CURRENT, "exec")
    except SyntaxError as err:
        return f"it doesn't compile ({err.msg}, line {err.lineno})"
    missing = missing_names(source, required)
    if missing:
        return f"it no longer has what the app hooks into: {', '.join(missing)}"
    return None


def _rejected(code_dir):
    try:
        return set(json.loads((Path(code_dir) / REJECTED).read_text()))
    except (OSError, ValueError):
        return set()


def install(code_dir, source, running_version, required):
    """Check and install a downloaded sol_watch.py. Returns (installed, message)."""
    code_dir = Path(code_dir)
    version = version_of(source)
    if version in _rejected(code_dir):
        return False, f"v{version} failed here before, so it's skipped"
    reason = check_source(source, running_version, required)
    if reason:
        return False, f"Not installing the new bridge: {reason}."
    code_dir.mkdir(parents=True, exist_ok=True)
    current = code_dir / CURRENT
    if current.exists():
        current.replace(code_dir / PREVIOUS)
    temporary = code_dir / (CURRENT + ".new")
    temporary.write_text(source, encoding="utf-8")
    temporary.replace(current)
    (code_dir / TRIAL).write_text(json.dumps({"version": version, "from": running_version}))
    return True, f"Installed bridge v{version} (was v{running_version})."


def fetch_and_install(code_dir, running_version, required, url=DEFAULT_SOURCE_URL):
    """Download the published sol_watch.py and install it if it's newer and fits. (installed, message)"""
    import requests
    try:
        response = requests.get(url, timeout=30)
    except requests.RequestException as err:
        return False, f"Couldn't reach GitHub: {err}"
    if response.status_code != 200:
        return False, f"GitHub answered HTTP {response.status_code}."
    return install(code_dir, response.text, running_version, required)


def trial_passed(code_dir):
    """The new bridge made enough checks: keep it."""
    (Path(code_dir) / TRIAL).unlink(missing_ok=True)


def roll_back(code_dir):
    """The new bridge failed during its trial: go back to the copy before it. Message, or None."""
    code_dir = Path(code_dir)
    trial = code_dir / TRIAL
    if not trial.exists():
        return None
    try:
        info = json.loads(trial.read_text())
    except ValueError:
        info = {}
    rejected = _rejected(code_dir) | {info.get("version")}
    (code_dir / REJECTED).write_text(json.dumps(sorted(v for v in rejected if v)))
    previous = code_dir / PREVIOUS
    if previous.exists():
        previous.replace(code_dir / CURRENT)
    else:
        (code_dir / CURRENT).unlink(missing_ok=True)  # back to the copy bundled with the app
    trial.unlink()
    return f"Bridge v{info.get('version')} failed to start, so the app went back to v{info.get('from')}."


def activate(code_dir):
    """Make `import sol_watch` load the newest copy: the download in code_dir, or the bundled one.
    A module already loaded from the other copy is dropped, so the next import is a clean one."""
    folder = str(Path(code_dir)) if code_dir else None
    for added in _ADDED - {folder}:  # a folder from an earlier activate() no longer applies
        while added in sys.path:
            sys.path.remove(added)
        _ADDED.discard(added)
    downloaded = Path(code_dir) / CURRENT if code_dir else None
    use_download = (downloaded is not None and downloaded.exists() and
                    version_tuple(_file_version(downloaded)) > version_tuple(_bundled_version(code_dir)))
    if folder and folder in sys.path:
        sys.path.remove(folder)
        _ADDED.discard(folder)
    if use_download:
        sys.path.insert(0, folder)
        _ADDED.add(folder)
        wanted = str(downloaded)
    else:
        spec = _bundled_spec(code_dir)
        wanted = spec.origin if spec else None
    loaded = sys.modules.get("sol_watch")
    if loaded is not None and wanted and getattr(loaded, "__file__", None) != wanted:
        del sys.modules["sol_watch"]
    importlib.invalidate_caches()
    return wanted


def describe(code_dir):
    """For the Status tab: which bridge runs and where it came from."""
    bundled = _bundled_version(code_dir)
    downloaded = _file_version(Path(code_dir) / CURRENT) if code_dir else None
    newer = downloaded and version_tuple(downloaded) > version_tuple(bundled)
    return json.dumps({"bundled": bundled, "downloaded": downloaded,
                       "using": downloaded if newer else bundled, "source": "GitHub" if newer else "app",
                       "trial": (Path(code_dir) / TRIAL).exists() if code_dir else False})
