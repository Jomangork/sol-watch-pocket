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

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ENABLED, on).apply()
}
