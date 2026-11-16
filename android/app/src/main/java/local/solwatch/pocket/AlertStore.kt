package local.solwatch.pocket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Alert(
    val id: Long,
    val at: Long,
    val channel: String,
    val header: String,
    val text: String,
    val plain: String,
    val priority: String,
    val kind: String = "",  // the bridge's alert kind (bridge 1.08.00+), or "" for an unlabelled alert
    val tab: String = "",   // the Alerts tab's topic and the filter beneath it (AlertTopics)
    val sub: String = "",
)

/** Every alert the bridge handed over, kept for Settings.historyDays (30 by default) in the app's private database. */
class AlertStore private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "alerts.db", null, 4) {

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so screens know to reload. */
    val version: StateFlow<Int> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, " +
                "channel TEXT NOT NULL, header TEXT NOT NULL, text TEXT NOT NULL, plain TEXT NOT NULL, " +
                "priority TEXT NOT NULL, kind TEXT NOT NULL DEFAULT '', tab TEXT NOT NULL DEFAULT '', " +
                "sub TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX alerts_at ON alerts(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE alerts ADD COLUMN kind TEXT NOT NULL DEFAULT ''")
        if (oldVersion in 3..3) {  // 0.9.6: kinds that came under "New" (rail links) move to their own filter
            val newer = db.rawQuery("SELECT DISTINCT kind, channel, priority FROM alerts WHERE sub = 'New'", null).use {
                buildList { while (it.moveToNext()) add(Triple(it.getString(0), it.getString(1), it.getString(2))) }
            }
            for ((kind, channel, priority) in newer) {
                val (tab, sub) = AlertTopics.of(kind, channel, priority, AlertKinds.headingOf(context, kind))
                db.update("alerts", ContentValues().apply { put("tab", tab); put("sub", sub) },
                    "kind = ? AND sub = 'New'", arrayOf(kind))
            }
        }
        if (oldVersion < 3) {  // 0.8.4: alerts already kept are sorted into the topic tabs too
            db.execSQL("ALTER TABLE alerts ADD COLUMN tab TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE alerts ADD COLUMN sub TEXT NOT NULL DEFAULT ''")
            val sorted = db.rawQuery("SELECT DISTINCT kind, channel, priority FROM alerts", null).use {
                buildList { while (it.moveToNext()) add(Triple(it.getString(0), it.getString(1), it.getString(2))) }
            }
            for ((kind, channel, priority) in sorted) {
                val (tab, sub) = AlertTopics.of(kind, channel, priority, AlertKinds.headingOf(context, kind))
                db.update("alerts", ContentValues().apply { put("tab", tab); put("sub", sub) },
                    "kind = ? AND channel = ? AND priority = ?", arrayOf(kind, channel, priority))
            }
        }
    }

    fun add(
        channel: String, header: String, text: String, plain: String, priority: String, kind: String = "",
        topic: Pair<String, String> = AlertTopics.of(kind, channel, priority),
    ): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("alerts", null, ContentValues().apply {
            put("at", now)
            put("channel", channel)
            put("header", header)
            put("text", text)
            put("plain", plain)
            put("priority", priority)
            put("kind", kind)
            put("tab", topic.first)
            put("sub", topic.second)
        })
        if (id % 200 == 0L) prune()
        _version.value++
        return id
    }

    /**
     * Newest first. filter: "all", "urgent", "chat", or an Alerts tab (AlertTopics), with sub one of the filters
     * beneath it ("" for all of them); query: words in the text.
     */
    fun recent(filter: String, query: String, limit: Int = 300, sub: String = ""): List<Alert> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            "all" -> Unit
            "urgent", "chat" -> { where += "priority = ?"; args += filter }
            else -> {
                where += "tab = ?"; args += filter
                if (sub.isNotBlank()) { where += "sub = ?"; args += sub }
            }
        }
        if (query.isNotBlank()) { where += "plain LIKE ?"; args += "%${query.trim()}%" }
        val cursor = readableDatabase.query(
            "alerts", null, where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray(),
            null, null, "at DESC, id DESC", limit.toString()
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(
                    Alert(
                        it.getLong(0), it.getLong(1), it.getString(2), it.getString(3),
                        it.getString(4), it.getString(5), it.getString(6), it.getString(7),
                        it.getString(8), it.getString(9),
                    )
                )
            }
        }
    }

    /** How many alerts (of one priority, or all) arrived since the given time. */
    fun countSince(sinceMs: Long, priority: String? = null): Int {
        val where = "at >= ?" + if (priority != null) " AND priority = ?" else ""
        val args = listOfNotNull(sinceMs.toString(), priority).toTypedArray()
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM alerts WHERE $where", args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Whether any alert is filed under this tab and filter (the "New" filter shows only when it has some). */
    fun hasSub(tab: String, sub: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM alerts WHERE tab = ? AND sub = ? LIMIT 1", arrayOf(tab, sub)).use { it.moveToFirst() }

    /** How many alerts of each kind arrived since the given time. */
    fun countsByKind(sinceMs: Long): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT kind, COUNT(*) FROM alerts WHERE at >= ? AND kind != '' GROUP BY kind", arrayOf(sinceMs.toString())
        ).use { buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) } }

    /** Drops alerts older than the history length the user picked. */
    fun prune() {
        val keepMs = Settings.historyDays(context) * 24L * 3600 * 1000
        writableDatabase.delete("alerts", "at < ?", arrayOf((System.currentTimeMillis() - keepMs).toString()))
        _version.value++
    }

    fun latest(priority: String): Alert? = recent(priority, "", limit = 1).firstOrNull()

    companion object {
        @Volatile private var instance: AlertStore? = null
        fun get(context: Context): AlertStore =
            instance ?: synchronized(this) { instance ?: AlertStore(context.applicationContext).also { instance = it } }
    }
}
