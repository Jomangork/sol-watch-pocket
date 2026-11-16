package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewOptionsTest {
    @Test fun switchesAndChecksFromTheRunner() {
        val offered = NewOptions.parse(
            """{"version": "1.10.00",
                "options": [{"name": "rail_link_posts", "label": "Rail link posts", "about": "Our rail links.", "default": false,
                             "check": "IndustryWatcher", "checkTitle": "Industry"}],
                "checks": [{"id": "RailNetworkWatcher", "title": "Rail network", "about": "", "path": "/rails", "start": 1800}]}"""
        )
        assertEquals("1.10.00", offered.version)
        assertEquals(NewOptions.Option("rail_link_posts", "Rail link posts", "Our rail links.", false, "IndustryWatcher", "Industry"),
            offered.options[0])
        assertEquals(NewOptions.NewCheck("RailNetworkWatcher", "Rail network", "", "/rails", 1800), offered.checks[0])
        assertTrue(NewOptions.parse("oops").isEmpty)
    }
}
