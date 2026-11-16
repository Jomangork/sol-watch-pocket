package local.solwatch.pocket

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

/**
 * What a newer bridge has that the app doesn't know yet (the runner lists it in bridge_options.json): on/off switches,
 * with the check whose reads they use, and whole new checks with their own reads. The user's choices are temporary:
 * an app update clears them, since by then the app either knows these properly or still offers them afresh.
 */
object NewOptions {
    data class Option(val name: String, val label: String, val about: String, val default: Boolean,
                      val check: String = "", val checkTitle: String = "")
    data class NewCheck(val id: String, val title: String, val about: String, val path: String, val start: Int)
    data class Offered(val version: String, val options: List<Option>, val checks: List<NewCheck>) {
        val isEmpty get() = options.isEmpty() && checks.isEmpty()
    }

    private const val OPTIONS = "new_options"
    private const val CHECKS = "new_checks"
    private const val FOR_APP = "new_options_app"
    const val CHECK_MIN = 300  // pocket_checks.NEW_CHECK_MIN: never faster until an app update has measured it

    fun parse(json: String): Offered = runCatching {
        val o = JSONObject(json)
        val options = o.optJSONArray("options")
        val checks = o.optJSONArray("checks")
        Offered(
            o.optString("version"),
            (0 until (options?.length() ?: 0)).map { i ->
                val x = options!!.getJSONObject(i)
                Option(x.getString("name"), x.optString("label"), x.optString("about"), x.optBoolean("default"),
                    x.optString("check"), x.optString("checkTitle"))
            },
            (0 until (checks?.length() ?: 0)).map { i ->
                val x = checks!!.getJSONObject(i)
                NewCheck(x.getString("id"), x.optString("title"), x.optString("about"), x.optString("path"), x.optInt("start", 1800))
            },
        )
    }.getOrDefault(Offered("", emptyList(), emptyList()))

    fun offered(context: Context) =
        runCatching { parse(File(File(context.filesDir, "bridge"), "bridge_options.json").readText()) }.getOrDefault(Offered("", emptyList(), emptyList()))

    private fun stored(context: Context, key: String) =
        runCatching { JSONObject(Settings.prefs(context).getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun json(context: Context): String = stored(context, OPTIONS).toString()

    fun isOn(context: Context, option: Option): Boolean =
        stored(context, OPTIONS).let { if (it.has(option.name)) it.getBoolean(option.name) else option.default }

    fun set(context: Context, name: String, on: Boolean) =
        Settings.prefs(context).edit().putString(OPTIONS, stored(context, OPTIONS).put(name, on).toString()).apply()

    fun interval(context: Context, check: NewCheck): Int = stored(context, CHECKS).optInt(check.id, check.start)

    fun setInterval(context: Context, id: String, seconds: Int) =
        Settings.prefs(context).edit().putString(CHECKS, stored(context, CHECKS).put(id, seconds).toString()).apply()

    /** The new checks' intervals, for the runner alongside the menu's own. */
    fun checkIntervals(context: Context): Map<String, Int> = offered(context).checks.associate { it.id to interval(context, it) }

    /** After an app update: the temporary choices go, so everything starts again from its defaults. */
    fun resetAfterUpdate(context: Context) {
        val prefs = Settings.prefs(context)
        if (prefs.getInt(FOR_APP, -1) == BuildConfig.VERSION_CODE) return
        prefs.edit().remove(OPTIONS).remove(CHECKS).putInt(FOR_APP, BuildConfig.VERSION_CODE).apply()
    }
}

@Composable
private fun IntervalMenu(seconds: Int, min: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(intervalLabel(seconds) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in INTERVAL_CHOICES) {
                val allowed = choice == 0 || choice >= min
                DropdownMenuItem(
                    text = { Text(intervalLabel(choice) + if (!allowed) "  · too often for a new check" else "") },
                    enabled = allowed,
                    onClick = { open = false; onPick(choice) },
                )
            }
        }
    }
}

/**
 * Settings › Checks, above Threats: "New from the bridge", shown only while a newer bridge has switches or checks the
 * app doesn't know. intervals/onInterval: the menu's own intervals, for a switch that rides on one of its checks.
 */
@Composable
fun NewOptionsCard(intervals: Map<String, Int>, onInterval: (String, Int) -> Unit) {
    val context = LocalContext.current
    val offered = remember { NewOptions.offered(context) }
    if (offered.isEmpty) return
    var states by remember { mutableStateOf(offered.options.associate { it.name to NewOptions.isOn(context, it) }) }
    var checkIntervals by remember { mutableStateOf(offered.checks.associate { it.id to NewOptions.interval(context, it) }) }
    val use = remember { UsageLog.dataUse(context)?.second.orEmpty() }
    fun restart() { if (BridgeService.status.value.running) BridgeService.restart(context) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨ New from the bridge", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (offered.version.isNotBlank()) {
                    Text("bridge ${offered.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "Temporary: these come from a bridge update the app doesn't know yet. When an app update adds them " +
                    "properly, this card goes away and they start again from their defaults.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC9E6F2),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
            for (option in offered.options) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label)
                        if (option.about.isNotBlank()) {
                            Text(option.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(checked = states[option.name] == true, onCheckedChange = { on ->
                        NewOptions.set(context, option.name, on)
                        states = states + (option.name to on)
                        restart()
                    })
                }
                if (option.check.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Uses the ${option.checkTitle} check's reads · no extra data",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val min = Catalog.checks.firstOrNull { it.id == option.check }?.min ?: 10
                        IntervalMenu(intervals[option.check] ?: 0, min) { onInterval(option.check, it) }
                    }
                } else {
                    Text("Comes from the bridge's existing reads.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (check in offered.checks) {
                if (offered.options.isNotEmpty() || check != offered.checks.first()) HorizontalDivider()
                val seconds = checkIntervals[check.id] ?: check.start
                val measured = use.firstOrNull { it.path.split(", ").contains(check.path) }
                val each = measured?.takeIf { it.reads > 0 }?.let { it.bytes / it.reads }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(check.title)
                        if (check.about.isNotBlank()) {
                            Text(check.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IntervalMenu(seconds, NewOptions.CHECK_MIN) {
                        NewOptions.setInterval(context, check.id, it)
                        checkIntervals = checkIntervals + (check.id to it)
                    }
                }
                Text(
                    "Its own read: ${check.path.ifBlank { check.id }} · " + when {
                        seconds == 0 -> "off, so the bridge won't read it."
                        each != null -> "about ${UsageLog.sizeLabel(each)} a read, up to ${dataLabel(86400.0 / seconds * each / 1024 / 1024)} a day."
                        else -> "its size shows once it has run."
                    } + " Starts at 30 minutes; never faster than 5 until an app update has measured it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
