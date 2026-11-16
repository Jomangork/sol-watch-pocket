"""My alerts against made-up replies (no real game data)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "python"))

import pocket_myalerts  # noqa: E402

HOUR_MS = 3_600_000
NOW = 1_000 * HOUR_MS
# 24 game days to a real day: game day 500 is NOW.
CLOCK = {"clock": {"epochRealMs": NOW, "epochGameDay": 500.0, "timeScale": 24}}


def tapped(**extra):
    base = {
        "/clock": CLOCK,
        "/industry": {"industry": {"stock": [
            {"regionId": "tros", "goodId": "steel", "qty": 30.0, "rate": -10.0},  # 3 game days: 3 real hours
            {"regionId": "tros", "goodId": "water_ice", "qty": 5.0, "rate": 2.0},
        ]}},
        "/company": {"sustainment": [{"placeId": "vesta_yard", "crew": 12, "supplies": {"netDaysLeft": 4.0}}]},
        "/research": {"research": {"techId": "ion_drives", "startDay": 499.0, "completeDay": 500.5}},
        "/market": {"markets": [{"regionId": "guiana", "goodId": "steel", "stock": 80.0, "target": 1000.0}]},
        "/spaceports": [{"regionId": "guiana", "spaceport": "Kourou", "waitDays": 1, "waiting": 3}],
    }
    base.update(extra)
    return base


ME = {"money": 600e6, "incomeRate": -8e6, "settledDay": 500.0, "gameDay": 500.0}
NAMES = pocket_myalerts.Names({})


def check(rules, data=None, me=ME, fired=None):
    fired = set() if fired is None else fired
    alerts, status = pocket_myalerts.check(rules, data or tapped(), me, NOW, fired, NAMES)
    return alerts, status, fired


class MyAlerts(unittest.TestCase):
    def test_each_kind_reads_its_value(self):
        rules = [
            {"id": "1", "kind": "money_below", "value": 700e6},
            {"id": "2", "kind": "money_above", "value": 700e6},
            {"id": "3", "kind": "income_below", "value": 0},
            {"id": "4", "kind": "stock_hours", "value": 4, "place": "tros", "good": "steel"},
            {"id": "5", "kind": "supplies_hours", "value": 3, "place": "vesta_yard"},
            {"id": "6", "kind": "research_minutes", "value": 60},
            {"id": "7", "kind": "market_stock", "value": 10, "region": "guiana", "good": "steel"},
            {"id": "8", "kind": "spaceport_wait", "value": 2, "spaceport": "guiana"},
        ]
        alerts, status, _ = check(rules)
        met = {rule_id for rule_id, s in status.items() if s["met"]}
        self.assertEqual(met, {"1", "3", "4", "6", "7", "8"})  # not: supplies (4 h left, line 3 h), money above
        self.assertEqual(status["4"]["now"], "Now 30.0 held, runs out in 3.0 h")
        self.assertEqual(status["5"]["now"], "Now 4.0 h left")
        self.assertEqual(status["7"]["now"], "Now 8% of target (80 of 1,000)")
        self.assertEqual(status["3"]["title"], "Income turns negative")
        self.assertEqual(status["6"]["now"], "Ion Drives: 30 min left")
        self.assertEqual(len(alerts), 6)

    def test_fires_once_until_the_value_goes_back(self):
        rule = [{"id": "m", "kind": "money_below", "value": 700e6, "loud": True}]
        alerts, _, fired = check(rule)
        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0][1], "💰 Money below $700M")
        again, _, fired = check(rule, fired=fired)
        self.assertEqual(again, [])  # still below: not posted twice
        _, _, fired = check(rule, me=dict(ME, money=800e6), fired=fired)
        self.assertEqual(fired, set())  # back above the line
        third, _, _ = check(rule, fired=fired)
        self.assertEqual(len(third), 1)

    def test_switched_off_rules_show_their_value_but_never_post(self):
        alerts, status, _ = check([{"id": "m", "kind": "money_below", "value": 700e6, "on": False}])
        self.assertEqual(alerts, [])
        self.assertTrue(status["m"]["met"])

    def test_missing_data_waits_rather_than_firing(self):
        alerts, status, _ = check([{"id": "s", "kind": "stock_hours", "value": 4, "place": "tros", "good": "steel"}],
                                  data={"/clock": CLOCK})
        self.assertEqual(alerts, [])
        self.assertEqual(status["s"]["now"], "Waiting for the check's next read")

    def test_a_stock_that_isnt_falling_never_fires(self):
        alerts, status, _ = check([{"id": "w", "kind": "stock_hours", "value": 99, "place": "tros", "good": "water_ice"}])
        self.assertEqual(alerts, [])
        self.assertEqual(status["w"]["now"], "Now 5.0 held, not falling")

    def test_choices_for_the_editor(self):
        found = pocket_myalerts.choices(tapped(), NAMES)
        self.assertEqual([c["good"] for c in found["stock"]], ["steel", "water_ice"])
        self.assertEqual(found["supplies"][0]["place"], "vesta_yard")
        self.assertEqual(found["spaceport"], [{"spaceport": "guiana", "label": "Kourou"}])
        self.assertTrue(pocket_myalerts.needs_spaceports([{"kind": "spaceport_wait"}]))
        self.assertFalse(pocket_myalerts.needs_spaceports([{"kind": "spaceport_wait", "on": False}]))


if __name__ == "__main__":
    unittest.main()
