package local.solwatch.pocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app itself from its public GitHub releases (no token: the repository is public).
 * Checks hourly on Wi-Fi and daily on mobile data (BridgeService.updateEvery), downloads only on
 * Wi-Fi, and installs only an APK that is this app, newer, and signed with the same key.
 */
object AppUpdater {
    const val REPO = "Jomangork/sol-watch-pocket"
    private const val RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val CHANNEL = "app_updates"
    private const val NOTIFY_ID = 2
    const val ACTION_INSTALL = "local.solwatch.pocket.INSTALL_UPDATE"
    private const val TAG = "SolWatchUpdate"

    private const val LAST_CHECK = "app_update_last_check"
    private const val MESSAGE = "app_update_message"
    private const val READY_FILE = "app_update_ready_file"
    private const val READY_VERSION = "app_update_ready_version"
    private const val AVAILABLE = "app_update_available"

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
    fun readyVersion(context: Context): String? = Settings.prefs(context).getString(READY_VERSION, null)
        ?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) && readyFile(context)?.exists() == true }
    private fun readyFile(context: Context) = Settings.prefs(context).getString(READY_FILE, null)?.let(::File)
    private fun say(context: Context, text: String) = Settings.prefs(context).edit().putString(MESSAGE, text).apply()

    /** True when a check is due (hourly on Wi-Fi, daily on mobile data). */
    fun due(context: Context, metered: Boolean): Boolean =
        System.currentTimeMillis() - Settings.prefs(context).getLong(LAST_CHECK, 0) >=
            BridgeService.updateEvery(metered) * 1000L

    /**
     * Checks GitHub; downloads a newer APK when allowed (always for a manual check, only on Wi-Fi
     * for an automatic one) and announces it. Blocking: call off the main thread. Returns a message.
     */
    fun check(context: Context, metered: Boolean, manual: Boolean): String {
        Settings.prefs(context).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
        val release = runCatching { pickRelease(fetch(RELEASES), BuildConfig.VERSION_NAME) }
            .getOrElse { return "Couldn't check GitHub: ${it.message}".also { say(context, it) } }
        if (release == null) return "Up to date (${BuildConfig.VERSION_NAME}).".also { say(context, it) }
        Settings.prefs(context).edit().putString(AVAILABLE, release.version).apply()
        if (readyVersion(context) == release.version) return "Version ${release.version} is ready to install.".also { say(context, it) }
        if (metered && !manual) {
            notify(context, "SOL-Watch Pocket ${release.version} is available",
                "${release.size / 1_000_000} MB. Open the app's Status tab to download it, or wait for Wi-Fi.")
            return "Version ${release.version} is available (${release.size / 1_000_000} MB); it downloads on Wi-Fi.".also { say(context, it) }
        }
        val file = runCatching { download(context, release) }
            .getOrElse { return "Couldn't download ${release.version}: ${it.message}".also { say(context, it) } }
        verify(context, file)?.let { problem ->
            file.delete()
            return "Not installing ${release.version}: $problem.".also { say(context, it) }
        }
        Settings.prefs(context).edit().putString(READY_FILE, file.path).putString(READY_VERSION, release.version).apply()
        notify(context, "SOL-Watch Pocket ${release.version} is ready", "Tap to install it.")
        return "Version ${release.version} is ready to install.".also { say(context, it) }
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

    private fun download(context: Context, release: Release): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }  // keep only the one being fetched
        val file = File(folder, "sol-watch-pocket-${release.version}.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true  // GitHub hands assets out from another https host
            setRequestProperty("User-Agent", "SOL-Watch-Pocket/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        if (release.size > 0 && file.length() != release.size) {
            file.delete()
            error("the download was incomplete")
        }
        return file
    }

    /** Null when the APK is this app, newer, and signed with the same key; else the reason it isn't. */
    fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "it isn't a readable app package"
        if (archive.packageName != context.packageName) return "it's a different app (${archive.packageName})"
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) return "it isn't newer than the installed version"
        val theirs = signers(archive.signingInfo?.apkContentsSigners)
        val ours = signers(installed.signingInfo?.apkContentsSigners)
        if (theirs.isEmpty() || theirs != ours) return "it's signed with a different key"
        return null
    }

    private fun signers(list: Array<android.content.pm.Signature>?): Set<String> =
        list.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()

    /**
     * Installs the downloaded update. Android asks the user to allow this app to install updates
     * the first time, and may ask to confirm each install; from Android 12 it can skip asking.
     * Call while the app is on screen. Returns a message for the Status tab.
     */
    fun install(context: Context): String {
        val file = readyFile(context)?.takeIf { it.exists() } ?: return "No update is downloaded yet."
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow SOL-Watch Pocket to install updates, then tap Install again."
        }
        verify(context, file)?.let { return "Not installing: $it." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("sol-watch-pocket.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,  // the installer adds its result
            )
            session.commit(result.intentSender)
        }
        return "Installing… the app restarts when it's done.".also { say(context, it) }
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new version of SOL-Watch Pocket is ready to install"
            }
        )
    }

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

/** Receives the installer's answer: asks the user to confirm when Android wants that, else records the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = Settings.prefs(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.edit().putString("app_update_message", "Updated.").apply()
            else -> prefs.edit().putString(
                "app_update_message",
                "The update didn't install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"),
            ).apply()
        }
    }
}
