package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QuietHoursTest {
    private val from = 22 * 60
    private val to = 7 * 60

    @Test fun overnightSpan() {
        assertTrue(QuietHours.isQuiet(23 * 60, Calendar.MONDAY, from, to, QuietHours.EVERY))
        assertTrue(QuietHours.isQuiet(6 * 60 + 59, Calendar.TUESDAY, from, to, QuietHours.EVERY))
        assertFalse(QuietHours.isQuiet(7 * 60, Calendar.TUESDAY, from, to, QuietHours.EVERY))
        assertFalse(QuietHours.isQuiet(21 * 60 + 59, Calendar.MONDAY, from, to, QuietHours.EVERY))
    }

    @Test fun daysAreTheNightsItStartsOn() {
        // Friday night belongs to the weekend, including Saturday early morning; Sunday night is a weeknight.
        assertFalse(QuietHours.isQuiet(23 * 60, Calendar.FRIDAY, from, to, QuietHours.WEEKNIGHTS))
        assertTrue(QuietHours.isQuiet(3 * 60, Calendar.SATURDAY, from, to, QuietHours.WEEKENDS))
        assertTrue(QuietHours.isQuiet(23 * 60, Calendar.SUNDAY, from, to, QuietHours.WEEKNIGHTS))
        assertTrue(QuietHours.isQuiet(3 * 60, Calendar.MONDAY, from, to, QuietHours.WEEKNIGHTS))
    }

    @Test fun sameDaySpanAndEmptySpan() {
        assertTrue(QuietHours.isQuiet(14 * 60, Calendar.MONDAY, 13 * 60, 15 * 60, QuietHours.EVERY))
        assertFalse(QuietHours.isQuiet(14 * 60, Calendar.MONDAY, 14 * 60, 14 * 60, QuietHours.EVERY))
    }

    @Test fun clockFormats() {
        TimeText.mode = TimeText.H24
        assertEquals("22:00", TimeText.minutes(22 * 60))
        assertEquals("07:30", TimeText.minutes(7 * 60 + 30))
        TimeText.mode = TimeText.H12
        assertEquals("10:00 PM", TimeText.minutes(22 * 60))
        assertEquals("12:15 AM", TimeText.minutes(15))
        assertEquals("12:00 PM", TimeText.minutes(12 * 60))
        TimeText.mode = TimeText.PHONE
    }
}
