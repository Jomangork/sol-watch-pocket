package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateTest {
    private fun check(id: String, recommended: Int, kib: Double, min: Int = 10) =
        Check(id, "Threats", id, "", recommended, recommended, recommended, kib, true, "", min)

    @Test fun offCostsNothing() {
        val c = check("CraftWatcher", 60, 1.5)
        assertEquals(0.0, mebibytesPerMonth(c, 0), 0.0)
        assertEquals(0.0, readsPerMinute(listOf(c), mapOf("CraftWatcher" to 0)), 0.0)
    }

    @Test fun industryCountsTwoReadsAndItsSize() {
        val c = check("IndustryWatcher", 300, 69.0)
        assertEquals(24.0, readsPerMinute(listOf(c), mapOf("IndustryWatcher" to 5)), 0.001)
        // 30 days at every 5 min = 8,640 reads × 69 KiB ≈ 582 MiB
        assertEquals(582.2, mebibytesPerMonth(c, 300), 0.5)
    }

    @Test fun missingIntervalsUseRecommended() {
        val c = check("FleetMapWatcher", 60, 2.0)
        assertEquals(1.0, readsPerMinute(listOf(c), emptyMap()), 0.0)
    }

    @Test fun choicesRespectMinimumButKeepOff() {
        val c = check("DerelictWatcher", 0, 200.0, min = 600)
        val choices = choicesFor(c)
        assertTrue(0 in choices)
        assertTrue(choices.filter { it != 0 }.all { it >= 600 })
    }

    @Test fun labels() {
        assertEquals("Off", intervalLabel(0))
        assertEquals("30 s", intervalLabel(30))
        assertEquals("5 min", intervalLabel(300))
        assertEquals("6 h", intervalLabel(21600))
        assertEquals("1.5 GB", dataLabel(1536.0))
        assertEquals("under 1 MB", dataLabel(0.4))
    }

    @Test fun boldRunsAndBackticks() {
        assertEquals(
            listOf("🚨 " to false, "Strike" to true, " at 3 Oct" to false),
            boldRuns("🚨 **Strike** at `3 Oct`"),
        )
    }

    @Test fun discordTimesAreWrittenOut() {
        val out = readableTimes("lands <t:100:f> (<t:100:R>), ends <t:200:R>") { "T$it" }
        assertEquals("lands T100, ends T200", out)
    }
}
