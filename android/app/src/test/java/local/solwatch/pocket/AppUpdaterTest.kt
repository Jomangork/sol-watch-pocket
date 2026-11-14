package local.solwatch.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test fun versionsCompareByNumber() {
        assertTrue(AppUpdater.isNewer("0.6.0", "0.5.0"))
        assertTrue(AppUpdater.isNewer("v0.10.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("1.0", "0.9.9"))
        assertFalse(AppUpdater.isNewer("0.5.0", "0.5.0"))
        assertFalse(AppUpdater.isNewer("0.4.9", "0.5.0"))
        assertFalse(AppUpdater.isNewer("v0.5", "0.5.0"))
    }

    private val feed = """
        [
          {"tag_name": "v0.7.0", "draft": true, "assets": [{"name": "a.apk", "browser_download_url": "https://x/draft.apk", "size": 1}]},
          {"tag_name": "v0.6.1", "draft": false, "prerelease": true,
           "assets": [{"name": "notes.txt", "browser_download_url": "https://x/n", "size": 3},
                      {"name": "sol-watch-pocket-0.6.1.apk", "browser_download_url": "https://x/0.6.1.apk", "size": 16000000}]},
          {"tag_name": "v0.6.2", "draft": false, "assets": []},
          {"tag_name": "v0.5.0", "draft": false, "assets": [{"name": "old.apk", "browser_download_url": "https://x/old.apk", "size": 2}]}
        ]
    """.trimIndent()

    @Test fun picksTheNewestPublishedReleaseWithAnApk() {
        val release = AppUpdater.pickRelease(feed, "0.6.0")
        assertEquals("0.6.1", release?.version)            // not the draft, not the one without an APK
        assertEquals("https://x/0.6.1.apk", release?.apkUrl)
        assertEquals(16_000_000L, release?.size)
    }

    @Test fun nothingWhenAlreadyCurrent() {
        assertNull(AppUpdater.pickRelease(feed, "0.6.1"))
        assertNull(AppUpdater.pickRelease("[]", "0.6.0"))
    }
}
