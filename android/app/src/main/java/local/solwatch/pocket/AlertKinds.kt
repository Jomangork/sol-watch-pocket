package local.solwatch.pocket

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.io.File

data class AlertKind(val id: String, val about: String, val priority: String)
data class KindGroup(val name: String, val kinds: List<AlertKind>)

/**
 * The bridge's alert kinds (bridge 1.08.00+), which the app writes to alert_kinds.json each time it starts the
 * bridge, and the user's choice for each: the bridge's own (""), loud, quiet or off. Off still keeps the alert
 * in the Alerts list; it only skips the notification.
 */
object AlertKinds {
    const val LOUD = "loud"
    const val QUIET = "quiet"
    const val OFF = "off"
    val CHOICES = listOf("", LOUD, QUIET, OFF)

    fun parse(json: String): List<KindGroup> {
        val groups = JSONArray(json)
        return (0 until groups.length()).map { i ->
            val group = groups.getJSONObject(i)
            val kinds = group.getJSONArray("kinds")
            KindGroup(group.getString("group"), (0 until kinds.length()).map { j ->
                val kind = kinds.getJSONObject(j)
                AlertKind(kind.getString("id"), kind.optString("about"), kind.optString("priority", "normal"))
            })
        }
    }

    fun load(context: Context): List<KindGroup> =
        runCatching { parse(File(File(context.filesDir, "bridge"), "alert_kinds.json").readText()) }.getOrDefault(emptyList())

    /** The priority an alert is shown with: the bridge's, unless the user chose loud or quiet for its kind. */
    fun level(priority: String, mode: String): String = when (mode) {
        LOUD -> "urgent"
        QUIET -> "normal"
        else -> priority
    }

    /** What the bridge does with a kind by itself, in the app's words. */
    fun defaultLabel(priority: String) = when (priority) {
        "urgent" -> "loud"
        "chat" -> "chat sound"
        else -> "quiet"
    }

    fun choiceLabel(mode: String, priority: String) = when (mode) {
        LOUD -> "Loud"
        QUIET -> "Quiet"
        OFF -> "Off"
        else -> "Default (${defaultLabel(priority)})"
    }
}

/** A menu button with the four choices for one kind of alert. */
@Composable
fun KindChoice(kind: String, priority: String, mode: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(AlertKinds.choiceLabel(mode, priority), fontWeight = if (mode.isBlank()) FontWeight.Normal else FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in AlertKinds.CHOICES) {
                DropdownMenuItem(
                    text = { Text(AlertKinds.choiceLabel(choice, priority)) },
                    onClick = { open = false; if (choice != mode) onChange(choice) },
                )
            }
        }
    }
}

/** The Alert types page, opened from the Checks tab: loud, quiet or off for each kind of alert. */
@Composable
fun AlertTypesScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val groups = remember { AlertKinds.load(context) }
    var modes by remember { mutableStateOf(Settings.kindModes(context)) }
    val counts = remember {
        AlertStore.get(context).countsByKind(System.currentTimeMillis() - Settings.historyDays(context) * 24L * 3600 * 1000)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var changedOnly by rememberSaveable { mutableStateOf(false) }

    fun shown(kind: AlertKind) = (!changedOnly || !modes[kind.id].isNullOrBlank()) &&
        (query.isBlank() || kind.about.contains(query.trim(), ignoreCase = true))

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Checks") }
                Text("Alert types", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Text(
                "Loud alerts make a sound, quiet ones don't (unless Sound for every alert is on), and Off skips the " +
                    "notification but still keeps the alert in the Alerts list. Changes apply to the next alert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    "The list appears once Sol Watch has run with bridge 1.08.00 or newer. Start it on the Status tab.",
                    Modifier.padding(vertical = 8.dp),
                )
            }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search alert types") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !changedOnly, onClick = { changedOnly = false }, label = { Text("All") })
                FilterChip(
                    selected = changedOnly, onClick = { changedOnly = true },
                    label = { Text("Changed (${modes.count { it.value.isNotBlank() }})") },
                )
            }
        }
        for (group in groups) {
            val kinds = group.kinds.filter(::shown)
            if (kinds.isEmpty()) continue
            item(key = "group-${group.name}") {
                Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(kinds, key = { it.id }) { kind ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.about)
                            val seen = counts[kind.id] ?: 0
                            if (seen > 0) {
                                Text(
                                    "$seen in the last ${Settings.historyDays(context)} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        KindChoice(kind.id, kind.priority, modes[kind.id].orEmpty()) { mode ->
                            Settings.setKindMode(context, kind.id, mode)
                            modes = Settings.kindModes(context)
                        }
                    }
                }
            }
        }
    }
}
