package local.solwatch.pocket

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One of the user's own alerts (pocket_myalerts.py checks them). value is in the kind's unit (see MyAlerts.KINDS). */
data class MyAlertRule(
    val id: String,
    val kind: String,
    val on: Boolean = true,
    val loud: Boolean = false,
    val value: Double = 0.0,
    val place: String = "",
    val good: String = "",
    val region: String = "",
    val spaceport: String = "",
)

data class MyAlertStatus(val now: String, val met: Boolean, val title: String)
data class MyAlertChoice(val label: String, val place: String = "", val good: String = "", val region: String = "", val spaceport: String = "")

/**
 * The user's own thresholds: kept in the app's settings, handed to the bridge's runner, which checks them once a
 * minute and writes each one's current value (and the places and goods the editor offers) to my_alerts.json.
 */
object MyAlerts {
    /** kind -> (name in the editor, what the number means, its unit's multiplier from what's typed, what to pick). */
    data class Kind(val id: String, val name: String, val ask: String, val scale: Double, val pick: String = "", val hint: String = "")

    val KINDS = listOf(
        Kind("money_below", "Money below", "Money falls below ($ millions)", 1e6),
        Kind("money_above", "Money above", "Money rises above ($ millions)", 1e6),
        Kind("income_below", "Income", "Income falls below ($ millions a day; 0 = turns negative)", 1e6),
        Kind("stock_hours", "Stock running low", "Runs out within (hours)", 1.0, "stock", "Our stocks, from the Industry check"),
        Kind("supplies_hours", "Crew supplies", "Supplies run out within (hours)", 1.0, "supplies", "Our crewed places, from the Company check"),
        Kind("research_minutes", "Research", "Current research finishes within (minutes)", 1.0),
        Kind("market_stock", "Market stock", "Stock falls below (% of the market's target)", 1.0, "market",
            "Earth's markets, from the hourly Market corruption check (it must be on)"),
        Kind("spaceport_wait", "Spaceport wait", "Wait falls below (days)", 1.0, "spaceport",
            "One small extra read every 10 minutes while this alert is on"),
    )

    fun kind(id: String) = KINDS.firstOrNull { it.id == id } ?: KINDS.first()

    private const val PREF = "my_alerts"

    fun toJson(rules: List<MyAlertRule>): String = JSONArray(rules.map { r ->
        JSONObject().put("id", r.id).put("kind", r.kind).put("on", r.on).put("loud", r.loud).put("value", r.value)
            .put("place", r.place).put("good", r.good).put("region", r.region).put("spaceport", r.spaceport)
    }).toString()

    fun parse(json: String): List<MyAlertRule> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MyAlertRule(
                o.getString("id"), o.getString("kind"), o.optBoolean("on", true), o.optBoolean("loud"), o.optDouble("value", 0.0),
                o.optString("place"), o.optString("good"), o.optString("region"), o.optString("spaceport"),
            )
        }
    }.getOrDefault(emptyList())

    fun json(context: Context): String = Settings.prefs(context).getString(PREF, "[]") ?: "[]"
    fun load(context: Context) = parse(json(context))
    /** Saving reaches a running bridge at once (BridgeService listens for this setting). */
    fun save(context: Context, rules: List<MyAlertRule>) = Settings.prefs(context).edit().putString(PREF, toJson(rules)).apply()
    const val SETTING = PREF

    /** (status by rule id, choices by list name) from the runner's my_alerts.json. */
    fun parseState(json: String): Pair<Map<String, MyAlertStatus>, Map<String, List<MyAlertChoice>>> = runCatching {
        val o = JSONObject(json)
        val status = o.optJSONObject("status") ?: JSONObject()
        val choices = o.optJSONObject("choices") ?: JSONObject()
        status.keys().asSequence().associateWith { id ->
            val s = status.getJSONObject(id)
            MyAlertStatus(s.optString("now"), s.optBoolean("met"), s.optString("title"))
        } to choices.keys().asSequence().associateWith { list ->
            val array = choices.getJSONArray(list)
            (0 until array.length()).map { i ->
                val c = array.getJSONObject(i)
                MyAlertChoice(c.optString("label"), c.optString("place"), c.optString("good"), c.optString("region"), c.optString("spaceport"))
            }
        }
    }.getOrDefault(emptyMap<String, MyAlertStatus>() to emptyMap())

    fun state(context: Context) = runCatching { parseState(File(File(context.filesDir, "bridge"), "my_alerts.json").readText()) }
        .getOrDefault(emptyMap<String, MyAlertStatus>() to emptyMap())

    /** "$500M", "3 h", "45 min", "10%", "2 days": the line, for the list. */
    fun lineText(rule: MyAlertRule): String {
        val k = kind(rule.kind)
        val typed = rule.value / k.scale
        fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)
        return when (rule.kind) {
            "money_below", "money_above" -> "$${n(typed)}M"
            "income_below" -> if (rule.value == 0.0) "below zero" else "$${n(typed)}M a day"
            "stock_hours", "supplies_hours" -> "${n(typed)} h"
            "research_minutes" -> "${n(typed)} min"
            "market_stock" -> "${n(typed)}%"
            else -> "${n(typed)} days"
        }
    }

    /** The list's title for a rule before the bridge has described it. */
    fun fallbackTitle(rule: MyAlertRule, choice: String) = "${kind(rule.kind).name} ${lineText(rule)}" +
        if (choice.isNotBlank()) " · $choice" else ""
}

/** Settings › My alerts: the list, and the editor for one alert. */
@Composable
fun MyAlertsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(MyAlerts.load(context)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
    val state = remember(tick, rules) { MyAlerts.state(context) }
    var editing by remember { mutableStateOf<MyAlertRule?>(null) }
    fun save(new: List<MyAlertRule>) { rules = new; MyAlerts.save(context, new) }

    editing?.let { rule ->
        BackHandler { editing = null }
        MyAlertEditor(modifier, rule, state.second, isNew = rules.none { it.id == rule.id },
            onSave = { saved -> save(if (rules.any { it.id == saved.id }) rules.map { if (it.id == saved.id) saved else it } else rules + saved); editing = null },
            onDelete = { save(rules.filterNot { it.id == rule.id }); editing = null },
            onCancel = { editing = null })
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Your own alerts, on top of the bridge's. Each one fires once when its line is crossed, and again only after " +
                    "the value has gone back past it. Checked once a minute from the checks' own reads, so they add no data " +
                    "(a spaceport wait adds one small read every 10 minutes). They show under Company · My alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rules, key = { it.id }) { rule ->
            val status = state.first[rule.id]
            Card(
                Modifier.fillMaxWidth().clickable { editing = rule },
                colors = if (status?.met == true && rule.on) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status?.title?.ifBlank { null } ?: MyAlerts.fallbackTitle(rule, ""), fontWeight = FontWeight.SemiBold)
                        Text(status?.now ?: "Checked within a minute of Sol Watch running", style = MaterialTheme.typography.bodySmall)
                        Text(
                            (if (rule.loud) "Loud" else "Quiet") + " · tap to change",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rule.on, onCheckedChange = { on -> save(rules.map { if (it.id == rule.id) it.copy(on = on) else it }) })
                }
            }
        }
        item {
            Button(onClick = { editing = MyAlertRule(id = System.currentTimeMillis().toString(36), kind = "money_below", value = 500e6) }) {
                Text("Add alert")
            }
        }
    }
}

@Composable
private fun MyAlertEditor(
    modifier: Modifier, start: MyAlertRule, choices: Map<String, List<MyAlertChoice>>, isNew: Boolean,
    onSave: (MyAlertRule) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit,
) {
    var rule by remember { mutableStateOf(start) }
    val kind = MyAlerts.kind(rule.kind)
    var typed by remember(rule.kind) {
        mutableStateOf(if (rule.kind == start.kind) (start.value / kind.scale).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } else "")
    }
    val options = choices[kind.pick].orEmpty()
    val picked = options.firstOrNull {
        it.place == rule.place && it.good == rule.good && it.region == rule.region && it.spaceport == rule.spaceport
    }
    val number = typed.replace(",", "").toDoubleOrNull()
    val ready = number != null && (kind.pick.isBlank() || picked != null)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("‹ My alerts") }
                Text(if (isNew) "New alert" else "Edit alert", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Text("Kind", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in MyAlerts.KINDS) {
                    FilterChip(selected = rule.kind == k.id, onClick = {
                        if (rule.kind != k.id) rule = rule.copy(kind = k.id, place = "", good = "", region = "", spaceport = "")
                    }, label = { Text(k.name) })
                }
            }
        }
        if (kind.pick.isNotBlank()) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (options.isEmpty()) {
                    Text("Nothing to pick yet: the list fills in after that check's next read while Sol Watch runs.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }) { Text((picked?.label ?: "Choose…") + " ▾") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for (option in options) {
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    open = false
                                    rule = rule.copy(place = option.place, good = option.good, region = option.region, spaceport = option.spaceport)
                                })
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = typed, onValueChange = { typed = it }, singleLine = true,
                label = { Text(kind.ask) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = typed.isNotBlank() && number == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("Sound", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !rule.loud, onClick = { rule = rule.copy(loud = false) }, label = { Text("Quiet") })
                FilterChip(selected = rule.loud, onClick = { rule = rule.copy(loud = true) }, label = { Text("Loud") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ready, onClick = { onSave(rule.copy(value = (number ?: 0.0) * kind.scale)) }) { Text("Save") }
                if (!isNew) OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
