package local.solwatch.pocket

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The user's choices: an interval (or off) for every check, on Wi-Fi and, optionally,
 * a separate set for mobile data. Stored as JSON in SharedPreferences.
 */
object Settings {
    const val WIFI = "wifi"
    const val MOBILE = "mobile"
    private const val FILE = "pocket_settings"
    private const val SEPARATE_MOBILE = "separate_mobile"
    private const val ENABLED = "bridge_enabled"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun intervals(context: Context, set: String): Map<String, Int> {
        val stored = prefs(context).getString("checks_$set", null)
        val defaults = Catalog.preset(if (set == MOBILE) "saver" else "recommended")
        if (stored == null) return defaults
        val json = JSONObject(stored)
        return defaults.mapValues { (id, seconds) -> if (json.has(id)) json.getInt(id) else seconds }
    }

    fun setInterval(context: Context, set: String, checkId: String, seconds: Int) {
        saveIntervals(context, set, intervals(context, set) + (checkId to seconds))
    }

    fun saveIntervals(context: Context, set: String, values: Map<String, Int>) {
        prefs(context).edit().putString("checks_$set", JSONObject(values).toString()).apply()
    }

    fun separateMobile(context: Context) = prefs(context).getBoolean(SEPARATE_MOBILE, false)

    fun setSeparateMobile(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(SEPARATE_MOBILE, on).apply()

    /** The set in force now: mobile only when the user keeps one and the network is metered. */
    fun activeSet(context: Context, metered: Boolean) =
        if (metered && separateMobile(context)) MOBILE else WIFI

    fun activeIntervalsJson(context: Context, metered: Boolean): String =
        JSONObject(intervals(context, activeSet(context, metered))).toString()

    // ---- look ----

    fun accent(context: Context) = prefs(context).getString("accent", "gold") ?: "gold"

    fun setAccent(context: Context, name: String) = prefs(context).edit().putString("accent", name).apply()

    /** "company" or "alliance": the game's logo share code (HQ › Logo), or null. */
    fun logoCode(context: Context, which: String): String? = prefs(context).getString("logo_$which", null)

    fun setLogoCode(context: Context, which: String, code: String?) =
        prefs(context).edit().apply { if (code.isNullOrBlank()) remove("logo_$which") else putString("logo_$which", code.trim()) }.apply()

    /** Install published bridge updates from GitHub by themselves (on by default, as the user asked). */
    fun autoUpdate(context: Context) = prefs(context).getBoolean("auto_update", true)

    fun setAutoUpdate(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto_update", on).apply()

    /** Every alert makes a sound, not only urgent ones and chat. */
    fun soundAll(context: Context) = prefs(context).getBoolean("sound_all", false)

    fun setSoundAll(context: Context, on: Boolean) = prefs(context).edit().putBoolean("sound_all", on).apply()

    // Posts about our own fleets' flights (bridge settings.ini [alerts]); read when the bridge starts.
    fun fleetFlights(context: Context) = prefs(context).getBoolean("fleet_flights", false)

    fun setFleetFlights(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fleet_flights", on).apply()

    // Posts about our space elevator bookings, the same way (bridge 1.07.15+).
    fun elevatorTrips(context: Context) = prefs(context).getBoolean("elevator_trips", false)

    fun setElevatorTrips(context: Context, on: Boolean) = prefs(context).edit().putBoolean("elevator_trips", on).apply()

    // The Storage card: the bridge's log sizes (bridge.log MB, old copies, errors.log MB, old copies), read when
    // the bridge starts, and how many days of alert history to keep.
    val BRIDGE_LOG_SIZES = listOf(5, 5, 2, 3)  // the bridge's own
    val PHONE_LOG_SIZES = listOf(2, 3, 1, 1)

    fun logSizes(context: Context): List<Int> =
        prefs(context).getString("log_sizes", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
            ?: BRIDGE_LOG_SIZES

    fun setLogSizes(context: Context, sizes: List<Int>) =
        prefs(context).edit().putString("log_sizes", sizes.joinToString(",")).apply()

    fun historyDays(context: Context) = prefs(context).getInt("history_days", 30)

    fun setHistoryDays(context: Context, days: Int) = prefs(context).edit().putInt("history_days", days).apply()

    fun fleetWarningMinutes(context: Context) = prefs(context).getInt("fleet_warning_minutes", 30)

    fun setFleetWarningMinutes(context: Context, minutes: Int) =
        prefs(context).edit().putInt("fleet_warning_minutes", minutes).apply()

    /** Loud, quiet or off for one kind of alert (AlertKinds), or "" for the bridge's own choice. */
    fun kindMode(context: Context, kind: String): String =
        if (kind.isBlank()) "" else prefs(context).getString("kind_$kind", "") ?: ""

    fun setKindMode(context: Context, kind: String, mode: String) = prefs(context).edit().apply {
        if (mode.isBlank()) remove("kind_$kind") else putString("kind_$kind", mode)
    }.apply()

    fun kindModes(context: Context): Map<String, String> = prefs(context).all
        .filterKeys { it.startsWith("kind_") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix("kind_") to it } }
        .toMap()

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ENABLED, on).apply()
}
