package local.solwatch.pocket

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        if (CrashReport.isCrashProcess()) return  // the report screen needs nothing else
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        runCatching { Settings.moveOldFast(this) }
        TimeText.load(this)
        Notifier.createChannels(this)
        AppUpdater.createChannel(this)
    }
}

/** The checks menu, read once from pocket_checks.py so the app and the wrapper share one list. */
object Catalog {
    val checks: List<Check> by lazy {
        val json = JSONObject(Python.getInstance().getModule("pocket_checks").callAttr("catalog_json").toString())
        val rows = json.getJSONArray("checks")
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Check(
                id = row.getString("id"),
                group = row.getString("group"),
                title = row.getString("title"),
                alerts = row.getString("alerts"),
                recommended = row.getInt("recommended"),
                saver = row.getInt("saver"),
                fast = row.getInt("fast"),
                kib = row.getDouble("kib"),
                measured = row.getBoolean("measured"),
                feeds = row.getString("feeds"),
                min = row.getInt("min"),
                minWhy = row.optString("min_why"),
            )
        }
    }

    val groups: List<String> by lazy { checks.map { it.group }.distinct() }

    fun preset(name: String): Map<String, Int> = checks.associate {
        it.id to when (name) {
            "saver" -> it.saver
            "fast" -> it.fast
            else -> it.recommended
        }
    }
}
