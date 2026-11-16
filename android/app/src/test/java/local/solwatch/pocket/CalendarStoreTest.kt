package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarStoreTest {
    private val hour = 3_600_000L
    private val json = """[
        {"id": "research:x", "day": 1001, "at": ${2 * hour}, "kind": "ours", "title": "Research done: X", "sub": "Research",
         "major": false, "about": "", "source": "Research check", "details": []},
        {"id": "power_rules", "day": 1300, "at": ${300 * hour}, "kind": "world", "title": "Power rules start", "sub": "",
         "major": true, "about": "Life support first.", "source": "Industry check", "details": ["LEO: full power"]},
        {"id": "elevator_built:pearson", "day": null, "at": null, "kind": "world", "title": "Pearson Elevator opens",
         "sub": "Under construction", "major": false, "about": "", "source": "", "details": []}
    ]"""

    @Test fun parsesEntriesWithAndWithoutDates() {
        val entries = CalendarStore.parse(json)
        assertEquals(listOf("research:x", "power_rules", "elevator_built:pearson"), entries.map { it.id })
        assertEquals(listOf("LEO: full power"), entries[1].details)
        assertNull(entries[2].at)
        assertNull(entries[2].gameDay)
    }

    @Test fun nextMajorIsTheSoonestMajorStillAhead() {
        val entries = CalendarStore.parse(json)
        assertEquals("power_rules", CalendarStore.nextMajor(entries, 0)?.id)
        assertNull(CalendarStore.nextMajor(entries, 301 * hour))
    }

    @Test fun countdowns() {
        assertEquals("12m", CalendarStore.countdown(12 * 60_000L, 0))
        assertEquals("2h 40m", CalendarStore.countdown(160 * 60_000L, 0))
        assertEquals("13d 20h", CalendarStore.countdown((13 * 24 + 20) * hour, 0))
        assertEquals("now", CalendarStore.countdown(0, 5))
    }

    @Test fun remindersFireOnceInTheirWindow() {
        val entries = CalendarStore.parse(json)
        val remind = { id: String -> if (id == "power_rules") 24 * 60 else 0 }
        val fireAt = 300 * hour - 24 * hour
        assertTrue(CalendarStore.due(entries, remind, emptySet(), fireAt - 60_000).isEmpty())  // not yet
        val due = CalendarStore.due(entries, remind, emptySet(), fireAt + 60_000)
        assertEquals(listOf("power_rules" to 24 * 60), due.map { it.first.id to it.second })
        val fired = setOf(CalendarStore.firedKey(due[0].first, 24 * 60))
        assertTrue(CalendarStore.due(entries, remind, fired, fireAt + 120_000).isEmpty())  // only once
        assertTrue(CalendarStore.due(entries, remind, emptySet(), fireAt + 2 * hour).isEmpty())  // over an hour late
    }

    @Test fun dataUsePerCheckBiggestFirst() {
        val (since, use) = UsageLog.parseDataUse(
            """{"since": 100, "paths": {"/me": [2048, 2], "/industry": [6291456, 3], "/reference/techs": [1024, 1]},
               "names": {"/me": "Widget data", "/industry": "Industry", "/reference": "Reference tables"}}"""
        )
        assertEquals(100_000L, since)
        assertEquals(listOf("Industry", "Widget data", "Reference tables"), use.map { it.name })
        assertEquals("Industry: 99% · 6 MB · 3 reads, 2048.0 KB each", UsageLog.describeDataUse(use)[0])
    }

    @Test fun pathsWithOneNameAreAddedUp() {
        val (_, use) = UsageLog.parseDataUse(
            """{"since": 1, "paths": {"/industry": [1000, 2], "/industry/flows": [500, 2], "": [100, 1]},
               "names": {"/industry": "Industry", "/industry/flows": "Industry"}}"""
        )
        assertEquals(listOf("Industry", "API index (the bridge's key check)"), use.map { it.name })
        assertEquals(1500L to 4L, use[0].bytes to use[0].reads)
        assertEquals("1 KB", UsageLog.sizeLabel(1500))
    }

    @Test fun connectionsPerHost() {
        val hosts = UsageLog.parseConnections("""{"connections": {"solthegame.com": [120, 1000]}}""")
        assertEquals(listOf("solthegame.com: 120 new connections for 1,000 requests (12%)"), UsageLog.describeConnections(hosts))
    }

    @Test fun sameDayConstructionSharesOneRow() {
        fun entry(id: String) = CalEntry(id, hour, 1000.0, "ours", id, "", false, "", "", emptyList())
        val day = listOf(entry("research:x"), entry("build:a"), entry("deal:1"), entry("build:b"), entry("build:c"))
        assertEquals(
            listOf(listOf("research:x"), listOf("build:a", "build:b", "build:c"), listOf("deal:1")),
            CalendarStore.agendaRows(day).map { row -> row.map { it.id } },
        )
        val one = listOf(entry("build:a"), entry("deal:1"))
        assertEquals(listOf(listOf("build:a"), listOf("deal:1")), CalendarStore.agendaRows(one).map { row -> row.map { it.id } })
    }
}
