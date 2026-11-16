package local.solwatch.pocket

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Times of day across the app, in the clock the user picked (Settings › Status › Personalise): the phone's,
 * 24-hour or 12-hour. Loaded at start and when changed, so formatting needs no Context.
 */
object TimeText {
    const val PHONE = "phone"
    const val H24 = "24"
    const val H12 = "12"
    val CHOICES = listOf(PHONE to "Phone's setting", H24 to "24-hour", H12 to "12-hour")

    @Volatile var mode = PHONE
    @Volatile var phone24 = true

    fun load(context: Context) {
        mode = Settings.clock(context)
        phone24 = android.text.format.DateFormat.is24HourFormat(context)
    }

    val is24: Boolean get() = when (mode) { H24 -> true; H12 -> false; else -> phone24 }

    /** "21:05" or "9:05 PM". */
    fun time(ms: Long): String = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

    /** "15/11/26 21:05": the date as the phone writes it, then the time in the chosen clock. */
    fun dateTime(ms: Long): String =
        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(Date(ms)) + " " + time(ms)

    /** A time of day given as minutes after midnight (Quiet hours): "22:00" or "10:00 PM". */
    fun minutes(minutesOfDay: Int): String {
        val m = ((minutesOfDay % 1440) + 1440) % 1440
        return if (is24) "%02d:%02d".format(m / 60, m % 60)
        else "%d:%02d %s".format(((m / 60) + 11) % 12 + 1, m % 60, if (m < 720) "AM" else "PM")
    }

    fun minutesOfDay(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }
}
