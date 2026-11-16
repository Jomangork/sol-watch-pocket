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
        val mins = Catalog.checks.associate { it.id to it.min }
        return defaults.mapValues { (id, seconds) ->
            if (json.has(id)) snapInterval(json.getInt(id), mins[id] ?: 0, shorter = set != MOBILE) else seconds
        }
    }

    /** The last mix of intervals that wasn't a preset, for the Custom chip (null before there is one). */
    fun customIntervals(context: Context, set: String): Map<String, Int>? {
        val json = prefs(context).getString("custom_$set", null)?.let { JSONObject(it) } ?: return null
        return json.keys().asSequence().associateWith { json.getInt(it) }
    }

    fun saveCustomIntervals(context: Context, set: String, values: Map<String, Int>) {
        prefs(context).edit().putString("custom_$set", JSONObject(values).toString()).apply()
    }

    fun setInterval(context: Context, set: String, checkId: String, seconds: Int) {
        saveIntervals(context, set, intervals(context, set) + (checkId to seconds))
    }

    fun saveIntervals(context: Context, set: String, values: Map<String, Int>) {
        prefs(context).edit().putString("checks_$set", JSONObject(values).toString()).apply()
    }

    /** The Fast preset as it was in 0.8.6-0.9.2, before its big downloads were slowed. */
    val OLD_FAST = mapOf(
        "FleetMapWatcher" to 15, "FleetsWatcher" to 30, "CraftWatcher" to 15, "DefenseWatcher" to 60, "NewsWatcher" to 60,
        "SegmentsWatcher" to 60, "CompanyWatcher" to 60, "IndustryWatcher" to 60, "ResearchWatcher" to 60, "MiningWatcher" to 60,
        "OreBodiesWatcher" to 60, "PocketWidget" to 60, "AccountWatcher" to 300, "MarketWatcher" to 3600, "RecordsWatcher" to 300,
        "ContractsWatcher" to 60, "ContractHistoryWatcher" to 60, "DiplomacyWatcher" to 60, "FactionsWatcher" to 60,
        "PadGoalWatcher" to 60, "AllianceWatcher" to 60, "DiscoveriesWatcher" to 60, "ChatWatcher" to 15, "DerelictWatcher" to 600,
        "PlaceWatcher" to 600, "BridgeUpdateWatcher" to 600, "ApiSpecWatcher" to 600, "GameUpdateWatcher" to 300,
    )

    /** Once (0.9.3): a set still exactly on the old Fast preset moves to the new one; a set changed by hand stays. */
    fun moveOldFast(context: Context) {
        if (prefs(context).getBoolean("fast_093", false)) return
        for (set in listOf(WIFI, MOBILE)) {
            val stored = prefs(context).getString("checks_$set", null)?.let { JSONObject(it) } ?: continue
            if (OLD_FAST.all { (id, seconds) -> stored.optInt(id, -1) == seconds }) saveIntervals(context, set, Catalog.preset("fast"))
        }
        prefs(context).edit().putBoolean("fast_093", true).apply()
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

    /** Clock for times shown in the app: TimeText.PHONE, H24 or H12. */
    fun clock(context: Context) = prefs(context).getString("clock", TimeText.PHONE) ?: TimeText.PHONE

    fun setClock(context: Context, mode: String) {
        prefs(context).edit().putString("clock", mode).apply()
        TimeText.load(context)
    }

    // Quiet hours: alerts make no sound between two times of day (minutes after midnight), on the chosen days.
    fun quietOn(context: Context) = prefs(context).getBoolean("quiet_on", false)
    fun quietFrom(context: Context) = prefs(context).getInt("quiet_from", 22 * 60)
    fun quietTo(context: Context) = prefs(context).getInt("quiet_to", 7 * 60)
    fun quietDays(context: Context) = prefs(context).getString("quiet_days", QuietHours.EVERY) ?: QuietHours.EVERY
    fun quietUrgent(context: Context) = prefs(context).getBoolean("quiet_urgent", true)

    fun setQuiet(context: Context, on: Boolean, from: Int, to: Int, days: String, urgent: Boolean) =
        prefs(context).edit().putBoolean("quiet_on", on).putInt("quiet_from", from).putInt("quiet_to", to)
            .putString("quiet_days", days).putBoolean("quiet_urgent", urgent).apply()

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ENABLED, on).apply()
}
