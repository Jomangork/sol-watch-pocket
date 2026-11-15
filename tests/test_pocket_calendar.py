"""Offline tests for pocket_calendar (made-up data, no network). Run: python3 -m unittest discover -s tests"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "python"))

import pocket_calendar  # noqa: E402

# Game day 1000 is real time 1,000,000,000 ms; 24 game days a real day, so one game day is one real hour.
CLOCK = {"gameDay": 1000, "clock": {"epochRealMs": 1_000_000_000, "epochGameDay": 1000, "timeScale": 24}}
NOW = 1_000_000_000
HOUR = 3_600_000


def entries(**tapped):
    return pocket_calendar.build(dict({"/clock": CLOCK}, **tapped), NOW)


def by_id(items):
    return {e["id"]: e for e in items}


class Calendar(unittest.TestCase):
    def test_nothing_without_the_clock(self):
        self.assertEqual(pocket_calendar.build({"/company": {"relayBurnInDay": 1000}}, NOW), [])

    def test_rule_changes_are_major_world_entries(self):
        got = by_id(entries(
            **{"/industry": {"power": {"rulesDay": 1010, "places": [
                {"placeId": "moon/a", "share": 1, "lifeShare": 1},
                {"placeId": "mars/b", "share": 0.85, "lifeShare": 1},
                {"placeId": "ceres_base", "share": 0.4, "lifeShare": 0.8}]}},
               "/company": {"relayBurnInDay": 1000, "modulePartsDay": 1005}}))
        power = got["power_rules"]
        self.assertEqual((power["kind"], power["major"], power["at"]), ("world", True, NOW + 10 * HOUR))
        self.assertEqual(power["details"], ["Moon/A: full power", "Mars/B: operations 85%",
                                            "Ceres Base: life support 80%, operations 40%"])
        self.assertEqual(got["relay_rules_start"]["at"], NOW + 15 * 24 * HOUR)  # 15 real days after the burn-in
        self.assertEqual(got["relay_rules_full"]["at"], NOW + 30 * 24 * HOUR)
        self.assertTrue(got["module_parts"]["major"])

    def test_ballot_and_council(self):
        got = by_id(entries(**{"/factions": {
            "ballot": {"year": 2033, "phase": "nominating", "votingDay": 1100, "closesDay": 1268, "nextOpensDay": 1345},
            "ballotMine": {"vote": None, "canVote": True},
            "government": {"nextSessionDay": 2000}}}))
        self.assertEqual(got["ballot_voting:2033"]["title"], "Ballot voting opens")
        self.assertEqual(got["ballot_closes:2033"]["sub"], "We haven't voted yet")
        self.assertTrue(got["ballot_closes:2033"]["major"])
        self.assertIn("council:2000", got)

    def test_elevators_being_built_have_no_date_and_come_last(self):
        items = entries(**{"/ueg": {"projects": [
            {"stationId": "elevator", "builtDay": 900},
            {"stationId": "pearson", "builtDay": None}]}, "/company": {"modulePartsDay": 1001}})
        self.assertEqual([e["id"] for e in items], ["module_parts", "elevator_built:pearson"])
        self.assertIsNone(items[-1]["at"])
        self.assertEqual(items[-1]["title"], "Pearson Elevator opens")

    def test_past_entries_drop_off(self):
        got = by_id(entries(**{"/company": {"workBills": [
            {"kind": "habitat", "ref": "a", "what": "level 3 of the habitat", "where": "at A", "readyDay": 999},
            {"kind": "habitat", "ref": "b", "what": "level 4 of the habitat", "where": "at B", "readyDay": 990}]}}))
        self.assertEqual([e["title"] for e in got.values()], ["Ready: Level 3 of the habitat"])  # 1 h ago stays

    def test_threats_and_ours(self):
        got = by_id(entries(**{
            "/defense": {"hazardIncidents": [{"id": 7, "hazardId": "flare", "startDay": 1003, "endDay": 1004,
                                              "size": "minor"}],
                         "threats": [{"id": "x", "name": "2033 AB", "arrivalDay": 1020, "outcome": None, "diameterM": 60},
                                     {"id": "y", "name": "old", "arrivalDay": 1020, "outcome": "miss"}]},
            "/factions": {"deals": [{"id": 5, "status": "open", "with": {"name": "Zvezda"}, "expiresDay": 1030},
                                    {"id": 6, "status": "accepted", "with": {"name": "WSA"}, "expiresDay": None}]},
            "/industry": {"elevatorTrips": [{"id": 9, "elevatorId": "elevator", "arriveDay": 1002}]},
            "/me": {"id": 22},
            "/craft": {"missions": [{"id": 1, "companyId": 22, "name": "Kestrel", "destinationId": "LEO", "arriveDay": 1001},
                                    {"id": 2, "companyId": 5, "name": "Theirs", "arriveDay": 1001}]}}))
        self.assertEqual(got["hazard:7"]["sub"], "Minor · Hazard forecast · lasts 1 h")
        self.assertEqual(got["hazard:7"]["kind"], "threat")
        self.assertIn("asteroid:x", got)
        self.assertNotIn("asteroid:y", got)
        self.assertEqual(got["deal:5"]["sub"], "With Zvezda")
        self.assertNotIn("deal:6", got)
        self.assertEqual(got["elevator_trip:9"]["sub"], "Artsutanov Elevator")
        self.assertEqual(got["craft:1:1001"]["title"], "Kestrel arrives")
        self.assertNotIn("craft:2:1001", got)


if __name__ == "__main__":
    unittest.main()
