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
)

/** Every alert the bridge handed over, kept for Settings.historyDays (30 by default) in the app's private database. */
class AlertStore private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "alerts.db", null, 1) {

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so screens know to reload. */
    val version: StateFlow<Int> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, " +
                "channel TEXT NOT NULL, header TEXT NOT NULL, text TEXT NOT NULL, plain TEXT NOT NULL, " +
                "priority TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX alerts_at ON alerts(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(channel: String, header: String, text: String, plain: String, priority: String): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("alerts", null, ContentValues().apply {
            put("at", now)
            put("channel", channel)
            put("header", header)
            put("text", text)
            put("plain", plain)
            put("priority", priority)
        })
        if (id % 200 == 0L) prune()
        _version.value++
        return id
    }

    /** Newest first. filter: "all", "urgent", "chat", or a channel name; query: words in the text. */
    fun recent(filter: String, query: String, limit: Int = 300): List<Alert> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            "all" -> Unit
            "urgent", "chat" -> { where += "priority = ?"; args += filter }
            else -> { where += "channel = ? AND priority = 'normal'"; args += filter }
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
                        it.getString(4), it.getString(5), it.getString(6)
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
