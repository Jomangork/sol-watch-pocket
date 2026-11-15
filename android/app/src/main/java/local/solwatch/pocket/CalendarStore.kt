package local.solwatch.pocket

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import org.json.JSONArray
import java.io.File
import kotlin.math.roundToLong

/** One Calendar tab entry, from pocket_calendar.build. [at] is real time in ms, or null for "no date yet". */
data class CalEntry(
    val id: String,
    val at: Long?,
    val gameDay: Double?,
    val kind: String,  // "world", "ours" or "threat"
    val title: String,
    val sub: String,
    val major: Boolean,
    val about: String,
    val source: String,
    val details: List<String>,
)

/**
 * The calendar the bridge's wrapper hands over every minute (files/calendar.json), the reminders the user
 * set on its entries (minutes before, per entry id), and firing those reminders. The wrapper's minute
 * is also the reminders' clock, so no alarm permission is needed: SOL-Watch runs anyway.
 */
object CalendarStore {
    private const val FILE = "calendar.json"
    private const val FIRED = "cal_fired"
    val REMIND_CHOICES = listOf(0, 60, 24 * 60, 3 * 24 * 60)  // minutes before; 0 = off
    private const val LATE_MS = 3600_000L  // a reminder missed while stopped still fires up to an hour late

    // ---- pure (JVM-tested) ----

    fun parse(json: String): List<CalEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val details = o.optJSONArray("details")
            CalEntry(
                id = o.optString("id"),
                at = if (o.isNull("at")) null else o.optLong("at"),
                gameDay = if (o.isNull("day")) null else o.optDouble("day"),
                kind = o.optString("kind", "ours"),
                title = o.optString("title"),
                sub = o.optString("sub"),
                major = o.optBoolean("major"),
                about = o.optString("about"),
                source = o.optString("source"),
                details = (0 until (details?.length() ?: 0)).map { details!!.optString(it) },
            )
        }
    }

    /** Construction finishing (the company's work bills). */
    fun isBuild(entry: CalEntry) = entry.id.startsWith("build:")

    /**
     * One day's entries as agenda rows: one entry each, except that two or more constructions share one row,
     * where the first of them was. On some days most of the calendar is construction.
     */
    fun agendaRows(day: List<CalEntry>): List<List<CalEntry>> {
        val builds = day.filter(::isBuild)
        if (builds.size < 2) return day.map { listOf(it) }
        val first = builds.first()
        return day.mapNotNull { entry ->
            when {
                entry === first -> builds
                isBuild(entry) -> null
                else -> listOf(entry)
            }
        }
    }

    /** The next major entry still ahead, for the Calendar's top card and the widget. */
    fun nextMajor(entries: List<CalEntry>, now: Long): CalEntry? =
        entries.filter { it.major && it.at != null && it.at > now }.minByOrNull { it.at!! }

    /** "13d 20h", "2h 40m", "12m"; "now" once it's past. */
    fun countdown(at: Long, now: Long): String {
        val minutes = ((at - now) / 60_000.0).roundToLong()
        return when {
            minutes <= 0 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 48 * 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
        }
    }

    /** Reminders due now: (entry, minutes before) whose time has come, not yet fired, and not over an hour late. */
    fun due(entries: List<CalEntry>, remind: (String) -> Int, fired: Set<String>, now: Long): List<Pair<CalEntry, Int>> =
        entries.mapNotNull { e ->
            val at = e.at ?: return@mapNotNull null
            val minutes = remind(e.id).takeIf { it > 0 } ?: return@mapNotNull null
            val fireAt = at - minutes * 60_000L
            if (now >= fireAt && now <= fireAt + LATE_MS && now < at && firedKey(e, minutes) !in fired) e to minutes else null
        }

    fun firedKey(e: CalEntry, minutes: Int) = "${e.id}@${e.at}@$minutes"

    fun remindLabel(minutes: Int) = when (minutes) {
        0 -> "Off"
        60 -> "1 hour before"
        24 * 60 -> "1 day before"
        else -> "${minutes / 1440} days before"
    }

    // ---- on the phone ----

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<CalEntry> =
        runCatching { parse(file(context).readText()) }.getOrDefault(emptyList())

    fun updatedAt(context: Context): Long = file(context).lastModified()

    fun remindMinutes(context: Context, id: String) = Settings.prefs(context).getInt("cal_remind_$id", 0)

    fun setRemindMinutes(context: Context, id: String, minutes: Int) =
        Settings.prefs(context).edit().putInt("cal_remind_$id", minutes).apply()

    /** From the wrapper, once a minute: keep the entries, then fire any reminders that are due. */
    @Synchronized
    fun store(context: Context, json: String) {
        val entries = runCatching { parse(json) }.getOrNull() ?: return
        file(context).writeText(json)
        val prefs = Settings.prefs(context)
        val fired = prefs.getStringSet(FIRED, emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val due = due(entries, { remindMinutes(context, it) }, fired, now)
        for ((entry, minutes) in due) {
            Notifier.show(
                context, "calendar", "CALENDAR", "reminder",
                "${entry.title} in ${countdown(entry.at!!, now)}",
                listOf(entry.sub, DateText.full(entry.at)).filter { it.isNotBlank() }.joinToString("\n"), "",
            )
        }
        // Keep only keys of entries still listed, so the set doesn't grow forever.
        val live = entries.flatMap { e -> REMIND_CHOICES.map { firedKey(e, it) } }.toSet()
        prefs.edit().putStringSet(FIRED, (fired + due.map { firedKey(it.first, it.second) }).intersect(live)).apply()
    }

    /** Opens the phone's calendar app with the entry filled in; the user saves it there (no calendar permission). */
    fun addToPhoneCalendar(context: Context, entry: CalEntry): Boolean {
        val at = entry.at ?: return false
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 30 * 60_000L)
            .putExtra(CalendarContract.Events.TITLE, "SOL: ${entry.title}")
            .putExtra(CalendarContract.Events.DESCRIPTION, listOf(entry.sub, entry.about).filter { it.isNotBlank() }.joinToString("\n\n"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/** Real dates as the calendar shows them, in the phone's own format and time zone. */
object DateText {
    fun full(ms: Long): String =
        java.text.SimpleDateFormat("EEE d MMM yyyy, ", java.util.Locale.getDefault()).format(java.util.Date(ms)) + time(ms)

    fun day(ms: Long): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))

    fun time(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
}
