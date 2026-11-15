package local.solwatch.pocket

import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun shortTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochSeconds * 1000))

private fun styled(text: String): AnnotatedString = buildAnnotatedString {
    for ((run, bold) in boldRuns(readableTimes(text, ::shortTime))) {
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(run) } else append(run)
    }
}

// ---------------- Alerts ----------------

// All, Urgent, then the topic tabs (Company, Fleets, Contracts, Sol, Chat, Bridge), each with filters beneath it.
private val FILTERS = listOf("all" to "All", "urgent" to "Urgent") + AlertTopics.TABS.map { it.name to it.name }

@Composable
fun AlertsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val store = remember { AlertStore.get(context) }
    val version by store.version.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var sub by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    if (FILTERS.none { it.first == filter }) filter = "all"  // a chip from before 0.8.4 (Corp, General, …)
    val alerts by produceState(emptyList<Alert>(), version, filter, sub, query) {
        value = withContext(Dispatchers.IO) { store.recent(filter, query, sub = sub) }
    }
    val kinds = remember { AlertKinds.load(context).flatMap { it.kinds }.associateBy { it.id } }
    var modes by remember { mutableStateOf(Settings.kindModes(context)) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((id, label) in FILTERS) {
                FilterChip(selected = filter == id, onClick = { filter = id; sub = "" }, label = { Text(label) })
            }
        }
        val subs = AlertTopics.subsOf(filter)
        if (subs.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (name in listOf("") + subs) {
                    FilterChip(
                        selected = sub == name, onClick = { sub = name },
                        label = { Text(name.ifBlank { "All" }, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search alerts") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        if (alerts.isEmpty()) {
            Text(
                if (query.isBlank() && filter == "all") "No alerts yet. Start Sol Watch in Settings › Status; " +
                    "new alerts appear here as they happen." else "No alerts match.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(alerts, key = { it.id }) { alert ->
                AlertCard(alert, kinds[alert.kind], modes[alert.kind].orEmpty()) { mode ->
                    Settings.setKindMode(context, alert.kind, mode)
                    modes = Settings.kindModes(context)
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: Alert, kind: AlertKind?, mode: String, onMode: (String) -> Unit) {
    val urgent = alert.priority == "urgent"
    var showKind by rememberSaveable(alert.id) { mutableStateOf(false) }  // tap the alert to show its type's setting
    Card(
        colors = if (urgent) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth().clickable(enabled = kind != null) { showKind = !showKind },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${shortTime(alert.at / 1000)} · ${AlertTopics.label(alert.tab to alert.sub).ifBlank { alert.channel }}" +
                    alert.header.takeIf { it.isNotBlank() && alert.tab.isBlank() }?.let { " · ${it.lowercase()}" }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                styled(alert.text),
                style = MaterialTheme.typography.bodyMedium,
                color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (kind != null && showKind) {  // this kind of alert's setting, right here (also in Settings › Alert types)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        kind.about, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    KindChoice(kind.id, kind.priority, mode, onMode)
                }
            }
        }
    }
}

// ---------------- Checks ----------------

@Composable
fun ChecksScreen(modifier: Modifier) {
    val context = LocalContext.current
    val checks = remember { Catalog.checks }
    var separate by remember { mutableStateOf(Settings.separateMobile(context)) }
    var set by rememberSaveable { mutableStateOf(Settings.WIFI) }
    val editing = if (separate) set else Settings.WIFI
    var intervals by remember(editing) { mutableStateOf(Settings.intervals(context, editing)) }

    fun change(values: Map<String, Int>) {
        // A mix that isn't a preset is kept for the Custom chip, both before a preset replaces it and as it changes.
        val presets = PRESETS.associateWith { Catalog.preset(it) }
        if (presetInUse(intervals, presets) == CUSTOM) Settings.saveCustomIntervals(context, editing, intervals)
        intervals = values
        Settings.saveIntervals(context, editing, values)
        if (presetInUse(values, presets) == CUSTOM) Settings.saveCustomIntervals(context, editing, values)
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ChecksSummary(checks, intervals) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Separate settings on mobile data", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Switches automatically when the phone leaves Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = separate, onCheckedChange = {
                            separate = it
                            Settings.setSeparateMobile(context, it)
                            if (!it) set = Settings.WIFI
                        })
                    }
                    if (separate) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = set == Settings.WIFI, onClick = { set = Settings.WIFI }, label = { Text("Wi-Fi") })
                            FilterChip(selected = set == Settings.MOBILE, onClick = { set = Settings.MOBILE }, label = { Text("Mobile data") })
                        }
                    }
                    Text("Preset", style = MaterialTheme.typography.labelMedium)
                    // The one in use is highlighted; Custom is any other mix, and brings back your last one.
                    val inUse = presetInUse(intervals, PRESETS.associateWith { Catalog.preset(it) })
                    val custom = Settings.customIntervals(context, editing)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in PRESETS) {
                            FilterChip(
                                selected = inUse == name, onClick = { change(Catalog.preset(name)) },
                                label = { Text(name.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                        FilterChip(
                            selected = inUse == CUSTOM, enabled = custom != null || inUse == CUSTOM,
                            onClick = { custom?.let { change(it) } }, label = { Text("Custom") },
                        )
                    }
                }
            }
        }
        for (group in Catalog.groups) {
            item(key = "group-$group") {
                Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(checks.filter { it.group == group }, key = { it.id }) { check ->
                CheckRow(check, intervals[check.id] ?: check.recommended) { seconds ->
                    change(intervals + (check.id to seconds))
                }
            }
        }
        item { FleetFlightsCard() }
        item {
            Text(
                "Data figures assume every read finds something changed, measured on 13 Nov 2026. " +
                    "Unchanged reads cost about 1 KB, so real use is usually lower. Changes apply " +
                    "within one check, without restarting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Posts about our own fleets' flights and elevator bookings: bridge settings, so a change restarts the bridge. */
@Composable
private fun FleetFlightsCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Settings.fleetFlights(context)) }
    var elevator by remember { mutableStateOf(Settings.elevatorTrips(context)) }
    var minutes by remember { mutableStateOf(Settings.fleetWarningMinutes(context)) }
    fun applied() { if (BridgeService.status.value.running) BridgeService.restart(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Our fleet flights", fontWeight = FontWeight.SemiBold)
                    Text(
                        "🧭 when a fleet leaves, ↪️ if it changes course, 🔔 a reminder before it lands, ⚓ when it lands. " +
                            "Quiet notifications. Uses the \"Our battles & sieges\" check's reads, so that check must be on; " +
                            "no extra data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = { on = it; Settings.setFleetFlights(context, it); applied() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Our elevator cargo", fontWeight = FontWeight.SemiBold)
                    Text(
                        "🛗 when a space elevator booking is made and when it arrives, 🔔 a reminder before. " +
                            "Quiet notifications. Uses the Industry check's reads; no extra data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = elevator, onCheckedChange = { elevator = it; Settings.setElevatorTrips(context, it); applied() })
            }
            if (on || elevator) {
                Text("Reminder before landing or arrival", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in listOf(0, 10, 30, 60)) {
                        FilterChip(
                            selected = minutes == choice,
                            onClick = { minutes = choice; Settings.setFleetWarningMinutes(context, choice); applied() },
                            label = { Text(if (choice == 0) "None" else "$choice min") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecksSummary(checks: List<Check>, intervals: Map<String, Int>) {
    val reads = readsPerMinute(checks, intervals)
    val data = mebibytesPerMonth(checks, intervals)
    val level = readsLevel(reads)
    // Colour and a symbol, so the level doesn't depend on telling orange from red.
    val (container, onContainer) = when (level) {
        ReadsLevel.DANGER -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ReadsLevel.CAUTION -> Color(0xFF4A3212) to Color(0xFFFFC27A)
        ReadsLevel.OK -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = if (level == ReadsLevel.OK) CardDefaults.cardColors()
        else CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(level.symbol.let { if (it.isEmpty()) "" else "$it " } + "${"%.1f".format(reads)} reads a minute",
                style = MaterialTheme.typography.titleLarge)
            Text("Up to ${dataLabel(data)} of data a month", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    reads > READS_LIMIT -> "Over the game's limit of 60 reads a minute: the API will refuse some reads."
                    level == ReadsLevel.DANGER -> "Close to the game's limit of 60 reads a minute: slow some checks down."
                    level == ReadsLevel.CAUTION -> "Above 40 reads a minute, where the bridge starts warning. The game allows 60 per key."
                    else -> "The game allows 60 reads a minute per key."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CheckRow(check: Check, seconds: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(check.title, fontWeight = FontWeight.SemiBold)
                    Text(check.alerts, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { open = true }) { Text(intervalLabel(seconds) + " ▾") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (choice in INTERVAL_CHOICES) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        intervalLabel(choice) + when {
                                            !allowed(check, choice) -> "  · too often for this check"
                                            choice == check.recommended -> "  · recommended"
                                            else -> ""
                                        }
                                    )
                                },
                                onClick = { open = false; onChange(choice) },
                                enabled = allowed(check, choice),
                            )
                        }
                    }
                }
            }
            val cost = mebibytesPerMonth(check, seconds)
            Text(
                (if (seconds == 0) "Off" else "Up to ${dataLabel(cost)} a month" + if (check.measured) "" else " (estimate)") +
                    " · recommended: ${intervalLabel(check.recommended)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (check.minWhy.isNotBlank()) {
                Text(check.minWhy, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (seconds == 0 && check.feeds.isNotBlank()) {
                Text(
                    "Off also leaves out what this check tells: ${check.feeds}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---------------- Status ----------------

@Composable
fun StatusScreen(modifier: Modifier) {
    val context = LocalContext.current
    val status by BridgeService.status.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }
    val folder = remember { File(context.filesDir, "bridge/logs") }
    val errors by produceState("", tick) { value = withContext(Dispatchers.IO) { tail(File(folder, "errors.log"), 12) } }
    val log by produceState("", tick) { value = withContext(Dispatchers.IO) { tail(File(folder, "bridge.log"), 25) } }
    val power = context.getSystemService(PowerManager::class.java)
    val exempt = power.isIgnoringBatteryOptimizations(context.packageName)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status.message, style = MaterialTheme.typography.titleLarge)
                if (status.version.isNotBlank()) Text("Bridge v${status.version} · ${status.polls} polls, ${status.failed} failed")
                if (status.lastPollAt > 0) Text("Last check: ${shortTime(status.lastPollAt / 1000)}")
                Text(if (status.metered) "On mobile data" else "On Wi-Fi or unmetered")
                if (status.outageSince > 0) Text(
                    "The game's server isn't answering (since ${shortTime(status.outageSince / 1000)}). " +
                        "The bridge keeps trying; errors.log notes only the first failure.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (status.error.isNotBlank()) Text(status.error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { BridgeService.start(context) }, enabled = !status.running) { Text("Start") }
                    OutlinedButton(onClick = { BridgeService.stop(context) }, enabled = status.running) { Text("Stop") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Running in the background", fontWeight = FontWeight.SemiBold)
                Text(
                    if (exempt) "Battery optimisation is off for Sol Watch, so checks continue with the screen off."
                    else "Android will pause checks while the phone sleeps unless battery optimisation is off for this app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!exempt) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("Allow background running") }
                }
                var soundAll by remember { mutableStateOf(Settings.soundAll(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sound for every alert")
                        Text(
                            if (soundAll) "Every alert makes a sound." else "Only urgent alerts and chat make a sound.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = soundAll, onCheckedChange = { soundAll = it; Settings.setSoundAll(context, it) })
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }) { Text("Notification sounds and channels") }
                val bytes = TrafficStats.getUidRxBytes(Process.myUid()) + TrafficStats.getUidTxBytes(Process.myUid())
                Text(
                    "Data used by this app since the phone started: ${dataLabel(bytes / 1024.0 / 1024.0)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ReportCard()
        StorageCard(tick, running = status.running)
        UsageCard(tick)
        AppUpdateCard()
        BridgeUpdateCard(running = status.running)
        PersonaliseCard()
        ApiKeyCard(running = status.running)
        LogCard("Problems (errors.log)", errors.ifBlank { "None" })
        LogCard("Recent log (bridge.log)", log.ifBlank { "Nothing yet" })
    }
}

/** TEMPORARY (see UsageLog): battery drain and data use measured by the app itself. */
@Composable
private fun UsageCard(tick: Int) {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(0) }
    val samples by produceState(emptyList<UsageLog.Sample>(), tick, cleared) {
        value = withContext(Dispatchers.IO) { UsageLog.read(context) }
    }
    var copied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Battery and data log (temporary)", fontWeight = FontWeight.SemiBold)
            val since = samples.firstOrNull()?.time
            Text(
                if (since == null) "Records every 10 minutes while Sol Watch runs; the first sample comes soon."
                else "Recording every 10 minutes since ${shortTime(since / 1000)} (${samples.size} samples). " +
                    "Battery is the whole phone's drain while unplugged; data is this app only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val now = System.currentTimeMillis()
            Text("Last 24 hours", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            UsageLog.describe(UsageLog.summarize(samples, now - 24 * 3600 * 1000L)).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Whole log", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            UsageLog.describe(UsageLog.summarize(samples)).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            val use = remember(tick, cleared) { UsageLog.dataUse(context) }
            Text("Data per check", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (use == null || use.second.isEmpty()) {
                Text("Counted from now on; the first figures come within 5 minutes.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Since ${shortTime(use.first / 1000)}, biggest first:", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                UsageLog.describeDataUse(use.second, top = 8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Sol Watch usage log", UsageLog.export(context)))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy log") }
                TextButton(onClick = { UsageLog.clear(context); cleared++; copied = false }) { Text("Clear") }
            }
        }
    }
}

/** One file with the app's logs and settings, to hand to Claude: share it or save it in Downloads. */
@Composable
private fun ReportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parts by rememberSaveable { mutableStateOf(Report.PARTS.filter { it.default }.map { it.id }.toSet()) }
    var note by remember { mutableStateOf("") }
    fun make(then: (String) -> Unit) = scope.launch {
        note = "Making the report…"
        val text = withContext(Dispatchers.IO) { runCatching { Report.build(context, parts) } }
        text.onSuccess { note = "About ${it.length / 1024} KB"; then(it) }.onFailure { note = "Couldn't make the report: ${it.message}" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Report for Claude", fontWeight = FontWeight.SemiBold)
            Text(
                "One file with the app's logs and settings, to hand over when something needs looking at. " +
                    "Your API key is never included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (part in Report.PARTS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(part.label)
                        Text(part.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = part.id in parts, onCheckedChange = { on -> parts = if (on) parts + part.id else parts - part.id })
                }
            }
            if ("alerts" in parts) {
                Text(
                    "Alert and chat text is your company's private information. Include it only when you want Claude " +
                        "to look at particular alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { make { Report.share(context, it) } }, enabled = parts.isNotEmpty()) { Text("Share report") }
                OutlinedButton(onClick = {
                    make { text ->
                        note = runCatching { "Saved in Downloads: " + Report.save(context, text) }
                            .getOrElse { "Couldn't save: ${it.message}" }
                    }
                }, enabled = parts.isNotEmpty()) { Text("Save to Downloads") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Space the app uses, the bridge's log sizes and how long alert history is kept. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StorageCard(tick: Int, running: Boolean) {
    val context = LocalContext.current
    var sizes by remember { mutableStateOf(Settings.logSizes(context)) }
    var days by remember { mutableStateOf(Settings.historyDays(context)) }
    var changed by remember { mutableStateOf(0) }
    val used by produceState(Triple(0L, 0L, 0L), tick, changed) {
        value = withContext(Dispatchers.IO) {
            val logs = File(File(context.filesDir, "bridge"), "logs").listFiles().orEmpty()
            fun sum(prefix: String) = logs.filter { it.name.startsWith(prefix) }.sumOf { it.length() }
            val db = context.getDatabasePath("alerts.db")
            val history = listOf(db, File(db.path + "-wal"), File(db.path + "-journal")).sumOf { it.length() }
            Triple(sum("bridge.log"), sum("errors.log"), history)
        }
    }
    fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
    fun save(new: List<Int>) {
        sizes = new
        Settings.setLogSizes(context, new)
        if (running) BridgeService.restart(context)
    }
    val max = sizes[0] * (sizes[1] + 1) + sizes[2] * (sizes[3] + 1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Storage", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Using ${mb(used.first + used.second + used.third)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val total = maxOf(max * 1024L * 1024L + used.third, used.first + used.second + used.third, 1L).toFloat()
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(PocketColors.line)) {
                for ((bytes, color) in listOf(used.first to MaterialTheme.colorScheme.primary,
                    used.second to Color(0xFFF06A8A), used.third to Color(0xFF4FC3F7))) {
                    if (bytes > 0) Box(Modifier.fillMaxHeight().weight(maxOf(bytes / total, 0.005f)).background(color))
                }
                val rest = 1f - (used.first + used.second + used.third) / total
                if (rest > 0.001f) Spacer(Modifier.weight(rest))
            }
            Text("Bridge log ${mb(used.first)} · error log ${mb(used.second)} · alert history ${mb(used.third)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable
            fun Choice(label: String, options: List<Int>, selected: Int, text: (Int) -> String, pick: (Int) -> Unit) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { FilterChip(selected = selected == it, onClick = { pick(it) }, label = { Text(text(it)) }) }
                }
            }
            val mbText = { n: Int -> "$n MB" }
            val copiesText = { n: Int -> if (n == 1) "1 old copy" else "$n old copies" }
            Choice("Bridge log: size of each file", listOf(1, 2, 5), sizes[0], mbText) { save(sizes.toMutableList().also { l -> l[0] = it }) }
            Choice("Bridge log: old files kept", listOf(1, 3, 5), sizes[1], copiesText) { save(sizes.toMutableList().also { l -> l[1] = it }) }
            Choice("Error log: size of each file", listOf(1, 2), sizes[2], mbText) { save(sizes.toMutableList().also { l -> l[2] = it }) }
            Choice("Error log: old files kept", listOf(1, 3), sizes[3], copiesText) { save(sizes.toMutableList().also { l -> l[3] = it }) }
            Choice("Alert history", listOf(7, 14, 30, 90), days, { "$it days" }) {
                days = it
                Settings.setHistoryDays(context, it)
                AlertStore.get(context).prune()
                changed++
            }
            Text(
                "Logs can grow to $max MB. Each log starts a new file when it's full and keeps the last few old ones; " +
                    "the oldest goes first. A change restarts Sol Watch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sizes != Settings.PHONE_LOG_SIZES) {
                OutlinedButton(onClick = { save(Settings.PHONE_LOG_SIZES) }) { Text("Use phone sizes (logs up to 10 MB)") }
            }
        }
    }
}

/** The app's own version, its GitHub release check, and a link to download a newer one. */
@Composable
private fun AppUpdateCard() {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(AppUpdater.message(context)) }
    var available by remember { mutableStateOf(AppUpdater.available(context)) }
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        message = withContext(Dispatchers.IO) { AppUpdater.check(context) }
        available = AppUpdater.available(context)
        busy = false
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("App updates", fontWeight = FontWeight.SemiBold)
            Text(
                "Sol Watch ${BuildConfig.VERSION_NAME}. Checks github.com/${AppUpdater.REPO} hourly on Wi-Fi " +
                    "and daily on mobile data while running. A new version downloads in your browser; open the " +
                    "download to install it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { busy = true }, enabled = !busy) { Text(if (busy) "Checking…" else "Check now") }
                available?.let { (version, url) ->
                    Button(onClick = { AppUpdater.openDownload(context, url) }) { Text("Download $version") }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Which bridge runs, whether it updates itself from GitHub, and a manual check. */
@Composable
private fun BridgeUpdateCard(running: Boolean) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(Settings.autoUpdate(context)) }
    var refresh by remember { mutableStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val codeDir = remember { BridgeService.codeDir(context).absolutePath }
    val info by produceState<JSONObject?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { JSONObject(Python.getInstance().getModule("pocket_update").callAttr("describe", codeDir).toString()) }
                .getOrNull()
        }
    }
    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        val runningVersion = info?.takeUnless { it.isNull("using") }?.optString("using").orEmpty()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val python = Python.getInstance()
                val required = python.getModule("pocket_runner").get("REQUIRED")
                python.getModule("pocket_update").callAttr("fetch_and_install", codeDir, runningVersion, required).asList()
            }.getOrNull()
        }
        val installed = result?.getOrNull(0)?.toBoolean() == true
        message = result?.getOrNull(1)?.toString() ?: "Couldn't check for an update."
        if (installed && running) BridgeService.restart(context)
        checking = false
        refresh++
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bridge updates", fontWeight = FontWeight.SemiBold)
            info?.let {
                Text(
                    (if (it.isNull("using")) "Bridge version unknown, " else "Bridge v${it.optString("using")}, ") +
                        (if (it.optString("source") == "GitHub") "updated from GitHub" else "as shipped with the app") +
                        if (it.optBoolean("trial")) " · on trial: goes back if it fails to start" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Install updates automatically")
                    Text(
                        "When a new version is on GitHub (checked hourly on Wi-Fi, daily on mobile data), the app checks it fits and " +
                            "switches to it. If it fails to start, the app goes back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = auto, onCheckedChange = {
                    auto = it
                    Settings.setAutoUpdate(context, it)
                    if (running) BridgeService.restart(context)
                })
            }
            OutlinedButton(onClick = { checking = true; message = "" }, enabled = !checking) {
                Text(if (checking) "Checking…" else "Check for an update now")
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PersonaliseCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Personalise", fontWeight = FontWeight.SemiBold)
            Text("Accent colour", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (accent in ACCENTS) {
                    FilterChip(
                        selected = LookState.accent == accent.name,
                        onClick = {
                            LookState.accent = accent.name
                            Settings.setAccent(context, accent.name)
                            WidgetUpdater.request(context, now = true)
                        },
                        label = { Text(accent.label, color = accent.color) },
                    )
                }
            }
            LogoField("company", "Company logo code")
            LogoField("alliance", "Alliance logo code")
            Text(
                "In the game: HQ › Logo › share code (starts with SOL1:). The API doesn't send logos, " +
                    "so paste them here; they stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogoField(which: String, label: String) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(Settings.logoCode(context, which).orEmpty()) }
    val logo = remember(code) { Logo.decode(code) }
    val image = remember(code, LookState.logos) { savedLogoBitmap(context, which) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && saveLogoImage(context, which, uri)) {
            code = ""
            Settings.setLogoCode(context, which, null)
            LookState.logos++
            WidgetUpdater.request(context, now = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { text ->
                    code = text.trim()
                    if (Logo.decode(code) != null) {
                        Settings.setLogoCode(context, which, code)
                        LookState.logos++
                        WidgetUpdater.request(context, now = true)
                    }
                },
                singleLine = true,
                label = { Text(label) },
                isError = code.isNotBlank() && logo == null,
                supportingText = if (code.isNotBlank() && logo == null) {
                    { Text("Not a logo code the app can read") }
                } else null,
                modifier = Modifier.weight(1f),
            )
            if (image != null) LogoImage(image, 44.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pick.launch("image/*") }) { Text("Or choose an image…") }
            if (image != null) {
                TextButton(onClick = {
                    clearLogo(context, which)
                    code = ""
                    LookState.logos++
                    WidgetUpdater.request(context, now = true)
                }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(running: Boolean) {
    val context = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var builtIn by remember { mutableStateOf(ApiKey.isBuiltIn(context)) }
    var hasKey by remember { mutableStateOf(ApiKey.get(context) != null) }
    // Which key to describe: a pasted one that looks complete, else the key in use.
    val candidate = entered.trim().takeIf { it.startsWith("sol_") && it.length >= 40 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SOL API key", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    builtIn -> "Using the test key built into this copy of the app."
                    hasKey -> "Using your saved key ${keyPrefix(ApiKey.get(context))}… (encrypted on this phone). " +
                        "The game lists keys by this start, under Settings › Account › API keys."
                    else -> "No key yet. Make one in the game: Settings › Account › API keys."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = entered, onValueChange = { entered = it }, singleLine = true,
                label = { Text("Paste a new key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = entered.trim().startsWith("sol_"), onClick = {
                    ApiKey.save(context, entered)
                    Settings.prefs(context).edit().remove(BridgeService.KEY_REJECTED).apply()
                    entered = ""
                    builtIn = false
                    hasKey = true
                    if (running) BridgeService.restart(context)
                }) { Text("Save key") }
                if (!builtIn && hasKey) {
                    OutlinedButton(onClick = {
                        ApiKey.clear(context)
                        builtIn = ApiKey.isBuiltIn(context)
                        hasKey = ApiKey.get(context) != null
                    }) { Text("Remove") }
                }
            }
            val key = candidate ?: ApiKey.get(context)
            if (key != null) KeySections(key, pasted = candidate != null)
        }
    }
}

/** The key's sections as the game groups them: ticked or not, and what a missing one stops. */
@Composable
private fun KeySections(key: String, pasted: Boolean) {
    val result by produceState<JSONObject?>(null, key) {
        delay(600)  // wait until the paste has settled; one read of the API index per key
        value = withContext(Dispatchers.IO) {
            runCatching {
                JSONObject(Python.getInstance().getModule("pocket_runner").callAttr("describe_key", key).toString())
            }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Couldn't check the key") }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        if (pasted) "The pasted key (${keyPrefix(key)}…) can read:" else "The key in use (${keyPrefix(key)}…) can read:",
        style = MaterialTheme.typography.labelMedium,
    )
    val data = result
    when {
        data == null -> Text("Checking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        !data.optBoolean("ok") -> Text(data.optString("error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {
            val groups = data.getJSONArray("groups")
            for (g in 0 until groups.length()) {
                val group = groups.getJSONObject(g)
                Text(group.getString("group").uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                val rows = group.getJSONArray("sections")
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONObject(r)
                    val on = row.getBoolean("enabled")
                    val need = row.getString("need")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (on) "✓" else "✗", color = if (on) PocketColors.good else
                            if (need == "required") MaterialTheme.colorScheme.error else PocketColors.muted)
                        Column {
                            Text(row.getString("label") + if (need == "required") " (required)" else "",
                                style = MaterialTheme.typography.bodyMedium)
                            if (!on && need != "unused") Text("Off: no ${row.getString("stops")}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val other = data.getJSONArray("other")
            if (other.length() > 0) Text("Also: " + (0 until other.length()).joinToString { other.getString(it) },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "sol_" and the next six characters: how the game itself lists a key. */
private fun keyPrefix(key: String?): String = key?.trim()?.take(10).orEmpty()

private fun tail(file: File, lines: Int): String =
    if (!file.exists()) "" else runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
