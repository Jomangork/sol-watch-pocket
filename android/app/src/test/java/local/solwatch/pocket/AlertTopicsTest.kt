package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertTopicsTest {
    @Test fun labelledAlertsGoToTheirTabAndFilter() {
        assertEquals("Contracts" to "Alliance jobs", AlertTopics.of("alliance_job", "corp", "normal"))
        assertEquals("Fleets" to "Threats", AlertTopics.of("incoming_fleet", "corp", "urgent"))
        assertEquals("Chat" to "Global", AlertTopics.of("chat", "corp", "chat"))
        assertEquals("Chat" to "Alliance", AlertTopics.of("alliance_chat", "corp", "chat"))
    }

    @Test fun aKindTheTableDoesntKnowGoesByItsHeading() {
        assertEquals("Contracts" to "", AlertTopics.of("contract_new_thing", "corp", "normal", "Contracts"))
        assertEquals("Sol" to "", AlertTopics.of("something_new", "corp", "normal", null))
    }

    @Test fun unlabelledAlertsGoByTheirOldChannel() {
        assertEquals("Contracts" to "", AlertTopics.of("", "general", "normal"))
        assertEquals("Chat" to "Alliance", AlertTopics.of("", "alliance", "chat"))
        assertEquals("Company" to "", AlertTopics.of("", "corp", "urgent"))
    }

    @Test fun everyKindIsInOneFilterOnly() {
        val all = AlertTopics.TABS.flatMap { tab -> tab.subs.flatMap { it.kinds } }
        assertEquals(all.size, all.toSet().size)
        assertEquals(134, all.size)
    }

    @Test fun labels() {
        assertEquals("Contracts · Alliance jobs", AlertTopics.label("Contracts" to "Alliance jobs"))
        assertEquals("Sol", AlertTopics.label("Sol" to ""))
    }
}
