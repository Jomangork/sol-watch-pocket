"""Offline tests for bridge updates (no network). Run: python3 -m unittest discover -s tests"""
import importlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import pocket_runner  # noqa: E402
import pocket_update  # noqa: E402

BUNDLED = (PYTHON_DIR / "sol_watch.py").read_text(encoding="utf-8")
BUNDLED_VERSION = pocket_update.version_of(BUNDLED)


def bumped(source=BUNDLED, to="9.99.99"):
    return pocket_update.VERSION_RE.sub(f'VERSION = "{to}"', source, count=1)


class Checks(unittest.TestCase):
    def test_the_bundled_bridge_has_everything_the_wrapper_needs(self):
        self.assertEqual(pocket_update.missing_names(BUNDLED, pocket_runner.REQUIRED), [])

    def test_newer_and_complete_is_accepted(self):
        self.assertIsNone(pocket_update.check_source(bumped(), BUNDLED_VERSION, pocket_runner.REQUIRED))

    def test_not_newer_is_refused(self):
        self.assertIn("isn't newer", pocket_update.check_source(BUNDLED, BUNDLED_VERSION, pocket_runner.REQUIRED))

    def test_broken_code_is_refused(self):
        broken = bumped() + "\ndef oops(:\n"
        self.assertIn("doesn't compile", pocket_update.check_source(broken, BUNDLED_VERSION, pocket_runner.REQUIRED))

    def test_a_renamed_hook_is_refused(self):
        renamed = bumped().replace("class Discord:", "class Courier:")
        reason = pocket_update.check_source(renamed, BUNDLED_VERSION, pocket_runner.REQUIRED)
        self.assertIn("Discord.post", reason)


class InstallAndRollBack(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.code = Path(self.tmp.name)

    def tearDown(self):
        pocket_update.activate(None)  # back to the bundled bridge for the other tests
        importlib.import_module("sol_watch")
        self.tmp.cleanup()

    def test_install_then_roll_back_to_the_bundled_copy(self):
        ok, message = pocket_update.install(self.code, bumped(), BUNDLED_VERSION, pocket_runner.REQUIRED)
        self.assertTrue(ok, message)
        self.assertTrue((self.code / "sol_watch.py").exists())
        self.assertTrue((self.code / "trial.json").exists())
        self.assertIn("9.99.99", pocket_update.roll_back(self.code))
        self.assertFalse((self.code / "sol_watch.py").exists())
        self.assertEqual(json.loads((self.code / "rejected.json").read_text()), ["9.99.99"])
        ok, message = pocket_update.install(self.code, bumped(), BUNDLED_VERSION, pocket_runner.REQUIRED)
        self.assertFalse(ok)  # a version that failed here isn't tried again
        self.assertIn("skipped", message)

    def test_roll_back_restores_the_previous_download(self):
        pocket_update.install(self.code, bumped(to="9.99.98"), BUNDLED_VERSION, pocket_runner.REQUIRED)
        pocket_update.trial_passed(self.code)
        pocket_update.install(self.code, bumped(to="9.99.99"), "9.99.98", pocket_runner.REQUIRED)
        pocket_update.roll_back(self.code)
        self.assertEqual(pocket_update.version_of((self.code / "sol_watch.py").read_text()), "9.99.98")

    def test_no_trial_no_roll_back(self):
        self.assertIsNone(pocket_update.roll_back(self.code))

    def test_activate_loads_the_newer_download_and_back(self):
        pocket_update.install(self.code, bumped(), BUNDLED_VERSION, pocket_runner.REQUIRED)
        pocket_update.activate(self.code)
        module = importlib.import_module("sol_watch")
        self.assertEqual(module.VERSION, "9.99.99")
        self.assertEqual(Path(module.__file__).resolve(), (self.code / "sol_watch.py").resolve())
        pocket_update.activate(None)
        self.assertEqual(importlib.import_module("sol_watch").VERSION, BUNDLED_VERSION)

    def test_an_older_download_loses_to_the_bundled_copy(self):
        (self.code / "sol_watch.py").write_text(bumped(to="0.0.1"))
        pocket_update.activate(self.code)
        self.assertEqual(importlib.import_module("sol_watch").VERSION, BUNDLED_VERSION)


class Sender:
    def __init__(self):
        self.delivered = []

    def deliver(self, channel, header, text, plain, priority, title="", body="", game_time=""):
        self.delivered.append(text)


class RunnerUpdates(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sender = Sender()
        self.runner = pocket_runner.Runner(Path(self.tmp.name) / "bridge", "k", self.sender,
                                           code_dir=Path(self.tmp.name) / "code", auto_update=True)
        self.sw = self.runner.prepare()
        self.original = pocket_update.fetch_and_install

    def tearDown(self):
        pocket_update.fetch_and_install = self.original
        self.tmp.cleanup()

    def test_the_bridges_new_version_notice_starts_an_update_and_a_restart(self):
        self.sw.Discord(enabled=True).post("pocket://corp", "BRIDGE UPDATE",
                                           ["🆕 **SOL-Watch 9.99.99 is available** (this bridge runs 1.0). Download it…"])
        self.assertEqual(self.runner._update_wanted, "9.99.99")
        self.assertIn("installing it now", self.sender.delivered[-1])
        pocket_update.fetch_and_install = lambda *a, **k: (True, "Installed bridge v9.99.99 (was v1).")
        with self.assertRaises(pocket_runner.PocketStop):
            self.runner._maybe_update()
        self.assertTrue(self.runner.updated)
        self.assertIn("Restarting into it", self.sender.delivered[-1])

    def test_a_refused_update_keeps_the_bridge_running(self):
        self.runner._update_wanted = "9.99.99"
        pocket_update.fetch_and_install = lambda *a, **k: (False, "Not installing the new bridge: test.")
        self.runner._maybe_update()  # no PocketStop
        self.assertFalse(self.runner.updated)
        self.assertIn("Not installing", self.sender.delivered[-1])

    def test_the_hourly_check_finds_a_newer_bridge(self):
        original = pocket_update.published_version
        pocket_update.published_version = lambda *a, **k: "9.99.99"
        pocket_update.fetch_and_install = lambda *a, **k: (True, "Installed bridge v9.99.99 (was v1).")
        try:
            self.runner.update_every = 3600
            self.runner._next_update_check = 0
            with self.assertRaises(pocket_runner.PocketStop):
                self.runner._maybe_update()
            self.assertTrue(self.runner.updated)
        finally:
            pocket_update.published_version = original

    def test_the_hourly_check_waits_its_turn_and_ignores_older(self):
        original = pocket_update.published_version
        calls = []
        pocket_update.published_version = lambda *a, **k: calls.append(1) or "0.0.1"
        try:
            self.runner.update_every = 3600
            self.runner._next_update_check = 0
            self.runner._maybe_update()   # checks: older than ours, nothing to do
            self.runner._maybe_update()   # not due again for an hour
            self.assertEqual(len(calls), 1)
            self.assertFalse(self.runner.updated)
        finally:
            pocket_update.published_version = original

    def test_without_auto_update_the_notice_is_only_passed_on(self):
        self.runner.auto_update = False
        self.sw.Discord(enabled=True).post("pocket://corp", "BRIDGE UPDATE",
                                           ["🆕 **SOL-Watch 9.99.99 is available** (this bridge runs 1.0)."])
        self.assertIsNone(self.runner._update_wanted)


class BundledVersion(unittest.TestCase):
    """On the phone the bundled bridge is compiled (.pyc only), so its version comes from the code."""

    def setUp(self):
        self.source = (PYTHON_DIR / "sol_watch.py").read_text(encoding="utf-8")
        self.expected = pocket_update.version_of(self.source)

    def test_version_in_compiled_code(self):
        self.assertEqual(pocket_update.version_in_code(compile(self.source, "sol_watch.py", "exec")), self.expected)

    def test_sourceless_bundled_copy(self):
        import py_compile
        with tempfile.TemporaryDirectory() as folder:
            py_compile.compile(str(PYTHON_DIR / "sol_watch.py"), cfile=str(Path(folder) / "sol_watch.pyc"))
            saved = sys.path[:]
            sys.path[:] = [folder]
            try:
                importlib.invalidate_caches()
                self.assertEqual(pocket_update._bundled_version(None), self.expected)
                self.assertEqual(json.loads(pocket_update.describe(str(Path(folder) / "code")))["using"], self.expected)
            finally:
                sys.path[:] = saved
                importlib.invalidate_caches()

    def test_not_a_version(self):
        for text in (None, "", "null"):
            self.assertEqual(pocket_update.version_tuple(text), (0,))
        self.assertEqual(pocket_update.version_tuple("1.07.12"), (1, 7, 12))


if __name__ == "__main__":
    unittest.main()
