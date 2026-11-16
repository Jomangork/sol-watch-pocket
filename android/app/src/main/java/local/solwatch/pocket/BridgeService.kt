package local.solwatch.pocket

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

data class BridgeStatus(
    val running: Boolean = false,
    val message: String = "Stopped",
    val version: String = "",
    val polls: Int = 0,
    val failed: Int = 0,
    val lastPollAt: Long = 0,
    val metered: Boolean = false,
    val error: String = "",
    val keyRejected: Boolean = false,
    val outageSince: Long = 0,  // ms; when the game's server stopped answering, 0 while it answers
)

/**
 * Runs the bridge (pocket_runner + sol_watch.py) on its own thread inside a foreground service.
 * Holds a partial wake lock so polling continues with the screen off; the battery-optimisation
 * exemption (asked for on the Status screen) keeps the network open during Doze.
 */
class BridgeService : Service() {

    private var thread: Thread? = null
    @Volatile private var runner: PyObject? = null
    @Volatile private var stopRequested = false
    @Volatile private var restartRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var updateChecker: Thread? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("checks_") || key == "separate_mobile" || key == "new_checks") applyChecks()
        if (key == MyAlerts.SETTING) applyMyAlerts()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings.setEnabled(this, false)
            shutDown()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Starting…")
        if (intent?.action == ACTION_RESTART && thread?.isAlive == true) {
            // A new key: restart the bridge inside this service; the loop reads the key again.
            restartRequested = true
            runCatching { runner?.callAttr("stop") }
            return START_STICKY
        }
        start()
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val notification = Notifier.service(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.SERVICE_ID, notification)
        }
    }

    private fun start() {
        stopRequested = false
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolWatchPocket:bridge")
                .apply { acquire() }
        }
        if (networkCallback == null) {
            Settings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
            watchNetwork()
        }
        if (updateChecker?.isAlive != true) {
            // App updates (hourly on Wi-Fi, daily on mobile data) and the usage log, while SOL-Watch runs.
            updateChecker = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val metered = metered()
                        if (AppUpdater.due(this, metered)) runCatching { AppUpdater.check(this) }
                        runCatching { UsageLog.record(this, metered, _status.value.running) }  // temporary
                        Thread.sleep(UsageLog.EVERY_MS)
                    }
                } catch (_: InterruptedException) {
                }
            }, "app-updates").apply { isDaemon = true; start() }
        }
        // Never a second bridge thread: if the last one is still ending, it starts the bridge again itself.
        if (thread?.isAlive == true) {
            restartRequested = true
            return
        }
        thread = Thread({ runLoop() }, "sol-watch-bridge").apply { start() }
    }

    private fun runLoop() {
        val folder = File(filesDir, "bridge")
        while (!stopRequested) {
            val key = ApiKey.get(this)
            if (key == null) {
                publish(_status.value.copy(running = false, message = "No API key", error = "Add your API key on the Status screen."))
                break
            }
            try {
                val python = Python.getInstance()
                val checks = python.getModule("json").callAttr("loads", Settings.activeIntervalsJson(this, metered()))
                val created = python.getModule("pocket_runner").callAttr(
                    "Runner", folder.absolutePath, key, Sender(this), checks, TimeZone.getDefault().id,
                    Kwarg("code_dir", codeDir(this).absolutePath),
                    Kwarg("auto_update", Settings.autoUpdate(this)),
                    Kwarg("update_every", updateEvery(metered())),
                    Kwarg("log_sizes", Settings.logSizes(this).toTypedArray()),
                    Kwarg("my_alerts", MyAlerts.json(this)),
                    Kwarg("settings", python.getModule("pocket_runner").callAttr(
                        "settings_ini", Settings.fleetFlights(this), Settings.fleetWarningMinutes(this),
                        Kwarg("elevator_trips", Settings.elevatorTrips(this)),
                        Kwarg("new_options", NewOptions.json(this)),
                    )),
                )
                runner = created
                publish(_status.value.copy(running = true, message = "Starting the bridge…", error = "", metered = metered()))
                val outcome = created.callAttr("run").toString()
                if (stopRequested) break
                if (outcome == "updated") continue  // straight into the newly installed bridge
                if (restartRequested) {
                    restartRequested = false
                    continue
                }
                publish(_status.value.copy(running = false, message = "The bridge stopped by itself", error = "Restarting in 30 s"))
            } catch (error: Throwable) {
                Log.e(TAG, "Bridge failed", error)
                publish(_status.value.copy(running = false, message = "The bridge failed", error = error.message ?: error.toString()))
                // A just-installed bridge that fails goes back to the copy before it.
                val rolledBack = runCatching {
                    Python.getInstance().getModule("pocket_update").callAttr("roll_back", codeDir(this).absolutePath)?.toString()
                }.getOrNull()
                if (rolledBack != null && rolledBack != "None") {
                    Sender(this).deliver(
                        "corp", "BRIDGE UPDATE", "🆕 $rolledBack", "🆕 $rolledBack", "urgent",
                        "🆕 Bridge update rolled back", rolledBack, "", "",
                    )
                    continue
                }
            } finally {
                runner = null
            }
            // Like systemd's Restart=on-failure for the home bridge.
            for (i in 0 until 30) {
                if (stopRequested) break
                Thread.sleep(1000)
            }
        }
        publish(_status.value.copy(running = false, message = if (stopRequested) "Stopped" else _status.value.message))
    }

    private fun applyChecks() {
        val current = runner ?: return
        runCatching {
            current.callAttr("set_checks_json", Settings.activeIntervalsJson(this, metered()))
            current.callAttr("set_update_every", updateEvery(metered()))
        }
            .onFailure { Log.w(TAG, "Couldn't change the checks", it) }
        publish(_status.value.copy(metered = metered()))
    }

    /** The user's own alerts changed: the running bridge checks the new ones within a minute. */
    private fun applyMyAlerts() {
        val current = runner ?: return
        runCatching { current.callAttr("set_my_alerts_json", MyAlerts.json(this)) }
            .onFailure { Log.w(TAG, "Couldn't change My alerts", it) }
    }

    private fun metered(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = applyChecks()
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun shutDown() {
        stopRequested = true
        runCatching { runner?.callAttr("stop") }
        thread?.join(5000)
        if (thread?.isAlive != true) thread = null  // one still ending is kept, so start() won't add another
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        updateChecker?.interrupt()
        updateChecker = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        publish(_status.value.copy(running = false, message = "Stopped"))
        WidgetUpdater.request(this, now = true)
    }

    override fun onDestroy() {
        if (!stopRequested) shutDown()
        super.onDestroy()
    }

    /** Called from Python: sender.deliver(...) for each alert, sender.status(json) every 15 s. */
    class Sender(private val context: Context) {
        fun deliver(
            channel: String, header: String, text: String, plain: String, priority: String,
            title: String, body: String, gameTime: String, kind: String,
        ) {
            // The user's choice for this kind of alert on the Alert types page, if any.
            val mode = Settings.kindMode(context, kind)
            val level = AlertKinds.level(priority, mode)
            val topic = AlertTopics.of(kind, channel, priority, AlertKinds.headingOf(context, kind))
            AlertStore.get(context).add(channel, header, text, plain, level, kind, topic)
            when {
                text.startsWith("🔑") -> raiseWarning(context, "API key problem")
                // Only while a newer bridge waits for the user: with automatic updates on, the app installs it.
                text.startsWith("🆕") && isUpdateWaiting(text) && !Settings.autoUpdate(context) ->
                    raiseWarning(context, "Bridge update available")
            }
            if (mode != AlertKinds.OFF) {
                Notifier.show(context, channel, header, level, title, body, gameTime, AlertTopics.label(topic))
            }
            WidgetUpdater.request(context, now = level == "urgent")
        }

        /** "🆕 SOL-Watch 1.08.00 is available…" but not "Installed…", "rolled back" or "Up to date". */
        private fun isUpdateWaiting(text: String) = Regex("""\bis available\b""").containsMatchIn(text) &&
            !text.contains("installing it now")

        /** The game's /widget data (money, research, trips), for the home-screen widget. */
        fun widget(json: String) {
            GameGlance.store(context, json)
            WidgetUpdater.request(context, now = true)
        }

        /** The Calendar tab's entries, once a minute; also the clock for calendar reminders. */
        fun calendar(json: String) {
            CalendarStore.store(context, json)
            WidgetUpdater.request(context)
        }

        fun status(json: String) {
            val data = JSONObject(json)
            if (data.optInt("failed") > _status.value.failed && _status.value.polls > 0) {
                raiseWarning(context, "Some checks failed")
            }
            val rejected = data.optBoolean("keyRejected")
            Settings.prefs(context).edit().putBoolean(KEY_REJECTED, rejected).apply()
            val status = _status.value.copy(
                running = true,
                keyRejected = rejected,
                message = if (rejected) "Paused: the game refuses the API key" else "Running",
                version = data.optString("version"),
                polls = data.optInt("polls"),
                failed = data.optInt("failed"),
                lastPollAt = data.optLong("at") * 1000,
                outageSince = data.optLong("outageSince") * 1000,
                error = "",
            )
            publish(status)
            Settings.prefs(context).edit().putLong(LAST_POLL_AT, status.lastPollAt).apply()
            WidgetUpdater.request(context)
            val time = TimeText.time(status.lastPollAt)
            runCatching {
                NotificationManagerCompat.from(context).notify(
                    Notifier.SERVICE_ID,
                    Notifier.service(context, "Last check $time · ${status.polls} polls · v${status.version}"),
                )
            }
        }
    }

    companion object {
        private const val TAG = "SolWatchPocket"
        const val ACTION_STOP = "local.solwatch.pocket.STOP"
        const val ACTION_RESTART = "local.solwatch.pocket.RESTART"
        const val LAST_POLL_AT = "last_poll_at"
        const val KEY_REJECTED = "key_rejected"

        /** How often to look for updates on GitHub (the bridge here, the app in AppUpdater). */
        fun updateEvery(metered: Boolean) = if (metered) 24 * 3600 else 3600

        /** Where bridge updates downloaded from GitHub are kept. */
        fun codeDir(context: Context) = File(context.filesDir, "bridge_code")
        private val _status = MutableStateFlow(BridgeStatus())
        val status: StateFlow<BridgeStatus> = _status
        private fun publish(status: BridgeStatus) { _status.value = status }

        fun start(context: Context) {
            Settings.setEnabled(context, true)
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        /** Restarts the bridge with the current key, without stopping the service. */
        fun restart(context: Context) {
            Settings.setEnabled(context, true)
            context.startForegroundService(Intent(context, BridgeService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
