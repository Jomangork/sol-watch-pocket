"""Runs the unchanged SOL-Watch bridge (sol_watch.py) inside Sol Watch.

The bridge is not edited. This module hooks into it from outside:
- its Discord sender is replaced by a call to the app (`sender.deliver(...)`),
- its folder (HERE / LOG_DIR) is pointed at the app's private storage,
- the API key goes in as an environment variable, never as a file,
- its debug output (new-fields log, captures/) is turned off,
- each check (watcher) runs at the interval the user picked, or not at all (pocket_checks).

`check(module)` lists anything a newer sol_watch.py renamed, so the app can refuse it plainly.
"""
import importlib
import json
import logging
import os
import re
import sys
import threading
import time
import zlib
from pathlib import Path

import pocket_calendar
import pocket_checks
import pocket_glance
import pocket_kinds
import pocket_myalerts
import pocket_update

# Bridge reads the widget summary and the calendar reuse instead of reading again.
TAPPED_PATHS = tuple(dict.fromkeys(("/research", "/craft", "/clock", "/reference/techs", "/reference/places",
                                    "/reference/bodies") + pocket_calendar.PATHS + pocket_myalerts.PATHS))

# Written as settings.ini. The intervals themselves come from the user's checks (applied over
# these at startup and live); derelicts are "on" here only so the bridge creates that watcher,
# which the checks then switch off unless the user wants it.
BASE_SETTINGS = """\
[bridge]
mode = full

[derelicts]
check_minutes = 60
"""



def settings_ini(fleet_flights=False, fleet_warning_minutes=30, elevator_trips=False, new_options="{}"):
    """settings.ini with the app's own choices: posts about our fleets' flights (bridge 1.07.14+) and our space
    elevator bookings (1.07.15+; an older bridge ignores both), and how many minutes before they land or arrive
    to remind (0: no reminder). Global chat is always relayed on the phone: bridge 1.09.00+ turns it off by default
    for Discord, and the Chat check on the Checks tab decides whether chat is read at all.
    new_options: JSON {name: true/false} for [alerts] switches a newer bridge added (bridge_options), until the
    app knows them properly. Names it already sets, or that aren't plain words, are ignored."""
    try:
        extra = {k: v for k, v in json.loads(new_options or "{}").items()
                 if isinstance(k, str) and re.fullmatch(r"[a-z][a-z0-9_]*_posts", k) and k not in APP_OPTIONS}
    except (ValueError, AttributeError):
        extra = {}
    return BASE_SETTINGS + (f"\n[alerts]\nfleet_move_posts = {'yes' if fleet_flights else 'no'}\n"
                            f"elevator_trip_posts = {'yes' if elevator_trips else 'no'}\n"
                            f"fleet_arrival_warning_minutes = {max(0, min(1440, int(fleet_warning_minutes)))}\n"
                            "global_chat_posts = yes\n"
                            + "".join(f"{k} = {'yes' if v else 'no'}\n" for k, v in sorted(extra.items())))


# [alerts] switches the app sets itself, and those every bridge up to 1.09.00 had. A newer bridge's other on/off
# "*_posts" switches are offered in Settings › Checks as "New from the bridge" (bridge 1.10.00's rail_link_posts was
# the first), so they can be tried before an app update knows them.
APP_OPTIONS = ("fleet_move_posts", "elevator_trip_posts", "global_chat_posts")
KNOWN_OPTIONS = APP_OPTIONS + ("game_update_posts", "new_place_posts")
BRIDGE_OPTIONS_FILE = "bridge_options.json"


def _source(module):
    """The bridge's own text, when it's a .py file (a downloaded update; the bundled copy is compiled)."""
    path = getattr(module, "__file__", "") or ""
    if not path.endswith(".py"):
        return ""
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError:
        return ""


def _comment_above(source, constant):
    """The comment lines just above `CONSTANT = …` in the bridge's source, as one sentence or more."""
    lines = source.splitlines()
    at = next((i for i, line in enumerate(lines) if line.startswith(f"{constant} =")), None)
    if at is None:
        return ""
    found = []
    for line in reversed(lines[:at]):
        if not line.startswith("#"):
            break
        found.insert(0, line.lstrip("# ").rstrip())
    # Sentences about settings.ini are for the desktop bridge: the app's switch is the setting here.
    sentences = re.split(r"(?<=\.)\s+", " ".join(found))
    return " ".join(x for x in sentences if "settings.ini" not in x).strip()


def _check_for_path(module, path):
    """The Checks menu's check that reads this API path (its watcher's `path`), or None."""
    for row in pocket_checks.CATALOG:
        cls = getattr(module, row["id"], None)
        if getattr(cls, "path", None) == path:
            return row
    return None


def bridge_options(module):
    """[{name, label, about, default, check, checkTitle}] for the on/off [alerts] switches this bridge has and the
    app doesn't know. check: the menu's check whose reads it uses, when its description names the API path."""
    out = []
    for spec in getattr(module, "SETTINGS_SPEC", ()) or ():
        if not (isinstance(spec, tuple) and len(spec) >= 4):
            continue
        section, name, constant, kind = spec[:4]
        if section != "alerts" or kind != "bool" or not str(name).endswith("_posts") or name in KNOWN_OPTIONS:
            continue
        label = str(name)[:-len("_posts")].replace("_", " ").capitalize() + " posts"
        about = _comment_above(_source(module), constant)
        path = re.search(r"\((/[a-z][a-z/_]*)", about)
        row = _check_for_path(module, path.group(1)) if path else None
        about = re.sub(r"\s*\(/[^)]*\)", "", about)  # "(/industry surface.rails)" is for the bridge's readers
        out.append({"name": name, "label": label, "about": about, "default": bool(getattr(module, constant, False)),
                    "check": row["id"] if row else "", "checkTitle": row["title"] if row else ""})
    return out


def new_check(watcher):
    """{id, title, about, path, start} for a bridge watcher the Checks menu doesn't list (a newer bridge's), or None."""
    name = type(watcher).__name__
    if name in pocket_checks.IDS or name.startswith("Lite"):
        return None
    header = str(getattr(watcher, "header", "") or "")
    title = header.replace(" UPDATE", "").strip().capitalize() if header else \
        re.sub(r"(?<!^)(?=[A-Z])", " ", name.removesuffix("Watcher")).capitalize()
    doc = (type(watcher).__doc__ or "").strip().split("\n\n")[0]
    interval = int(getattr(watcher, "interval", 0) or 0)
    return {"id": name, "title": title, "about": " ".join(doc.split()), "path": str(getattr(watcher, "path", "") or ""),
            "start": max(pocket_checks.NEW_CHECK_START, interval)}


def _kind_headings(source):
    """{kind: heading} from the comments in ALERT_KINDS, when the bridge's source is there."""
    if "ALERT_KINDS = {" not in source:
        return {}
    block = source[source.index("ALERT_KINDS = {"):]
    block = block[:block.find("\n}\n")]
    headings, heading = {}, None
    for line in block.splitlines()[1:]:
        comment = re.match(r"\s+# (.+)", line)
        if comment:
            heading = comment.group(1).strip()
            continue
        kind = re.match(r'\s+"(\w+)":', line)
        if kind and heading:
            headings[kind.group(1)] = heading
    return headings


CHANNELS = ("corp", "general", "alliance")
WEBHOOK_PREFIX = "pocket://"
URGENT_MARKS = ("🚨", "🆘", "🔑")  # 🔑: without a working key the app watches nothing
GOOD_NEWS = re.compile(r"\bagain\b")  # "🔑 The game accepts our API key again" stays quiet

# The bridge's advice is written for its desktop setup; on the phone the key lives in the app.
# The bridge's daily GitHub check finds a newer version: with auto-update on, the app installs it.
NEW_VERSION_RE = re.compile(r"^🆕 \*\*SOL-Watch (\d+\.\d+\.\d+) is available\*\*")

APP_WORDING = (
    (re.compile(r"put it in `\.env` as `SOL_BRIDGE_API_KEY` and restart the bridge"),
     "paste it in Sol Watch's Settings › Status and tap Save key"),
)
CHAT_MARKS = ("💬", "🤝")
ALERT_KINDS_FILE = "alert_kinds.json"  # the bridge's alert kinds, for the app's Alert types page (bridge 1.08.00+)
LITE_GROUP = "Public news (Lite)"  # the lite bridge's posts: the app always runs the full bridge
STATUS_EVERY_SECONDS = 15
CALENDAR_EVERY_SECONDS = 60  # the app also checks calendar reminders each time
MY_ALERTS_FILE = "my_alerts.json"  # each rule's current value and the editor's choices, for the app
MY_ALERTS_FIRED_FILE = "my_alerts_fired.json"  # rules already posted, so a restart doesn't post them again
DATA_USE_FILE = "data_use.json"  # bytes each API path downloaded, kept across restarts (temporary measuring aid)
DATA_USE_SAVE_SECONDS = 300
DATA_USE_VERSION = 3  # 3 (0.9.2): chunked gzipped replies at their packed size; 2 counted them unpacked
# The bridge's own log sizes: (bridge.log MB, old copies, errors.log MB, old copies). The app's Storage card
# picks others; the wrapper then sets up the bridge's logs itself.
BRIDGE_LOG_SIZES = (5, 5, 2, 3)
FIRST_WIDGET_READ_SECONDS = 20  # let the bridge's own startup reads go first
FIRST_UPDATE_CHECK_SECONDS = 120

# What this wrapper uses from sol_watch.py. A newer bridge that renames any of these
# is refused with this list rather than half-working.
REQUIRED = (
    "VERSION", "HERE", "LOG_DIR", "POLL_COUNTS", "DISCORD_TIME", "readable_time",
    "Discord.post", "merge_repeats", "log", "heartbeat", "capture",
    "FieldTracker.observe", "FieldTracker.save", "Watcher.safe_poll", "apply_poll_intervals", "main",
    "SolApi", "read_ok", "OUTAGE", "PROBLEMS", "PROBLEM_TAGS",
)


# The bridge's hooks are global to the sol_watch module, so only one bridge may run per process.
# _ACTIVE holds the Runner whose bridge is running; a new run waits for it to end.
_ACTIVE = {"runner": None}
_ACTIVE_LOCK = threading.Lock()
WAIT_FOR_PREVIOUS_SECONDS = 90


class PocketStop(BaseException):
    """Raised from the bridge's heartbeat to stop it. BaseException, so the bridge's own
    `except Exception` around each poll can't swallow it."""


class Incompatible(RuntimeError):
    pass


def check(module):
    """Names from REQUIRED that the given sol_watch module lacks (empty when it's usable)."""
    missing = []
    for name in REQUIRED:
        target = module
        for part in name.split("."):
            target = getattr(target, part, None)
            if target is None:
                missing.append(name)
                break
    return missing


def unknown_checks(module):
    """Checks in the menu that this sol_watch.py doesn't have (they're simply ignored)."""
    return [check_id for check_id in pocket_checks.IDS
            if check_id not in pocket_checks.POCKET_FEEDS and not hasattr(module, check_id)]


def priority(line):
    """'urgent' for 🚨 / 🚨⚔️ / 🆘 and key problems (🔑), 'chat' for relayed chat, else 'normal'."""
    text = line.lstrip()
    if text.startswith(URGENT_MARKS) and not (text.startswith("🔑") and GOOD_NEWS.search(text)):
        return "urgent"
    if text.startswith(CHAT_MARKS):
        return "chat"
    return "normal"


def labels(line):
    """The bridge's labels on a line (bridge 1.08.00+ posts each as an Alert, a str that carries them):
    {kind, priority, title, game_ms, key}, or None for a plain line (an older bridge, or one not labelled)."""
    kind = getattr(line, "kind", None)
    if not kind:
        return None
    return {"kind": str(kind), "priority": str(getattr(line, "priority", "") or "normal"),
            "title": str(getattr(line, "title", "") or ""), "game_ms": getattr(line, "game_ms", None),
            "key": str(getattr(line, "key", "") or "")}


def app_priority(line):
    """'urgent', 'chat' or 'normal': the bridge's own label when the line has one ('info' is quiet, so
    'normal'), else read from its emoji."""
    found = labels(line)
    if found is None:
        return priority(line)
    return found["priority"] if found["priority"] in ("urgent", "chat") else "normal"


def alert_kinds(module):
    """The bridge's ALERT_KINDS as [{group, kinds: [{id, about, priority, new}]}], under pocket_kinds' headings,
    leaving out the lite bridge's. A kind pocket_kinds doesn't list yet goes under its heading in the bridge's own
    source when that's readable (a downloaded update), else under "Other", marked new. [] without labels."""
    kinds = getattr(module, "ALERT_KINDS", None)
    if not isinstance(kinds, dict):
        return []
    headings = _kind_headings(_source(module))
    known = {k for _, ids in pocket_kinds.GROUPS for k in ids}
    extra = {}
    for kind in kinds:
        if kind not in known:
            extra.setdefault(headings.get(kind, "Other"), []).append(kind)
    groups, placed = [], set()
    names = [name for name, _ in pocket_kinds.GROUPS]
    for name, ids in pocket_kinds.GROUPS + tuple((h, ()) for h in extra if h not in names):
        listed = [{"id": k, "about": kinds[k][0], "priority": kinds[k][1], "new": k not in known}
                  for k in tuple(ids) + tuple(extra.get(name, ())) if k in kinds and k not in placed]
        placed.update(entry["id"] for entry in listed)
        if listed and name != LITE_GROUP:
            groups.append({"group": name, "kinds": listed})
    return groups


# The status post lists the game website's code files it watches for game updates; on the phone, a count is enough.
GAME_BUILD_RE = re.compile(r" \| Game build: ([^|\n]+)")


def _game_build(match):
    files = [f for f in match.group(1).split(",") if f.strip()]
    return f" | Game build: {len(files)} file{'s' if len(files) != 1 else ''}"


def app_wording(line):
    for pattern, replacement in APP_WORDING:
        line = pattern.sub(replacement, line)
    return GAME_BUILD_RE.sub(_game_build, line)


def channel_of(webhook):
    if webhook and webhook.startswith(WEBHOOK_PREFIX):
        return webhook[len(WEBHOOK_PREFIX):]
    return "corp"


def plain(module, text):
    """Discord markdown to plain text: times written out, ** and ` dropped, <links> unwrapped."""
    text = module.DISCORD_TIME.sub(module.readable_time, text)
    text = text.replace("**", "").replace("`", "")
    return re.sub(r"<(https?://[^>\s]+)>", r"\1", text)


# A post's game time: `14 Nov 2032, 06:22 AM`, after the headline (bridge 1.07.13+) or right after the emoji.
GAME_STAMP_RE = re.compile(r"(?: · )?`([^`\n]*\d{4}[^`\n]*)` ?")
# The emoji, then a **bold** headline ("🧪 **Research done: Ion drives** | next: …").
HEADLINE_RE = re.compile(r"^((?:[^\w\s`*\[(]\S*\s+){0,3})\*\*(.+?)\*\*")
LABEL_RE = re.compile(r"^\[([^\]\n]+)\]$")
NEXT_BOLD_RE = re.compile(r"^\*\*(.+?)\*\*")
ACRONYMS = {"UEG", "LEO", "GEO", "HQ", "NPC", "AI", "ID"}
TITLE_MAX = 80
TITLE_BREAKS = (". ", " | ", ": ", " — ")


def tidy_label(label):
    """'SIEGE CONTRACT AGAINST US' -> 'Siege contract against us' (known acronyms such as UEG stay)."""
    words = [w if w in ACRONYMS else w.lower() for w in label.split(" ")]
    if words and words[0] not in ACRONYMS:
        words[0] = words[0][:1].upper() + words[0][1:]
    return " ".join(words)


def notification_parts(module, text):
    """(title, body, game time) for a notification, in plain text, saying nothing twice: the title is the
    emoji and the bold headline, the body is the rest, and the game time goes to the notification's header.
    Without a headline, a short first line is the title; a long one is split at its first break, or left
    whole as the body with no title (the app then shows the channel's name)."""
    first, _, more = text.partition("\n")
    stamp = GAME_STAMP_RE.search(first)
    game_time = plain(module, stamp.group(1)).strip() if stamp else ""
    if stamp:
        first = first[:stamp.start()] + " " + first[stamp.end():]
    first = re.sub(r"[ \t]{2,}", " ", first).strip()
    headline = HEADLINE_RE.match(first)
    if headline:
        title = headline.group(1) + headline.group(2).rstrip().rstrip(":")
        rest = first[headline.end():].lstrip(" ·|:—-,")
        label = LABEL_RE.match(headline.group(2).strip())
        if label:  # "**[RESEARCH ACTIVE]** **Ion drives** …" -> "Research active: Ion drives"
            title = headline.group(1) + tidy_label(label.group(1))
            named = NEXT_BOLD_RE.match(rest)
            if named:
                title += ": " + named.group(1).rstrip().rstrip(":")
                rest = rest[named.end():].lstrip(" ·|:—-,")
    elif len(plain(module, first)) <= TITLE_MAX:
        title, rest = first, ""
    else:
        cut = min((i for i in (first.find(b) for b in TITLE_BREAKS) if 0 < i <= TITLE_MAX), default=-1)
        title, rest = (first[:cut], first[cut:].lstrip(" .|:—")) if cut > 0 else ("", first)
    body = "\n".join(part for part in (rest.strip(), more.strip()) if part)
    return plain(module, title).strip(), plain(module, body).strip(), game_time


class Runner:
    def __init__(self, folder, api_key, sender, checks=None, timezone=None, settings=BASE_SETTINGS,
                 code_dir=None, auto_update=False, update_every=86400, log_sizes=None, my_alerts="[]"):
        self.folder = Path(folder)
        self.api_key = api_key
        self.sender = sender
        self.checks = dict(pocket_checks.preset("recommended"))
        self.checks.update(checks or {})
        self.timezone = timezone
        self.settings = settings
        self.stopping = False
        self.module = None
        self.api = None  # the bridge's SolApi, once main() has made it
        self._last_status = 0.0
        self._widget_due = time.monotonic() + FIRST_WIDGET_READ_SECONDS
        self._in_feed = False
        self.code_dir = code_dir        # where downloaded bridge updates live (None: never download)
        self.auto_update = auto_update
        self._update_wanted = None     # a version the bridge's own check says is published
        self.update_every = update_every  # seconds between the app's own GitHub checks (hourly on Wi-Fi)
        self._next_update_check = time.monotonic() + FIRST_UPDATE_CHECK_SECONDS
        self.updated = False
        self._trial_done = False
        self.tapped = {}  # path -> last body the bridge read
        self.outage_since = 0.0  # when the game's server stopped answering (0: it answers)
        self.outage_hidden = 0
        self._me = None
        self._calendar_due = time.monotonic() + FIRST_WIDGET_READ_SECONDS
        self.log_sizes = tuple(int(n) for n in log_sizes) if log_sizes else BRIDGE_LOG_SIZES
        self.data_use = {}  # path -> [bytes, reads] since the last save
        self._pool_seen = {}  # id(connection pool) -> (connections, requests) already saved
        self.ctx = None  # the bridge's shared context (names of places and goods), from its first poll
        self.my_alerts = []
        self.set_my_alerts_json(my_alerts)
        self._my_alerts_due = time.monotonic() + FIRST_WIDGET_READ_SECONDS
        self._spaceports_due = 0.0  # read once at the start, for the editor's list, then only while needed
        self._fired = None  # loaded on first check
        self._options = []
        self.new_checks = {}  # watcher class -> new_check() for the ones the menu doesn't list
        self._data_use_due = time.monotonic() + DATA_USE_SAVE_SECONDS

    # ---- control, callable from the app's threads ----

    def stop(self):
        """Ask the bridge to stop at its next heartbeat (within about a second). Stops whichever
        bridge is running in this process, even one started by an older Runner."""
        self.stopping = True
        with _ACTIVE_LOCK:
            active = _ACTIVE["runner"]
        if active is not None:
            active.stopping = True

    def set_checks(self, checks):
        """{watcher class: seconds, 0 = off}. Takes effect after each watcher's next poll
        (a check switched on polls within its new interval), no restart needed."""
        for check_id, seconds in dict(checks).items():
            self.checks[str(check_id)] = pocket_checks.clamp(check_id, seconds)
        if self.module:
            self._apply_checks()
        return dict(self.checks)

    def set_checks_json(self, text):
        """set_checks() for callers that only pass strings (the app)."""
        return json.dumps(self.set_checks(json.loads(text)))

    def set_update_every(self, seconds):
        """How often to look for a newer bridge on GitHub (the app: hourly on Wi-Fi, daily on mobile data)."""
        self.update_every = max(600, int(seconds or 0)) if seconds else 0

    def set_my_alerts_json(self, text):
        """The user's own alert rules (pocket_myalerts), as JSON; checked again within a minute."""
        try:
            rules = json.loads(text or "[]")
        except ValueError:
            rules = []
        self.my_alerts = [r for r in rules if isinstance(r, dict) and r.get("kind") in pocket_myalerts.KINDS]
        self._my_alerts_due = 0.0

    def is_off(self, check_id):
        return self.checks.get(check_id, 1) == 0

    # ---- setup ----

    def prepare(self, module_name="sol_watch"):
        self.folder.mkdir(parents=True, exist_ok=True)
        if self.settings is not None:
            (self.folder / "settings.ini").write_text(self.settings, encoding="utf-8")
        os.environ["SOL_BRIDGE_API_KEY"] = self.api_key
        for channel in CHANNELS:
            os.environ[f"WEBHOOK_{channel.upper()}"] = WEBHOOK_PREFIX + channel
        if self.timezone:
            os.environ["IRL_TIMEZONE"] = self.timezone

        if self.code_dir and module_name == "sol_watch":
            pocket_update.activate(self.code_dir)  # the newest copy: a download or the bundled one
        module = importlib.import_module(module_name)
        missing = check(module)
        if missing:
            raise Incompatible(f"sol_watch.py {getattr(module, 'VERSION', '?')} lacks: {', '.join(missing)}")
        module.HERE = self.folder
        module.LOG_DIR = self.folder / "logs"
        self.module = module
        self._patch(module)
        (self.folder / ALERT_KINDS_FILE).write_text(json.dumps(alert_kinds(module)), encoding="utf-8")
        self._options = bridge_options(module)
        self._write_bridge_options()
        return module

    def _apply_checks(self):
        sw = self.module
        for check_id, seconds in self.checks.items():
            cls = getattr(sw, check_id, None)
            if cls is not None and seconds:
                cls.interval = seconds

    def _patch(self, sw):
        runner = self
        # Keep the bridge's own functions once, so preparing twice doesn't wrap the wrappers.
        for name in ("heartbeat", "apply_poll_intervals", "log", "read_ok"):
            if not hasattr(sw, f"_pocket_original_{name}"):
                setattr(sw, f"_pocket_original_{name}", getattr(sw, name))
        if not hasattr(sw.Watcher, "_pocket_original_safe_poll"):
            sw.Watcher._pocket_original_safe_poll = sw.Watcher.safe_poll
        if not hasattr(sw.SolApi, "_pocket_original_init"):
            sw.SolApi._pocket_original_init = sw.SolApi.__init__
            sw.SolApi._pocket_original_get = sw.SolApi.get
        original_api_init = sw.SolApi._pocket_original_init
        original_api_get = sw.SolApi._pocket_original_get
        original_heartbeat = sw._pocket_original_heartbeat
        original_intervals = sw._pocket_original_apply_poll_intervals
        original_safe_poll = sw.Watcher._pocket_original_safe_poll

        # Bridge 1.07.13+ moves each line's game time after its headline inside Discord.post, which the app
        # replaces, so the app does the same here.
        reorder = getattr(sw, "stamp_after_headline", None) or (lambda line: line)
        relabel = getattr(sw, "relabel", None) or (lambda text, like: text)  # 1.08.00+: keep a line's labels
        original_log = sw._pocket_original_log
        original_read_ok = sw._pocket_original_read_ok

        def post(discord, webhook_url, header, lines):
            lines = sw.merge_repeats([relabel(reorder(line), line) for line in lines])
            for line in lines:
                sw.log(line)
            if not discord.enabled:
                return
            for line in lines:
                runner._deliver(channel_of(webhook_url), header, line)

        def heartbeat():
            if runner.stopping:
                raise PocketStop()
            original_heartbeat()
            runner._status()
            runner._widget()
            runner._calendar()
            runner._check_my_alerts()
            runner.save_data_use()
            runner._maybe_update()

        def apply_poll_intervals():
            # settings.ini first, then the user's checks over it.
            original_intervals()
            runner._apply_checks()
            off = sorted(c for c, s in runner.checks.items() if not s)
            sw.log(f"[Pocket] {len(runner.checks) - len(off)} checks on; off: {', '.join(off) or 'none'}")

        def safe_poll(watcher, when=""):
            if runner.ctx is None:
                runner.ctx = getattr(watcher, "ctx", None)
            if type(watcher).__name__ not in runner.new_checks:
                runner._spot_new_check(watcher)
            if runner.is_off(type(watcher).__name__):
                return
            return original_safe_poll(watcher, when)

        def api_init(api, *args, **kwargs):
            original_api_init(api, *args, **kwargs)
            runner.api = api  # so the widget feed reads through the bridge's own client (spacing, ETags, key checks)
            session_send = api.session.send

            def send(request, **send_kwargs):  # count what each read costs (temporary measuring aid)
                response = session_send(request, **send_kwargs)
                try:
                    runner._count(request, response)
                except Exception:
                    pass
                return response
            api.session.send = send

        def setup_logs():
            # The bridge's own setup, with the sizes the app's Storage card picked.
            log_mb, log_copies, err_mb, err_copies = runner.log_sizes
            sw.LOG_DIR.mkdir(exist_ok=True)
            formatter = logging.Formatter("%(asctime)s %(message)s", "%Y-%m-%d %H:%M:%S")
            for name, level, size_mb, copies in (("bridge.log", logging.INFO, log_mb, log_copies),
                                                 ("errors.log", logging.WARNING, err_mb, err_copies)):
                for old in sw.LOG_DIR.glob(name + ".*"):  # copies beyond the new count would never be replaced
                    if old.suffix[1:].isdigit() and int(old.suffix[1:]) > copies:
                        old.unlink(missing_ok=True)
                handler = sw.RotatingFileHandler(sw.LOG_DIR / name, maxBytes=size_mb * 1024 * 1024, backupCount=copies,
                                                 encoding="utf-8")
                handler.setLevel(level)
                handler.setFormatter(formatter)
                sw.LOG.addHandler(handler)
            sw.LOG.setLevel(logging.INFO)

        def api_get(api, path, *args, **kwargs):
            body = original_api_get(api, path, *args, **kwargs)
            if body is not None and path in TAPPED_PATHS:
                runner.tapped[path] = body
            return body

        def log(message, detail=None):
            # While the game's server is down, every read fails and logs a line: keep the first one, count
            # the rest (still noted for the bridge's daily status), and say once when the server is back.
            text = str(message)
            if sw.OUTAGE.match(text):
                if runner.outage_since:
                    runner.outage_hidden += 1
                    tag = sw.PROBLEM_TAGS.match(text)
                    sw.PROBLEMS.append((time.time(), tag.group(1) if tag else "API error", text))
                    return
                runner.outage_since = time.time()
                original_log(f"{text} (repeats hidden until the game's server answers)", detail)
                return
            original_log(message, detail)

        def read_ok(*args, **kwargs):
            original_read_ok(*args, **kwargs)
            if runner.outage_since:
                minutes = (time.time() - runner.outage_since) / 60
                if runner.outage_hidden or minutes >= 1:  # not for one dropped connection whose retry worked
                    original_log(f"[Pocket] The game's server answers again after {minutes:.0f} min "
                                 f"({runner.outage_hidden} more failed reads not logged).")
                runner.outage_since, runner.outage_hidden = 0.0, 0

        sw.log = log
        sw.read_ok = read_ok
        sw.SolApi.__init__ = api_init
        sw.SolApi.get = api_get
        sw.Discord.post = post
        sw.heartbeat = heartbeat
        sw.apply_poll_intervals = apply_poll_intervals
        sw.Watcher.safe_poll = safe_poll
        if hasattr(sw, "setup_logs") and hasattr(sw, "RotatingFileHandler"):
            sw.setup_logs = setup_logs
        sw.capture = lambda name, item: None
        sw.FieldTracker.observe = lambda tracker, endpoint, body: None
        sw.FieldTracker.save = lambda tracker: None
        self._apply_checks()

    # ---- to the app ----

    def _deliver(self, channel, header, line):
        label = labels(line)  # read before the app rewords the line, which drops them
        level = app_priority(line)
        found = NEW_VERSION_RE.match(line)
        if found and self.auto_update and self.code_dir:
            self._update_wanted = found.group(1)
            line = f"🆕 SOL-Watch {found.group(1)} is available; the app is installing it now."
        line = app_wording(line)
        title, body, game_time = notification_parts(self.module, line)
        if not title and label:
            title = label["title"]
        try:
            self.sender.deliver(channel, header or "", str(line), plain(self.module, line), level,
                                title, body, game_time, label["kind"] if label else "")
        except Exception as err:  # the app failing to show one alert mustn't stop the bridge
            self.module.log(f"[Pocket] Couldn't hand an alert to the app: {err!r}")

    def _status(self):
        now = time.monotonic()
        if now - self._last_status < STATUS_EVERY_SECONDS or not hasattr(self.sender, "status"):
            return
        self._last_status = now
        counts = self.module.POLL_COUNTS
        if self.code_dir and not self._trial_done and counts["polls"] >= pocket_update.TRIAL_POLLS:
            self._trial_done = True
            pocket_update.trial_passed(self.code_dir)  # this bridge works: keep it
        try:
            self.sender.status(json.dumps({
                "version": self.module.VERSION, "polls": counts["polls"], "failed": counts["failed"],
                "at": int(time.time()),
                # The bridge pauses every read while the game refuses the key; say so plainly.
                "keyRejected": bool(self.api is not None and getattr(self.api, "rejected", False)),
                "outageSince": int(self.outage_since),  # 0, or when the game's server stopped answering
            }))
        except Exception:
            pass

    def _maybe_update(self):
        """Look for a newer bridge on GitHub every update_every seconds; install one that the bridge's
        own daily check or this check found, then restart into it. Runs from heartbeat."""
        if self._in_feed:
            return
        now = time.monotonic()
        if (not self._update_wanted and self.auto_update and self.code_dir and self.update_every
                and now >= self._next_update_check):
            self._next_update_check = now + self.update_every
            self._in_feed = True
            try:
                published = pocket_update.published_version(
                    getattr(self.module, "BRIDGE_SOURCE_URL", pocket_update.DEFAULT_SOURCE_URL))
            except Exception:
                published = None
            finally:
                self._in_feed = False
            if (published and pocket_update.version_tuple(published) > pocket_update.version_tuple(self.module.VERSION)
                    and not pocket_update.is_rejected(self.code_dir, published)):
                self._update_wanted = published
        if not self._update_wanted:
            return
        version, self._update_wanted = self._update_wanted, None
        self._in_feed = True
        try:
            installed, message = pocket_update.fetch_and_install(
                self.code_dir, self.module.VERSION, REQUIRED, getattr(self.module, "BRIDGE_SOURCE_URL",
                                                                      pocket_update.DEFAULT_SOURCE_URL))
        except Exception as err:  # an update must never stop the running bridge
            installed, message = False, f"Couldn't install v{version}: {err!r}"
        finally:
            self._in_feed = False
        self.module.log(f"[Pocket] {message}")
        self._deliver("corp", "BRIDGE UPDATE", f"🆕 {message}" + (" Restarting into it." if installed else ""))
        if installed:
            self.updated = True
            self.stopping = True
            raise PocketStop()

    def _widget(self):
        """At the "Widget data" interval: read /me (money) and hand the app a summary for the
        home-screen widget, with research, arrivals and the clock from the bridge's own reads.
        Runs from the bridge's heartbeat; the bridge's client calls heartbeat too, hence the guard."""
        seconds = self.checks.get("PocketWidget", 0)
        if not seconds or self._in_feed or self.api is None or not hasattr(self.sender, "widget"):
            return
        now = time.monotonic()
        if now < self._widget_due:
            return
        self._widget_due = now + seconds
        self._in_feed = True
        try:
            me = self.api.get("/me", reader="PocketWidget")
            if me is not None:
                self._me = me
            summary = pocket_glance.summarize(
                self._me, self.tapped.get("/research"), self.tapped.get("/craft"), self.tapped.get("/clock"),
                self.tapped.get("/reference/techs"), self.tapped.get("/reference/places"),
                self.tapped.get("/reference/bodies"),
            )
            if summary:
                self.sender.widget(json.dumps(summary))
        except Exception as err:  # the widget must never stop the bridge
            self.module.log(f"[Pocket] Couldn't build the widget data: {err!r}")
        finally:
            self._in_feed = False

    def _write_bridge_options(self):
        """bridge_options.json: a newer bridge's switches and checks the app doesn't know, for "New from the bridge"."""
        try:
            (self.folder / BRIDGE_OPTIONS_FILE).write_text(json.dumps(
                {"version": getattr(self.module, "VERSION", ""), "options": self._options,
                 "checks": [c for c in self.new_checks.values() if c]}), encoding="utf-8")
        except OSError as err:
            self.module.log(f"[Pocket] Couldn't list what's new from the bridge: {err!r}")

    def _spot_new_check(self, watcher):
        """A watcher the menu doesn't list: it runs at NEW_CHECK_START (or slower) until the user picks."""
        name = type(watcher).__name__
        found = new_check(watcher)
        self.new_checks[name] = found
        if not found:
            return
        if name not in self.checks:
            self.checks[name] = found["start"]
            type(watcher).interval = found["start"]
        self.module.log(f"[Pocket] New check from the bridge: {found['title']} ({found['path'] or name}), "
                        f"every {self.checks[name] // 60} min")
        self._write_bridge_options()

    def _check_my_alerts(self):
        """Once a minute: the user's own alerts against the replies the checks already read (pocket_myalerts).
        Posts the ones that crossed their line and writes each rule's current value for the app."""
        now = time.monotonic()
        if now < self._my_alerts_due or self._in_feed or self.module is None:
            return
        self._my_alerts_due = now + CALENDAR_EVERY_SECONDS
        self._in_feed = True
        try:
            if self.api is not None and now >= self._spaceports_due and (
                    self._spaceports_due == 0.0 or pocket_myalerts.needs_spaceports(self.my_alerts)):
                read = getattr(self.api, "get_latest", None) or self.api.get
                body = read(pocket_myalerts.SPACEPORTS_PATH)
                if body is not None:
                    self.tapped[pocket_myalerts.SPACEPORTS_PATH] = body
                self._spaceports_due = now + pocket_myalerts.SPACEPORTS_EVERY_SECONDS
            fired_file = self.folder / MY_ALERTS_FIRED_FILE
            if self._fired is None:
                try:
                    self._fired = set(json.loads(fired_file.read_text(encoding="utf-8")))
                except (OSError, ValueError, TypeError):
                    self._fired = set()
            before = set(self._fired)
            names = pocket_myalerts.Names(self.tapped, self.module, self.ctx)
            alerts, status = pocket_myalerts.check(self.my_alerts, self.tapped, self._me, int(time.time() * 1000),
                                                   self._fired, names)
            self._fired &= {str(r.get("id")) for r in self.my_alerts}  # deleted rules
            if self._fired != before:
                fired_file.write_text(json.dumps(sorted(self._fired)), encoding="utf-8")
            (self.folder / MY_ALERTS_FILE).write_text(json.dumps(
                {"status": status, "choices": pocket_myalerts.choices(self.tapped, names)}), encoding="utf-8")
            for rule, title, body in alerts:
                text = f"{title} | {body}"
                self.module.log(text)
                if hasattr(self.sender, "deliver"):
                    self.sender.deliver("corp", "MY ALERTS", text, text, "urgent" if rule.get("loud") else "normal",
                                        title, body, "", pocket_myalerts.KIND)
        except Exception as err:  # the user's alerts must never stop the bridge
            self.module.log(f"[Pocket] Couldn't check My alerts: {err!r}")
        finally:
            self._in_feed = False

    def _count(self, request, response):
        """Bytes one read cost: the compressed body as it came over the network, plus headers both ways."""
        raw = getattr(response, "raw", None)
        # urllib3 counts the bytes it read off the network in _fp_bytes_read. Its tell() says 0 for a gzipped reply
        # (most of the game's), which made 0.8.0-0.8.7 count headers only. A chunked reply isn't counted there either,
        # and has no Content-Length: compressing the body again gives about the size that came over the network
        # (0.8.8-0.9.1 counted the unpacked size, 5-15 times too much).
        body = getattr(raw, "_fp_bytes_read", None)
        if not isinstance(body, int) or (body == 0 and response.content):
            length = response.headers.get("Content-Length")
            packed = response.headers.get("Content-Encoding", "").lower() in ("gzip", "deflate", "br")
            if length and length.isdigit():
                body = int(length)
            elif packed and response.content:
                body = len(zlib.compress(response.content, 6))
            else:
                body = len(response.content or b"")
        headers = sum(len(k) + len(v) + 4 for k, v in response.headers.items())
        headers += sum(len(k) + len(v) + 4 for k, v in request.headers.items()) + len(request.url) + 40
        url = request.url.split("?", 1)[0]
        base = getattr(self.module, "API_BASE", "")
        path = url[len(base):] if base and url.startswith(base) else url
        entry = self.data_use.setdefault(path, [0, 0])
        entry[0] += body + headers
        entry[1] += 1

    def save_data_use(self, force=False):
        """Adds the counts since the last save to data_use.json: {"since": unix s, "paths": {path: [bytes, reads]}}."""
        now = time.monotonic()
        if not force and now < self._data_use_due:
            return
        self._data_use_due = now + DATA_USE_SAVE_SECONDS
        counts, self.data_use = self.data_use, {}
        opened = self._new_connections()
        if not counts and not opened:
            return
        file = self.folder / DATA_USE_FILE
        try:
            saved = json.loads(file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        if saved.get("v") != DATA_USE_VERSION:  # counted the old way (headers only): start again
            saved = {}
        paths = saved.get("paths") if isinstance(saved.get("paths"), dict) else {}
        for path, (size, reads) in counts.items():
            old = paths.get(path) or [0, 0]
            paths[path] = [old[0] + size, old[1] + reads]
        hosts = saved.get("connections") if isinstance(saved.get("connections"), dict) else {}
        for host, (made, used) in opened.items():
            old = hosts.get(host) or [0, 0]
            hosts[host] = [old[0] + made, old[1] + used]
        file.write_text(json.dumps({"v": DATA_USE_VERSION, "since": saved.get("since") or int(time.time()),
                                    "paths": paths, "names": self._path_names(), "connections": hosts}),
                        encoding="utf-8")

    def _new_connections(self):
        """{host: [connections opened, requests sent]} since the last save, from the bridge's connection pools.
        Each new HTTPS connection costs a few KB of handshake that the per-check byte counts can't see, so a
        high ratio of connections to requests explains data the phone counts but the checks don't."""
        out = {}
        try:
            pools = self.api.session.get_adapter("https://").poolmanager.pools._container.values()
        except Exception:  # no bridge yet, or a urllib3 that keeps its pools elsewhere
            return out
        for pool in list(pools):
            made, used = getattr(pool, "num_connections", 0), getattr(pool, "num_requests", 0)
            old_made, old_used = self._pool_seen.get(id(pool), (0, 0))
            if made < old_made or used < old_used:  # a different pool now has that id
                old_made, old_used = 0, 0
            self._pool_seen[id(pool)] = (made, used)
            if made - old_made or used - old_used:
                entry = out.setdefault(str(getattr(pool, "host", "?")), [0, 0])
                entry[0] += made - old_made
                entry[1] += used - old_used
        return out

    def _path_names(self):
        """{API path: the checks that read it} from the Checks menu, for the usage log's per-check figures."""
        names = {"/clock": "Game clock (the bridge's own)", "/reference": "Reference tables (the bridge's own)"}
        for row in pocket_checks.CATALOG:
            path = pocket_checks.POCKET_FEEDS.get(row["id"]) or getattr(getattr(self.module, row["id"], None), "path", None)
            if isinstance(path, str) and path:
                base = getattr(self.module, "API_BASE", "")
                path = path[len(base):] if base and path.startswith(base) else path
                names[path] = f"{names[path]} + {row['title']}" if path in names else row["title"]
        return names

    def _calendar(self):
        """Every CALENDAR_EVERY_SECONDS: the Calendar tab's entries, from replies the bridge already read."""
        now = time.monotonic()
        if now < self._calendar_due or not hasattr(self.sender, "calendar"):
            return
        self._calendar_due = now + CALENDAR_EVERY_SECONDS
        try:
            entries = pocket_calendar.build(dict(self.tapped, **{"/me": self._me}), int(time.time() * 1000))
            if entries or self.tapped.get("/clock"):
                self.sender.calendar(json.dumps(entries))
        except Exception as err:  # the calendar must never stop the bridge
            self.module.log(f"[Pocket] Couldn't build the calendar: {err!r}")

    def run(self):
        """Run the bridge until stop() is called. Returns "updated" after installing a new bridge
        (the caller starts the next run at once), else "stopped". Other errors propagate.
        Waits (up to WAIT_FOR_PREVIOUS_SECONDS) for a bridge still ending in this process."""
        deadline = time.monotonic() + WAIT_FOR_PREVIOUS_SECONDS
        while True:
            with _ACTIVE_LOCK:
                if _ACTIVE["runner"] is None:
                    _ACTIVE["runner"] = self
                    break
                _ACTIVE["runner"].stopping = True  # a new run replaces the old one
            if self.stopping:
                return "stopped"
            if time.monotonic() > deadline:
                raise RuntimeError("The previous bridge didn't stop; restart the app.")
            time.sleep(0.5)
        try:
            self._run()
            return "updated" if self.updated else "stopped"
        finally:
            with _ACTIVE_LOCK:
                if _ACTIVE["runner"] is self:
                    _ACTIVE["runner"] = None

    def _run(self):
        sw = self.module or self.prepare()
        self._patch(sw)  # bind the bridge's hooks to this run
        sys.argv = ["sol_watch.py", "--silent"]
        sw.POLL_COUNTS.update(polls=0, failed=0)  # count per run, so a restart shows
        try:
            sw.main()
        except PocketStop:
            sw.log("[Pocket] Stopped.")
        finally:
            try:
                self.save_data_use(force=True)
            except Exception:
                pass
            # Let a later run in the same process take the lock and log files again.
            handle = getattr(sw, "_lock_handle", None)
            if handle:
                handle.close()
                sw._lock_handle = None
            for handler in list(sw.LOG.handlers):
                sw.LOG.removeHandler(handler)
                handler.close()


# The game's own grouping of key sections (Settings › Account › API keys).
KEY_GROUPS = {
    "Company": ("company", "research", "industry", "market", "account"),
    "Space": ("craft", "fleets", "mining", "contracts", "discoveries"),
    "World": ("alliance", "factions", "ueg", "defense", "segments", "news", "chat", "alliance_chat"),
}

# Sections a bridge reads but doesn't yet list in its KEY_SECTIONS (same shape), so the key card explains them.
EXTRA_SECTIONS = (
    ("market", "Market", "optional", ("/market",), "Earth's market corruption alerts (bridge 1.07.16+)"),
)


def describe_key(api_key, module_name="sol_watch"):
    """What a key may read, for the app's key card: one read of the API index (open to any key).
    JSON: {"ok", "error", "groups": [{group, sections: [{id, label, need, enabled, stops}]}], "other": [ids]}."""
    import requests
    sw = importlib.import_module(module_name)
    try:
        response = requests.get(sw.API_BASE, headers={"Authorization": f"Bearer {api_key.strip()}"}, timeout=15)
    except requests.RequestException as err:
        return json.dumps({"ok": False, "error": f"Couldn't reach the game: {err}"})
    if response.status_code == 429:
        return json.dumps({"ok": False, "error": "The game is locking out bad keys from this phone for a few "
                                                  "minutes (after reads with a revoked key). Try again in 5 minutes."})
    if response.status_code != 200:
        return json.dumps({"ok": False, "error": f"The game refused this key (HTTP {response.status_code}). "
                                                  "Check it's the whole key, copied when it was made."})
    try:
        index = response.json()
        have = {s.get("id") for s in index.get("sections", []) if isinstance(s, dict)}
    except (ValueError, AttributeError):
        return json.dumps({"ok": False, "error": "The game's answer wasn't readable."})
    known = {sid: {"id": sid, "label": label, "need": need, "enabled": sid in have, "stops": stops}
             for sid, label, need, _paths, stops in EXTRA_SECTIONS + tuple(sw.KEY_SECTIONS)}  # the bridge's own wording wins
    groups = []
    for group, ids in KEY_GROUPS.items():
        rows = [known.pop(sid, {"id": sid, "label": sid.title(), "need": "unused", "enabled": sid in have,
                                "stops": "nothing: the app doesn't use it"}) for sid in ids]
        groups.append({"group": group, "sections": rows})
    if known:  # sections the bridge knows that the game's grouping above doesn't (yet)
        groups.append({"group": "Other", "sections": list(known.values())})
    listed = {r["id"] for g in groups for r in g["sections"]}
    return json.dumps({"ok": True, "error": "", "groups": groups, "other": sorted(have - listed)})
