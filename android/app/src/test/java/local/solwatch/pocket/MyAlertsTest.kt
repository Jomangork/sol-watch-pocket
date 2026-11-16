package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyAlertsTest {
    @Test fun rulesSurviveTheirJson() {
        val rules = listOf(
            MyAlertRule("a", "money_below", loud = true, value = 500.0),
            MyAlertRule("b", "stock_hours", on = false, value = 3.0, place = "tros", good = "steel"),
        )
        assertEquals(rules, MyAlerts.parse(MyAlerts.toJson(rules)))
        assertEquals(emptyList<MyAlertRule>(), MyAlerts.parse("not json"))
    }

    @Test fun moneySavedIn090DollarsBecomesMillions() {
        val old = """[{"id": "a", "kind": "money_below", "value": 2.0E10}, {"id": "b", "kind": "income_below", "value": 5.0E8},
                     {"id": "c", "kind": "stock_hours", "value": 3}]"""
        assertEquals(listOf(20000.0, 500.0, 3.0), MyAlerts.parse(old).map { it.value })
    }

    @Test fun linesInTheirUnits() {
        assertEquals("$500M", MyAlerts.lineText(MyAlertRule("a", "money_below", value = 500.0)))
        assertEquals("below zero", MyAlerts.lineText(MyAlertRule("a", "income_below", value = 0.0)))
        assertEquals("2.5 h", MyAlerts.lineText(MyAlertRule("a", "stock_hours", value = 2.5)))
        assertEquals("10%", MyAlerts.lineText(MyAlertRule("a", "market_stock", value = 10.0)))
    }

    @Test fun statusAndChoicesFromTheRunner() {
        val (status, choices) = MyAlerts.parseState(
            """{"status": {"a": {"now": "Now $612M", "met": true, "title": "Money below $700M"}},
               "choices": {"stock": [{"place": "tros", "good": "steel", "label": "Steel at Tros"}], "spaceport": []}}"""
        )
        assertEquals(MyAlertStatus("Now $612M", true, "Money below $700M"), status["a"])
        assertEquals("Steel at Tros", choices["stock"]!![0].label)
        assertTrue(choices["spaceport"]!!.isEmpty())
    }
}
