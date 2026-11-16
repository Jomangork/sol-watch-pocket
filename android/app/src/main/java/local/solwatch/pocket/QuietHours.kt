package local.solwatch.pocket

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/**
 * Quiet hours: between two times of day, alerts arrive silently (no sound or vibration) but still show in the
 * shade and the Alerts list. Urgent alerts can be let through. The days are the nights it starts on.
 */
object QuietHours {
    const val EVERY = "every"
    const val WEEKNIGHTS = "weeknights"  // nights before a weekday: Sunday to Thursday
    const val WEEKENDS = "weekends"      // Friday and Saturday nights
    val DAYS = listOf(EVERY to "Every night", WEEKNIGHTS to "Weeknights", WEEKENDS to "Weekends")

    private fun startsOn(days: String, dayOfWeek: Int) = when (days) {
        WEEKNIGHTS -> dayOfWeek in listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY)
        WEEKENDS -> dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY
        else -> true
    }

    /** Whether it's quiet at this minute of the day (0-1439) on this day of the week (Calendar.SUNDAY…). */
    fun isQuiet(minute: Int, dayOfWeek: Int, from: Int, to: Int, days: String): Boolean {
        if (from == to) return false
        val yesterday = if (dayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else dayOfWeek - 1
        return if (from < to) minute in from until to && startsOn(days, dayOfWeek)
        else (minute >= from && startsOn(days, dayOfWeek)) || (minute < to && startsOn(days, yesterday))
    }

    fun active(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!Settings.quietOn(context)) return false
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return isQuiet(
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), c.get(Calendar.DAY_OF_WEEK),
            Settings.quietFrom(context), Settings.quietTo(context), Settings.quietDays(context),
        )
    }

    /** Whether this alert should arrive without a sound now. */
    fun silences(context: Context, priority: String) = active(context) && !(priority == "urgent" && Settings.quietUrgent(context))
}

/** Settings › My app: the Quiet hours card. */
@Composable
fun QuietHoursCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Settings.quietOn(context)) }
    var from by remember { mutableStateOf(Settings.quietFrom(context)) }
    var to by remember { mutableStateOf(Settings.quietTo(context)) }
    var days by remember { mutableStateOf(Settings.quietDays(context)) }
    var urgent by remember { mutableStateOf(Settings.quietUrgent(context)) }
    fun save() = Settings.setQuiet(context, on, from, to, days, urgent)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🌙 Quiet hours", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Alerts still arrive and show in the shade and the Alerts list, just without a sound or vibration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = { on = it; save() })
            }
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((label, value, set) in listOf(
                        Triple("From", from) { v: Int -> from = v },
                        Triple("Until", to) { v: Int -> to = v },
                    )) {
                        // The time on its own line and the buttons under it, so "11:00 PM" fits as well as "23:00".
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(TimeText.minutes(value), fontFamily = FontFamily.Monospace, fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { set((value + 1440 - 30) % 1440); save() }) { Text("−") }
                                OutlinedButton(onClick = { set((value + 30) % 1440); save() }) { Text("+") }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, name) in QuietHours.DAYS) {
                        FilterChip(selected = days == id, onClick = { days = id; save() }, label = { Text(name) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let urgent alerts through")
                        Text(
                            "🚨 incoming fleets, losses, escape capsules and loud My alerts still make a sound.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = urgent, onCheckedChange = { urgent = it; save() })
                }
                Text(
                    QuietHours.DAYS.first { it.first == days }.second + ", ${TimeText.minutes(from)} to ${TimeText.minutes(to)}: " +
                        (if (urgent) "only urgent alerts make a sound." else "nothing makes a sound, not even urgent alerts.") +
                        " The bridge keeps watching as usual.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Settings › My app: the clock for every time the app shows. */
@Composable
fun ClockCard() {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(Settings.clock(context)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clock", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, name) in TimeText.CHOICES) {
                    FilterChip(selected = clock == id, onClick = { clock = id; Settings.setClock(context, id) }, label = { Text(name) })
                }
            }
            Text(
                "For every time the app shows: alerts, the calendar, the widget, quiet hours and the status post.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
