package local.solwatch.pocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells the user about new versions of the app from its public GitHub releases (no token: the
 * repository is public). Checks hourly on Wi-Fi and daily on mobile data (BridgeService.updateEvery).
 * It doesn't install anything itself: tapping the notice opens the APK in the browser, which installs
 * it. (0.6.0 installed updates itself, and Play Protect then called the app harmful; the permission
 * to install apps was the likely reason, so 0.6.1 leaves installing to the browser.)
 */
object AppUpdater {
    const val REPO = "Jomangork/sol-watch-pocket"
    private const val RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val CHANNEL = "app_updates"
    private const val NOTIFY_ID = 2
    private const val TAG = "SolWatchUpdate"

    private const val LAST_CHECK = "app_update_last_check"
    private const val MESSAGE = "app_update_message"
    private const val AVAILABLE = "app_update_available"
    private const val AVAILABLE_URL = "app_update_available_url"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    // ---- pure: version comparison and picking a release (JVM-tested) ----

    fun versionParts(version: String): List<Int> =
        version.trim().trimStart('v', 'V').split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = versionParts(candidate)
        val b = versionParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest published (not draft) release with an APK, if it's newer than `current`. */
    fun pickRelease(json: String, current: String): Release? {
        val releases = JSONArray(json)
        val candidates = (0 until releases.length()).mapNotNull { i ->
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
            Release(release.optString("tag_name").trimStart('v', 'V'), apk.optString("browser_download_url"), apk.optLong("size"))
        }
        val newest = candidates.maxWithOrNull { x, y -> if (isNewer(x.version, y.version)) 1 else if (isNewer(y.version, x.version)) -1 else 0 }
        return newest?.takeIf { isNewer(it.version, current) }
    }

    // ---- state shown on the Status tab ----

    fun message(context: Context): String = Settings.prefs(context).getString(MESSAGE, null).orEmpty()

    /** A newer version and the link to its APK, if the last check found one. */
    fun available(context: Context): Pair<String, String>? {
        val prefs = Settings.prefs(context)
        val version = prefs.getString(AVAILABLE, null)?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) } ?: return null
        return version to (prefs.getString(AVAILABLE_URL, null) ?: return null)
    }

    private fun say(context: Context, text: String) = Settings.prefs(context).edit().putString(MESSAGE, text).apply()

    /** True when a check is due (hourly on Wi-Fi, daily on mobile data). */
    fun due(context: Context, metered: Boolean): Boolean =
        System.currentTimeMillis() - Settings.prefs(context).getLong(LAST_CHECK, 0) >=
            BridgeService.updateEvery(metered) * 1000L

    /** Checks GitHub and announces a newer version (once per version). Blocking: call off the main thread. */
    fun check(context: Context): String {
        val prefs = Settings.prefs(context)
        prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
        val release = runCatching { pickRelease(fetch(RELEASES), BuildConfig.VERSION_NAME) }
            .getOrElse { return "Couldn't check GitHub: ${it.message}".also { say(context, it) } }
        if (release == null) return "Up to date (${BuildConfig.VERSION_NAME}).".also { say(context, it) }
        val already = prefs.getString(AVAILABLE, null) == release.version
        prefs.edit().putString(AVAILABLE, release.version).putString(AVAILABLE_URL, release.apkUrl).apply()
        if (!already) {
            notify(context, release, "SOL-Watch Pocket ${release.version} is available",
                "Tap to download it (${release.size / 1_000_000} MB), then open the download to install.")
        }
        return "Version ${release.version} is available. Download it, then open the file to install.".also { say(context, it) }
    }

    /** Opens the APK link in the browser; the browser downloads it and Android installs it from there. */
    fun openDownload(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SOL-Watch-Pocket/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new version of SOL-Watch Pocket is available"
            }
        )
    }

    private fun notify(context: Context, release: Release, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 1, Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIFY_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Couldn't show the update notification", it) }
    }
}
