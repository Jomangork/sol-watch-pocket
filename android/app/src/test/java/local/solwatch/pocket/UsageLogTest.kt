package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLogTest {
    private val min = 60_000L
    private fun s(minute: Long, battery: Int, charging: Boolean = false, bytes: Long = 0, metered: Boolean = false) =
        UsageLog.Sample(minute * min, battery, charging, bytes, metered, true)

    @Test fun drainCountsOnlyUnpluggedSpans() {
        val summary = UsageLog.summarize(listOf(s(0, 100, charging = true), s(10, 100), s(20, 99), s(30, 97), s(40, 97, charging = true)))
        assertEquals(20 * min, summary.unpluggedMs)
        assertEquals(3, summary.drained)
        assertEquals(9.0, summary.drainPerHour, 0.001)
    }

    @Test fun longGapsAreNotCountedAsDrain() {
        val summary = UsageLog.summarize(listOf(s(0, 90), s(120, 60), s(130, 59)))
        assertEquals(10 * min, summary.unpluggedMs)
        assertEquals(1, summary.drained)
    }

    @Test fun dataSplitsByNetworkAndSurvivesReboot() {
        val summary = UsageLog.summarize(listOf(s(0, 50, bytes = 1000), s(10, 50, bytes = 3000), s(20, 50, bytes = 500, metered = true)))
        assertEquals(2000, summary.wifiBytes)
        assertEquals(500, summary.mobileBytes)
    }

    @Test fun lineRoundTrips() {
        val sample = UsageLog.Sample(1_700_000_000_000, 87, true, 123456, true, false)
        assertEquals(sample, UsageLog.Sample.parse(sample.line()))
    }
}
