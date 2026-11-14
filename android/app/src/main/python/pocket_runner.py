"""Runs the unchanged SOL-Watch bridge (sol_watch.py) inside SOL-Watch Pocket.

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
import os
import re
import sys
import threading
import time
from pathlib import Path

import pocket_checks
import pocket_glance
import pocket_update

# Bridge reads the widget summary reuses instead of reading again.
TAPPED_PATHS = ("/research", "/craft", "/clock", "/reference/techs", "/reference/places", "/reference/bodies")

# Written as settings.ini. The intervals themselves come from the user's checks (applied over
# these at startup and live); derelicts are "on" here only so the bridge creates that watcher,
# which the checks then switch off unless the user wants it.
BASE_SETTINGS = """\
[bridge]
mode = full

[derelicts]
check_minutes = 60
"""

CHANNELS = ("corp", "general", "alliance")
WEBHOOK_PREFIX = "pocket://"
URGENT_MARKS = ("🚨", "🆘", "🔑")  # 🔑: without a working key the app watches nothing
GOOD_NEWS = re.compile(r"\bagain\b")  # "🔑 The game accepts our API key again" stays quiet

# The bridge's advice is written for its desktop setup; on the phone the key lives in the app.
# The bridge's daily GitHub check finds a newer version: with auto-update on, the app installs it.
NEW_VERSION_RE = re.compile(r"^🆕 \*\*SOL-Watch (\d+\.\d+\.\d+) is available\*\*")

APP_WORDING = (
    (re.compile(r"put it in `\.env` as `SOL_BRIDGE_API_KEY` and restart the bridge"),
     "paste it on SOL-Watch Pocket's Status tab and tap Save key"),
)
CHAT_MARKS = ("💬", "🤝")
STATUS_EVERY_SECONDS = 15
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


def app_wording(line):
    for pattern, replacement in APP_WORDING:
        line = pattern.sub(replacement, line)
    return line


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
TITLE_MAX = 80
TITLE_BREAKS = (". ", " | ", ": ", " — ")


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
    elif len(plain(module, first)) <= TITLE_MAX:
        title, rest = first, ""
    else:
        cut = min((i for i in (first.find(b) for b in TITLE_BREAKS) if 0 < i <= TITLE_MAX), default=-1)
        title, rest = (first[:cut], first[cut:].lstrip(" .|:—")) if cut > 0 else ("", first)
    body = "\n".join(part for part in (rest.strip(), more.strip()) if part)
    return plain(module, title).strip(), plain(module, body).strip(), game_time


class Runner:
    def __init__(self, folder, api_key, sender, checks=None, timezone=None, settings=BASE_SETTINGS,
                 code_dir=None, auto_update=False, update_every=86400):
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
        original_log = sw._pocket_original_log
        original_read_ok = sw._pocket_original_read_ok

        def post(discord, webhook_url, header, lines):
            lines = sw.merge_repeats([reorder(line) for line in lines])
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
            runner._maybe_update()

        def apply_poll_intervals():
            # settings.ini first, then the user's checks over it.
            original_intervals()
            runner._apply_checks()
            off = sorted(c for c, s in runner.checks.items() if not s)
            sw.log(f"[Pocket] {len(runner.checks) - len(off)} checks on; off: {', '.join(off) or 'none'}")

        def safe_poll(watcher, when=""):
            if runner.is_off(type(watcher).__name__):
                return
            return original_safe_poll(watcher, when)

        def api_init(api, *args, **kwargs):
            original_api_init(api, *args, **kwargs)
            runner.api = api  # so the widget feed reads through the bridge's own client (spacing, ETags, key checks)

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
        sw.capture = lambda name, item: None
        sw.FieldTracker.observe = lambda tracker, endpoint, body: None
        sw.FieldTracker.save = lambda tracker: None
        self._apply_checks()

    # ---- to the app ----

    def _deliver(self, channel, header, line):
        found = NEW_VERSION_RE.match(line)
        if found and self.auto_update and self.code_dir:
            self._update_wanted = found.group(1)
            line = f"🆕 SOL-Watch {found.group(1)} is available; the app is installing it now."
        line = app_wording(line)
        title, body, game_time = notification_parts(self.module, line)
        try:
            self.sender.deliver(channel, header or "", line, plain(self.module, line), priority(line),
                                title, body, game_time)
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
             for sid, label, need, _paths, stops in sw.KEY_SECTIONS}
    groups = []
    for group, ids in KEY_GROUPS.items():
        rows = [known.pop(sid, {"id": sid, "label": sid.title(), "need": "unused", "enabled": sid in have,
                                "stops": "nothing: the app doesn't use it"}) for sid in ids]
        groups.append({"group": group, "sections": rows})
    if known:  # sections the bridge knows that the game's grouping above doesn't (yet)
        groups.append({"group": "Other", "sections": list(known.values())})
    listed = {r["id"] for g in groups for r in g["sections"]}
    return json.dumps({"ok": True, "error": "", "groups": groups, "other": sorted(have - listed)})
