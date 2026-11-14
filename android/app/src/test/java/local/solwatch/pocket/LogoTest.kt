package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LogoTest {
    // A made-up 16×16 logo in the game's share-code format (HQ › Logo): a cyan ring, an orange
    // dot at the top and a white dot lower left, on a transparent background.
    private val ring = "SOL1:EAP/////ij1Pw/cAAAAAAAIAAAAAAzMzMAAAAAAzMzMzAAAAAzMAADMwAAAzAAAAADMAAzMAAAAAMzADMAAAAAADMAMwAAAAAAMwAzAAAAAAAzADMAAAAAADMAMzAAAAADMwADMAAAAAMwAAAxMAADMwAAAAMzMzMwAAAAADMzMwAAAAAAAAAAAAAAA"

    @Test fun decodesSizeAndPalette() {
        val logo = Logo.decode(ring)
        assertNotNull(logo)
        assertEquals(16, logo!!.size)
        assertEquals(0, logo.pixel(0, 0))                    // transparent corner
        assertEquals(0xffff8a3d.toInt(), logo.pixel(11, 0))   // the orange dot at the top
        assertEquals(0xff4fc3f7.toInt(), logo.pixel(7, 1))    // the cyan ring
        assertEquals(0xffffffff.toInt(), logo.pixel(4, 12))   // the white dot
    }

    @Test fun rejectsOtherText() {
        assertNull(Logo.decode(""))
        assertNull(Logo.decode("hello"))
        assertNull(Logo.decode("SOL1:AA"))
        assertNull(Logo.decode(null))
    }
}
