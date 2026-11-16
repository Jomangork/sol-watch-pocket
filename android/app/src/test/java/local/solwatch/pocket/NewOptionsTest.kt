package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class NewOptionsTest {
    @Test fun optionsFromTheRunner() {
        val options = NewOptions.parse(
            """[{"name": "rail_link_posts", "label": "Rail link posts", "about": "Our rail links.", "default": false}]"""
        )
        assertEquals(listOf(NewOptions.Option("rail_link_posts", "Rail link posts", "Our rail links.", false)), options)
        assertEquals(emptyList<NewOptions.Option>(), NewOptions.parse("oops"))
    }
}
