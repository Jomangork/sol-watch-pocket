"""Offline tests for the widget summary. Run: python3 -m unittest discover -s tests"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "python"))

import pocket_glance  # noqa: E402

ME = {"id": 22, "name": "Test Co", "money": 1000.0, "incomeRate": 24.0, "settledDay": 99.0, "gameDay": 100.0}
CLOCK = {"gameDay": 100.5, "clock": {"timeScale": 24}}


class Summary(unittest.TestCase):
    def test_money_runs_on_from_the_settlement(self):
        out = pocket_glance.summarize(ME, clock=CLOCK)
        self.assertAlmostEqual(out["money"], 1000 + 24 * 1.5)
        self.assertEqual(out["company"], "Test Co")
        self.assertEqual(out["timeScale"], 24)

    def test_research_progress_and_tech_name(self):
        research = {"research": {"techId": "armor", "startDay": 100.0, "completeDay": 102.0}, "investigations": []}
        out = pocket_glance.summarize(ME, research, clock=CLOCK, techs=[{"id": "armor", "name": "Composite Armor"}])
        self.assertEqual(out["research"]["name"], "Composite Armor")
        self.assertAlmostEqual(out["research"]["progress"], 0.25)

    def test_failure_investigation_is_named_after_its_launch(self):
        research = {"research": {"techId": "investigation:108", "startDay": 100, "completeDay": 101},
                    "investigations": [{"id": 108, "missionName": "Example-1"}]}
        out = pocket_glance.summarize(ME, research, clock=CLOCK)
        self.assertEqual(out["research"]["name"], "Failure investigation: Example-1")

    def test_trips_are_ours_and_still_flying_soonest_first(self):
        craft = {"missions": [
            {"companyId": 22, "name": "Late", "destinationId": "gan", "launchDay": 99, "arriveDay": 105},
            {"companyId": 22, "name": "Soon", "destinationId": "gan", "launchDay": 99, "arriveDay": 101},
            {"companyId": 22, "name": "Done", "destinationId": "gan", "launchDay": 90, "arriveDay": 95},
            {"companyId": 7, "name": "Theirs", "destinationId": "gan", "launchDay": 99, "arriveDay": 101},
            {"companyId": 22, "name": "Lost", "destinationId": "gan", "launchDay": 99, "arriveDay": 103, "outcome": "lost"},
        ]}
        out = pocket_glance.summarize(ME, craft=craft, clock=CLOCK, places=[{"id": "gan", "name": "Ganymede"}])
        self.assertEqual(out["trips"]["underWay"], 2)
        self.assertEqual([t["name"] for t in out["trips"]["next"]], ["Soon", "Late"])
        self.assertEqual(out["trips"]["next"][0]["to"], "Ganymede")

    def test_destinations_use_the_games_wrapped_tables_and_bodies(self):
        craft = {"missions": [
            {"companyId": 22, "name": "A", "destinationId": "ganymede", "launchDay": 99, "arriveDay": 101},
            {"companyId": 22, "name": "B", "destinationId": "LEO", "launchDay": 99, "arriveDay": 102},
            {"companyId": 22, "name": "C", "destinationId": "churyumov_gerasimenko", "launchDay": 99, "arriveDay": 103},
        ]}
        places = {"rulesVersion": 3, "places": [{"id": "LEO", "name": "LEO station"}]}
        bodies = {"rulesVersion": 3, "bodies": [{"id": "ganymede", "name": "Ganymede"}]}
        out = pocket_glance.summarize(ME, craft=craft, clock=CLOCK, places=places, bodies=bodies)
        self.assertEqual([t["to"] for t in out["trips"]["next"]], ["Ganymede", "LEO station", "Churyumov Gerasimenko"])

    def test_tech_names_from_the_wrapped_table(self):
        research = {"research": {"techId": "armor", "startDay": 100, "completeDay": 102}}
        techs = {"rulesVersion": 3, "techs": [{"id": "armor", "name": "Composite Armor"}]}
        self.assertEqual(pocket_glance.summarize(ME, research, clock=CLOCK, techs=techs)["research"]["name"], "Composite Armor")

    def test_nothing_without_me(self):
        self.assertIsNone(pocket_glance.summarize(None))


if __name__ == "__main__":
    unittest.main()
