package local.solwatch.pocket

import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Back up and restore: the user's settings as one JSON file in Downloads, and back. Only settings the user chose
 * (BACKED_UP), never the API key (it lives encrypted elsewhere) or the app's own bookkeeping.
 */
object Backup {
    // Setting names, or the start of them, that are the user's choices.
    private val BACKED_UP = listOf(
        "checks_", "custom_", "separate_mobile", "accent", "logo_", "auto_update", "sound_all", "fleet_flights",
        "elevator_trips", "rail_links", "fleet_warning_minutes", "log_sizes", "history_days", "kind_", "my_alerts",
        "quiet_", "clock", "status_post", "status_times", "cal_remind_",
    )

    fun isBackedUp(key: String) = BACKED_UP.any { key == it || (it.endsWith("_") && key.startsWith(it)) }

    /** {"app", "version", "made", "settings": {name: {"type", "value"}}} from these settings. */
    fun toJson(all: Map<String, *>, now: Long = System.currentTimeMillis()): String {
        val settings = JSONObject()
        for ((key, value) in all.toSortedMap()) {
            if (!isBackedUp(key) || value == null) continue
            val type = when (value) { is Boolean -> "bool"; is Int -> "int"; is Long -> "long"; is Float -> "float"; else -> "string" }
            settings.put(key, JSONObject().put("type", type).put("value", value.toString()))
        }
        return JSONObject().put("app", "Sol Watch").put("version", BuildConfig.VERSION_NAME).put("made", now)
            .put("settings", settings).toString(2)
    }

    /** The settings in a backup, typed, leaving out anything that isn't one of the user's choices. */
    fun read(json: String): Map<String, Any> {
        val o = JSONObject(json)
        require(o.optString("app") == "Sol Watch") { "This isn't a Sol Watch settings file" }
        val settings = o.getJSONObject("settings")
        return settings.keys().asSequence().filter(::isBackedUp).associateWith { key ->
            val x = settings.getJSONObject(key)
            val v = x.getString("value")
            when (x.optString("type")) {
                "bool" -> v.toBoolean(); "int" -> v.toInt(); "long" -> v.toLong(); "float" -> v.toFloat(); else -> v
            }
        }
    }

    fun save(context: Context): String {
        val name = "sol-watch-settings-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
        context.contentResolver.openOutputStream(uri)?.use { it.write(toJson(Settings.prefs(context).all).toByteArray()) }
            ?: error("Couldn't write the file")
        return name
    }

    /** Replaces the user's choices with the backup's; returns how many settings it had. */
    fun restore(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't read the file")
        val restored = read(text)
        val prefs = Settings.prefs(context)
        prefs.edit().apply {
            prefs.all.keys.filter(::isBackedUp).forEach { remove(it) }
            for ((key, value) in restored) when (value) {
                is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value)
                is Float -> putFloat(key, value); else -> putString(key, value.toString())
            }
        }.apply()
        TimeText.load(context)
        LookState.load(context)
        LookState.logos++
        return restored.size
    }
}

/** Settings › My app: save every setting to a file, or load one back (a new phone, or a reinstall). */
@Composable
fun BackupCard() {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            note = runCatching { "Restored ${Backup.restore(context, uri)} settings." }.getOrElse { "Couldn't restore: ${it.message}" }
            if (BridgeService.status.value.running) BridgeService.restart(context)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Back up settings", fontWeight = FontWeight.SemiBold)
            Text(
                "Every setting you chose (checks, alert types, My alerts, quiet hours, status post, colour and logos) in one " +
                    "file in Downloads, to load on a new phone or after a reinstall. Your API key is not in it: paste it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    note = runCatching { "Saved in Downloads: " + Backup.save(context) }.getOrElse { "Couldn't save: ${it.message}" }
                }) { Text("Back up") }
                OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Restore…") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Settings › My app: when the bridge posts its "still running" status. */
@Composable
fun StatusPostCard(running: Boolean) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Settings.statusPostOn(context)) }
    var times by remember { mutableStateOf(Settings.statusTimes(context)) }
    fun save(newOn: Boolean, newTimes: List<Int>) {
        on = newOn; times = newTimes.distinct().sorted()
        Settings.setStatusPost(context, on, times)
        if (running) BridgeService.restart(context)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🟢 Bridge status post", fontWeight = FontWeight.SemiBold)
                    Text(
                        "A short \"still running\" post: how long it's run, checks and failures, game updates, problems. " +
                            "If one doesn't arrive, something's wrong.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = { save(it, times) })
            }
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (minute in times) {
                        InputChip(
                            selected = true, onClick = { save(true, times - minute) },
                            label = { Text(TimeText.minutes(minute) + "  ×") },
                        )
                    }
                    OutlinedButton(onClick = {
                        val start = (times.lastOrNull()?.plus(180) ?: 480) % 1440
                        TimePickerDialog(context, { _, h, m -> save(true, times + (h * 60 + m)) }, start / 60, start % 60, TimeText.is24).show()
                    }) { Text("+ Add time") }
                }
                Text(
                    if (times.isEmpty()) "No times: no status posts." else "${times.size} post${if (times.size == 1) "" else "s"} a day. Tap a time to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
