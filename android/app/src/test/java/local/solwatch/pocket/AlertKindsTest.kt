package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertKindsTest {
    @Test fun parsesTheBridgesGroups() {
        val groups = AlertKinds.parse(
            """[{"group": "Research", "kinds": [{"id": "research_done", "about": "Research finished", "priority": "normal"}]},
               {"group": "Fleets and battles", "kinds": [{"id": "incoming_fleet", "about": "A fleet is heading for us", "priority": "urgent"}]}]"""
        )
        assertEquals(listOf("Research", "Fleets and battles"), groups.map { it.name })
        assertEquals(AlertKind("incoming_fleet", "A fleet is heading for us", "urgent"), groups[1].kinds[0])
    }

    @Test fun theUsersChoiceDecidesTheLevel() {
        assertEquals("urgent", AlertKinds.level("normal", AlertKinds.LOUD))
        assertEquals("normal", AlertKinds.level("urgent", AlertKinds.QUIET))
        assertEquals("normal", AlertKinds.level("chat", AlertKinds.QUIET))
        assertEquals("chat", AlertKinds.level("chat", ""))
        assertEquals("urgent", AlertKinds.level("urgent", AlertKinds.OFF))  // off skips the notification, not the history
    }

    @Test fun labels() {
        assertEquals("Default (loud)", AlertKinds.choiceLabel("", "urgent"))
        assertEquals("Default (quiet)", AlertKinds.choiceLabel("", "info"))
        assertEquals("Off", AlertKinds.choiceLabel(AlertKinds.OFF, "normal"))
    }
}
