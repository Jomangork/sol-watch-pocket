"""Offline tests for pocket_runner (no network). Run: python3 -m unittest discover -s tests"""
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import pocket_runner  # noqa: E402


class FakeSender:
    def __init__(self):
        self.delivered = []
        self.statuses = []
        self.widgets = []

    def deliver(self, channel, header, text, plain, priority, title="", body="", game_time=""):
        self.delivered.append((channel, header, text, plain, priority, title, body, game_time))

    def status(self, text):
        self.statuses.append(text)

    def widget(self, text):
        self.widgets.append(text)


class PureFunctions(unittest.TestCase):
    def test_priority(self):
        self.assertEqual(pocket_runner.priority("🚨 Launch failed"), "urgent")
        self.assertEqual(pocket_runner.priority("🚨⚔️ INCOMING"), "urgent")
        self.assertEqual(pocket_runner.priority("  🆘 Capsule"), "urgent")
        self.assertEqual(pocket_runner.priority("💬 hi"), "chat")
        self.assertEqual(pocket_runner.priority("🏗️ Upgrade complete"), "normal")
        self.assertEqual(pocket_runner.priority("Sol-Watch v1 is online."), "normal")
        self.assertEqual(pocket_runner.priority("🔑 **The game is rejecting our API key.**"), "urgent")
        self.assertEqual(pocket_runner.priority("🔑 The game accepts our API key again."), "normal")

    def test_key_advice_is_reworded_for_the_app(self):
        line = ("🔑 Make a new key in the game, put it in `.env` as `SOL_BRIDGE_API_KEY` and restart the bridge.")
        self.assertEqual(pocket_runner.app_wording(line),
                         "🔑 Make a new key in the game, paste it on SOL-Watch Pocket's Status tab and tap Save key.")

    def test_channel_of(self):
        self.assertEqual(pocket_runner.channel_of("pocket://alliance"), "alliance")
        self.assertEqual(pocket_runner.channel_of(None), "corp")

    def test_check_reports_missing_names(self):
        fake = types.SimpleNamespace(VERSION="9.99.99", Discord=types.SimpleNamespace())
        missing = pocket_runner.check(fake)
        self.assertIn("Discord.post", missing)
        self.assertIn("main", missing)
        self.assertNotIn("VERSION", missing)


class WithBridge(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.folder = Path(self.tmp.name) / "pocket"
        self.sender = FakeSender()
        self.runner = pocket_runner.Runner(self.folder, "sol_test_not_a_real_key", self.sender)
        self.sw = self.runner.prepare()

    def tearDown(self):
        self.tmp.cleanup()

    def test_real_bridge_passes_check(self):
        self.assertEqual(pocket_runner.check(self.sw), [])

    def test_folder_settings_and_env(self):
        self.assertEqual(self.sw.HERE, self.folder)
        self.assertEqual(self.sw.LOG_DIR, self.folder / "logs")
        self.assertIn("check_minutes = 60", (self.folder / "settings.ini").read_text())
        self.assertEqual(os.environ["WEBHOOK_GENERAL"], "pocket://general")
        self.assertFalse((self.folder / ".env").exists())

    def test_post_hands_each_line_to_the_app(self):
        discord = self.sw.Discord(enabled=True)
        discord.post("pocket://general", "HAZARDS",
                     ["🚨 **Strike** at Vesta <t:1794503987:R>", "🏗️ Upgrade complete", "🏗️ Upgrade complete"])
        self.assertEqual(len(self.sender.delivered), 2)  # the repeat is merged (×2) by the bridge
        channel, header, text, plain, prio = self.sender.delivered[0][:5]
        self.assertEqual((channel, header, prio), ("general", "HAZARDS", "urgent"))
        self.assertNotIn("**", plain)
        self.assertNotIn("<t:", plain)
        self.assertEqual(self.sender.delivered[1][4], "normal")
        self.assertIn("×2", self.sender.delivered[1][2])

    def parts(self, text):
        return pocket_runner.notification_parts(self.sw, text)

    def test_title_is_the_headline_and_nothing_repeats(self):
        stamp = "`14 Nov 2032, 06:22 AM`"
        for line in (f"🧪 {stamp} **Research done: Ion drives** | next: Fusion",   # before bridge 1.07.13
                     f"🧪 **Research done: Ion drives** · {stamp} | next: Fusion"):  # 1.07.13 and later
            self.assertEqual(self.parts(line), ("🧪 Research done: Ion drives", "next: Fusion", "14 Nov 2032, 06:22 AM"))
        title, body, when = self.parts(f"📉 **Copper running out at Tros:** {stamp}\n• Site 1\n• Site 2")
        self.assertEqual((title, body, when), ("📉 Copper running out at Tros", "• Site 1\n• Site 2", "14 Nov 2032, 06:22 AM"))
        title, body, _ = self.parts(f"🚨⚔️ **Fleet incoming to Mars** from Pirates · {stamp} · 🕰️ <t:1793750000:t> (<t:1793750000:R>)")
        self.assertEqual(title, "🚨⚔️ Fleet incoming to Mars")
        self.assertTrue(body.startswith("from Pirates · 🕰️ "))
        self.assertNotIn("<t:", body)

    def test_title_without_a_headline(self):
        self.assertEqual(self.parts("🏗️ Upgrade complete"), ("🏗️ Upgrade complete", "", ""))
        long = "📰 A very long news item about something that happened. " + "More words follow here. " * 5
        title, body, _ = self.parts(long)
        self.assertEqual(title, "📰 A very long news item about something that happened")
        self.assertTrue(body.startswith("More words"))
        title, body, _ = self.parts("x" * 200)
        self.assertEqual((title, body), ("", "x" * 200))

    def test_post_moves_the_game_time_after_the_headline(self):
        self.sw.Discord(enabled=True).post("pocket://corp", "X", ["📭 `14 Nov 2032, 06:22 AM` **The research queue is empty.**"])
        text = self.sender.delivered[0][2]
        if hasattr(self.sw, "stamp_after_headline"):
            self.assertTrue(text.startswith("📭 **The research queue is empty.**"), text)
        self.assertEqual(self.sender.delivered[0][5], "📭 The research queue is empty.")

    def test_an_outage_logs_one_line_then_one_when_it_ends(self):
        written = []
        self.sw._pocket_original_log = lambda message, detail=None: written.append(message)
        self.runner._patch(self.sw)
        for path in ("/me", "/chat", "/craft"):
            self.sw.log(f"[API error] {path}: HTTP 530 (HTML error page)")
        self.sw.log("[Watcher error] something else")
        self.assertEqual(len(written), 2)
        self.assertIn("repeats hidden", written[0])
        self.assertTrue(self.runner.outage_since)
        self.assertEqual(self.runner.outage_hidden, 2)
        self.sw.read_ok()
        self.assertIn("answers again", written[-1])
        self.assertIn("2 more failed reads", written[-1])
        self.assertEqual(self.runner.outage_since, 0)

    def test_disabled_sender_delivers_nothing(self):
        self.sw.Discord(enabled=False).post("pocket://corp", "X", ["🚨 nothing"])
        self.assertEqual(self.sender.delivered, [])

    def test_a_failing_app_does_not_stop_the_bridge(self):
        def broken(*args):
            raise RuntimeError("UI gone")
        self.sender.deliver = broken
        self.sw.Discord(enabled=True).post("pocket://corp", "X", ["🚨 still fine"])

    def test_debug_output_is_off(self):
        self.sw.capture("battle", {"id": 1})
        self.assertFalse((self.folder / "captures").exists())

    def test_off_checks_never_poll(self):
        calls = []

        class Probe(self.sw.Watcher):
            def poll(self):
                calls.append(1)

        self.sw.Probe = Probe
        self.runner.set_checks({"Probe": 0})
        Probe({}, None).safe_poll()
        self.assertEqual(calls, [])
        self.runner.set_checks({"Probe": 60})
        Probe({}, None).safe_poll()
        self.assertEqual(calls, [1])

    def test_defaults_chat_every_5_min_and_derelicts_off(self):
        self.assertEqual(self.runner.checks["ChatWatcher"], 300)
        self.assertTrue(self.runner.is_off("DerelictWatcher"))
        self.assertFalse(self.runner.is_off("FleetMapWatcher"))

    def test_stop_raises_through_the_bridge(self):
        self.runner.stop()
        with self.assertRaises(pocket_runner.PocketStop):
            self.sw.heartbeat()

    def test_set_checks_changes_intervals_live(self):
        self.runner.set_checks({"IndustryWatcher": 900, "FactionsWatcher": 5, "DerelictWatcher": 60})
        self.assertEqual(self.sw.IndustryWatcher.interval, 900)
        self.assertEqual(self.sw.FactionsWatcher.interval, 10)  # clamped to the bridge's minimum
        self.assertEqual(self.sw.DerelictWatcher.interval, 600)  # derelicts: at least 10 minutes
        self.assertEqual(self.sw.AllianceWatcher.interval, 600)  # its own recommended value, not slow's

    def test_checks_survive_the_bridges_own_settings(self):
        self.runner.set_checks({"CraftWatcher": 300})
        self.sw.apply_poll_intervals()  # what the bridge runs at startup after reading settings.ini
        self.assertEqual(self.sw.CraftWatcher.interval, 300)

    def test_set_checks_json(self):
        import json
        result = json.loads(self.runner.set_checks_json('{"ChatWatcher": 60}'))
        self.assertEqual(result["ChatWatcher"], 60)

    def test_every_menu_check_exists_in_this_bridge(self):
        self.assertEqual(pocket_runner.unknown_checks(self.sw), [])

    def _fake_api(self, body):
        runner = self.runner

        class FakeApi:
            calls = []

            def get(self, path, reader=""):
                self.calls.append((path, reader))
                self.sw_heartbeat()  # the real client calls heartbeat while it reads
                return body

        api = FakeApi()
        api.sw_heartbeat = self.sw.heartbeat
        runner.api = api
        runner._widget_due = 0
        return api

    def test_widget_feed_hands_data_to_the_app(self):
        api = self._fake_api({"name": "Test Co", "money": 1.5, "incomeRate": 1, "settledDay": 10, "gameDay": 10})
        self.sw.heartbeat()
        self.assertEqual(api.calls, [("/me", "PocketWidget")])  # once: no recursion via heartbeat
        self.assertIn("Test Co", self.sender.widgets[0])
        self.sw.heartbeat()  # not due again until its interval has passed
        self.assertEqual(len(api.calls), 1)

    def test_widget_feed_off(self):
        api = self._fake_api({"company": "x"})
        self.runner.set_checks({"PocketWidget": 0})
        self.sw.heartbeat()
        self.assertEqual(api.calls, [])

    def test_bridge_reads_are_tapped_not_repeated(self):
        original = self.sw.SolApi._pocket_original_get
        self.sw.SolApi._pocket_original_get = lambda api, path, *a, **k: {"path": path}
        try:
            self.runner._patch(self.sw)
            api = self.sw.SolApi("k")
            api.get("/research", reader="ResearchWatcher")
            api.get("/fleets")
            self.assertEqual(self.runner.tapped, {"/research": {"path": "/research"}})
        finally:
            self.sw.SolApi._pocket_original_get = original
            self.runner._patch(self.sw)

    def test_bridge_client_is_captured(self):
        api = self.sw.SolApi("k")
        self.assertIs(self.runner.api, api)

    def test_a_new_run_stops_and_waits_for_the_old_one(self):
        import threading
        self.sw.main = lambda: [self.sw.heartbeat() or __import__("time").sleep(0.05) for _ in iter(int, 1)]
        old = self.runner
        first = threading.Thread(target=old.run)
        first.start()
        __import__("time").sleep(0.3)
        new = pocket_runner.Runner(self.folder, "k2", FakeSender())
        second = threading.Thread(target=new.run)
        second.start()
        first.join(5)
        self.assertFalse(first.is_alive())   # the old bridge was told to stop
        self.assertTrue(second.is_alive())   # and the new one took over
        new.stop()                           # stop reaches the running bridge
        second.join(5)
        self.assertFalse(second.is_alive())

    def test_stop_from_any_runner_reaches_the_running_bridge(self):
        import threading
        self.sw.main = lambda: [self.sw.heartbeat() or __import__("time").sleep(0.05) for _ in iter(int, 1)]
        running = threading.Thread(target=self.runner.run)
        running.start()
        __import__("time").sleep(0.3)
        pocket_runner.Runner(self.folder, "k3", FakeSender()).stop()
        running.join(5)
        self.assertFalse(running.is_alive())

    def test_preparing_twice_keeps_one_heartbeat_wrapper(self):
        first = self.sw._pocket_original_heartbeat
        pocket_runner.Runner(self.folder, "k", FakeSender()).prepare()
        self.assertIs(self.sw._pocket_original_heartbeat, first)


if __name__ == "__main__":
    unittest.main()
