package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTest {
    private val bundled = listOf("0.9.0", "0.9.1", "0.9.2", "0.9.3", "0.9.4", "0.10.0")

    @Test fun everyVersionSinceTheLastOneOpenedNewestFirst() {
        assertEquals(listOf("0.9.4", "0.9.3"), WhatsNew.between(bundled, "0.9.2", "0.9.4"))
        assertEquals(listOf("0.10.0"), WhatsNew.between(bundled, "0.9.4", "0.10.0"))  // 10 after 9, not before
        assertEquals(emptyList<String>(), WhatsNew.between(bundled, "0.9.4", "0.9.4"))
    }
}
