package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTest {
    @Test fun alertAndChatTextIsLeftOutOfTheBridgeLog() {
        val log = listOf(
            "[09:00:01] [Pocket] Started bridge 1.09.00.",
            "[09:00:05] 🚨⚔️ **[INCOMING FLEET]** 3× Corvette",
            "Arrives in 2h",  // the alert's second line
            "[09:00:06] 💬 Mira: hello",
            "[09:01:00] [Research] Read failed: timeout",
            "Traceback (most recent call last):",
        )
        val (kept, dropped) = Report.withoutAlertText(log)
        assertEquals(
            listOf("[09:00:01] [Pocket] Started bridge 1.09.00.", "[09:01:00] [Research] Read failed: timeout", "Traceback (most recent call last):"),
            kept,
        )
        assertEquals(3, dropped)
    }

    @Test fun alertTextIsLeftOutWithTheLogFilesOwnTimes() {
        val log = listOf(
            "2026-11-15 16:03:31 [Pocket] 28 checks on; off: none",
            "2026-11-15 16:05:02 🧪 **Research done: Ion drives** | next: Fusion",
            "• Steel at Tros: 3 days",
            "2026-11-15 16:06:00 [Update check] Up to date (v1.09.00).",
        )
        val (kept, dropped) = Report.withoutAlertText(log)
        assertEquals(listOf(log[0], log[3]), kept)
        assertEquals(2, dropped)
    }

    @Test fun theKeyIsNeverInTheReport() {
        assertEquals("key was [API key removed] here", Report.redact("key was sol_abcdef123456 here", "sol_abcdef123456"))
        assertEquals("nothing", Report.redact("nothing", null))
    }
}
