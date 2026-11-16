package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupTest {
    @Test fun onlyTheUsersChoicesGoInAndComeBackTyped() {
        val prefs = mapOf(
            "checks_wifi" to """{"IndustryWatcher":300}""", "quiet_on" to true, "quiet_from" to 1380, "history_days" to 30,
            "kind_chat" to "quiet", "last_poll_at" to 1794850000000L, "whats_new_seen" to "0.9.5", "new_options" to "{}",
        )
        val back = Backup.read(Backup.toJson(prefs, now = 0))
        assertEquals(mapOf("checks_wifi" to """{"IndustryWatcher":300}""", "history_days" to 30, "kind_chat" to "quiet",
            "quiet_from" to 1380, "quiet_on" to true), back.toSortedMap())
        assertFalse(Backup.isBackedUp("api_key"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFileFromSomethingElseIsRefused() { Backup.read("""{"app": "Other", "settings": {}}""") }
}
