package local.solwatch.pocket

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On/off switches a newer bridge has that the app doesn't know yet (the runner lists them in bridge_options.json,
 * with the bridge's own description). The user's choices go into the bridge's settings.ini, so new posts can be
 * tried before an app update gives them a proper place.
 */
object NewOptions {
    data class Option(val name: String, val label: String, val about: String, val default: Boolean)

    private const val PREF = "new_options"

    fun parse(json: String): List<Option> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Option(o.getString("name"), o.optString("label"), o.optString("about"), o.optBoolean("default"))
        }
    }.getOrDefault(emptyList())

    fun offered(context: Context) =
        runCatching { parse(File(File(context.filesDir, "bridge"), "bridge_options.json").readText()) }.getOrDefault(emptyList())

    fun json(context: Context): String = Settings.prefs(context).getString(PREF, "{}") ?: "{}"

    fun isOn(context: Context, option: Option): Boolean =
        runCatching { JSONObject(json(context)).let { if (it.has(option.name)) it.getBoolean(option.name) else option.default } }
            .getOrDefault(option.default)

    fun set(context: Context, name: String, on: Boolean) {
        val all = runCatching { JSONObject(json(context)) }.getOrDefault(JSONObject()).put(name, on)
        Settings.prefs(context).edit().putString(PREF, all.toString()).apply()
    }
}

/** Settings › Checks: "New from the bridge", shown only while a newer bridge has switches the app doesn't know. */
@Composable
fun NewOptionsCard() {
    val context = LocalContext.current
    val options = remember { NewOptions.offered(context) }
    if (options.isEmpty()) return
    var states by remember { mutableStateOf(options.associate { it.name to NewOptions.isOn(context, it) }) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New from the bridge", fontWeight = FontWeight.SemiBold)
            Text(
                "Switches a newer bridge added that this version of the app doesn't know yet. Their alerts show under a " +
                    "\"New\" filter on the Alerts tab until an app update gives them a proper place. A change restarts the bridge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (option in options) {
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
                        if (BridgeService.status.value.running) BridgeService.restart(context)
                    })
                }
            }
        }
    }
}
