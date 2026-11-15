package local.solwatch.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

/** The colour of each kind of entry (World, Ours, Threats), the same whatever accent the user picked. */
fun kindColor(kind: String): Color = when (kind) {
    "world" -> Color(0xFFE2B84A)
    "threat" -> Color(0xFFF06A8A)
    else -> Color(0xFF4FC3F7)
}

private val FILTERS = listOf("all" to "All", "world" to "World", "ours" to "Ours", "threat" to "Threats")

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(ms: Long, now: Long): String {
    val days = ((startOfDay(ms) - startOfDay(now)) / 86_400_000.0).let { Math.round(it) }
    return when (days) {
        0L -> "Today · " + DateText.day(ms)
        1L -> "Tomorrow · " + DateText.day(ms)
        else -> DateText.day(ms)
    }
}

@Composable
fun CalendarScreen(modifier: Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    val entries = remember(tick) { CalendarStore.load(context) }
    val now = remember(tick) { System.currentTimeMillis() }
    var month by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = entries.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        CalendarDetail(modifier, open, now) { openId = null }
        return
    }
    if (month) MonthView(modifier, entries, now, onList = { month = false }, onOpen = { openId = it })
    else AgendaView(modifier, entries, now, onMonth = { month = true }, onOpen = { openId = it })
}

@Composable
private fun ViewSwitch(month: Boolean, onChange: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(PocketColors.panel)
            .border(1.dp, PocketColors.line, RoundedCornerShape(20.dp)).padding(3.dp),
    ) {
        for ((label, isMonth) in listOf("List" to false, "Month" to true)) {
            val on = isMonth == month
            Text(
                label,
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(enabled = !on, onClick = onChange).padding(horizontal = 14.dp, vertical = 7.dp),
                color = if (on) MaterialTheme.colorScheme.primary else PocketColors.muted,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun TitleRow(month: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Calendar", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        ViewSwitch(month, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgendaView(modifier: Modifier, entries: List<CalEntry>, now: Long, onMonth: () -> Unit, onOpen: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val shown = entries.filter { filter == "all" || it.kind == filter }
    val groups = shown.groupBy { e -> e.at?.let { dayLabel(it, now) } ?: "No date yet" }
    val next = CalendarStore.nextMajor(entries, now)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TitleRow(month = false, onChange = onMonth) }
        if (next != null) item { NextBigCard(next, now) { onOpen(next.id) } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in FILTERS) FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
            }
        }
        if (entries.isEmpty()) item { EmptyCalendar() }
        for ((label, items) in groups) {
            item(key = "day:$label") {
                Text(label, style = MaterialTheme.typography.labelLarge, color = PocketColors.muted, modifier = Modifier.padding(top = 6.dp))
            }
            items(items, key = { it.id }) { EntryRow(it, now) { onOpen(it.id) } }
        }
    }
}

@Composable
private fun EmptyCalendar() {
    val status by BridgeService.status.collectAsState()
    Text(
        if (status.running) "The calendar fills in within a minute or two of SOL-Watch starting, from the checks' own reads."
        else "Start SOL-Watch on the Status tab: the calendar comes from its checks' reads.",
        style = MaterialTheme.typography.bodyMedium,
        color = PocketColors.muted,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun NextBigCard(entry: CalEntry, now: Long, onClick: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PocketColors.panelHigh),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NEXT BIG CHANGE", style = MaterialTheme.typography.labelMedium, color = gold, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(CalendarStore.countdown(entry.at!!, now), fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = gold)
            }
            Text(
                listOf(DateText.day(entry.at!!) + ", " + DateText.time(entry.at), entry.sub).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = PocketColors.muted,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: CalEntry, now: Long, onClick: () -> Unit) {
    val past = entry.at != null && entry.at <= now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PocketColors.panel).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.at?.let { DateText.time(it) } ?: "—", Modifier.width(62.dp), color = PocketColors.muted, fontSize = 13.sp)
        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(entry.kind).copy(alpha = if (past) 0.4f else 1f)))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontSize = 15.sp, color = if (past) PocketColors.muted else PocketColors.text)
            val sub = listOfNotNull("Major".takeIf { entry.major }, entry.sub.ifBlank { null }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = PocketColors.muted, maxLines = 2)
        }
        entry.at?.let {
            Text(if (past) "past" else CalendarStore.countdown(it, now), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                color = if (past) PocketColors.muted else PocketColors.text)
        }
    }
}

@Composable
private fun MonthView(modifier: Modifier, entries: List<CalEntry>, now: Long, onList: () -> Unit, onOpen: (String) -> Unit) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf(startOfDay(now)) }
    val first = Calendar.getInstance().apply {
        timeInMillis = startOfDay(now); set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset)
    }
    val monthName = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(first.time)
    val lead = (first.get(Calendar.DAY_OF_WEEK) - first.firstDayOfWeek + 7) % 7
    val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val byDay = entries.filter { it.at != null }.groupBy { startOfDay(it.at!!) }
    val today = startOfDay(now)
    val dayStarts = (0 until daysInMonth).map { d ->
        (first.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d) }.timeInMillis
    }
    val weekdays = (0 until 7).map { i ->
        val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (first.firstDayOfWeek + i - 1) % 7 + 1) }
        java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault()).format(c.time)
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TitleRow(month = true, onChange = onList) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { offset-- }) { Text("‹", fontSize = 22.sp) }
                        Text(monthName, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { offset++ }) { Text("›", fontSize = 22.sp) }
                    }
                    Row { weekdays.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = PocketColors.muted, fontSize = 12.sp) } }
                    val cells = List(lead) { null } + dayStarts
                    cells.chunked(7).forEach { week ->
                        Row {
                            for (i in 0 until 7) {
                                val start = week.getOrNull(i)
                                Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    if (start != null) DayCell(start, byDay[start].orEmpty(), start == today, start == picked) { picked = start }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                        for ((kind, label) in FILTERS.drop(1)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(kind)))
                                Spacer(Modifier.width(6.dp))
                                Text(label, fontSize = 12.sp, color = PocketColors.muted)
                            }
                        }
                    }
                }
            }
        }
        val dayEntries = byDay[picked].orEmpty()
        item {
            val days = Math.round((picked - today) / 86_400_000.0)
            Text(
                DateText.day(picked) + when {
                    days == 0L -> " · today"
                    days == 1L -> " · tomorrow"
                    days > 1 -> " · in $days days"
                    else -> ""
                },
                style = MaterialTheme.typography.labelLarge, color = PocketColors.muted, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (dayEntries.isEmpty()) item { Text("Nothing on this day.", color = PocketColors.muted, fontSize = 14.sp) }
        items(dayEntries, key = { it.id }) { EntryRow(it, now) { onOpen(it.id) } }
    }
}

@Composable
private fun DayCell(start: Long, entries: List<CalEntry>, today: Boolean, picked: Boolean, onClick: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    val major = entries.any { it.major }
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (today) gold else if (picked) PocketColors.panelHigh else Color.Transparent)
                .then(if (major && !today) Modifier.border(2.dp, gold, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.DAY_OF_MONTH).toString(),
                fontSize = 14.sp,
                color = if (today) PocketColors.night else PocketColors.text,
                fontWeight = if (today || major) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            entries.map { it.kind }.distinct().sortedBy { FILTERS.indexOfFirst { f -> f.first == it } }.forEach {
                Box(Modifier.size(6.dp).clip(CircleShape).background(kindColor(it)))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarDetail(modifier: Modifier, entry: CalEntry, now: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val gold = MaterialTheme.colorScheme.primary
    var remind by remember(entry.id) { mutableIntStateOf(CalendarStore.remindMinutes(context, entry.id)) }
    var noCalendarApp by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("‹  Calendar") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kindName = FILTERS.firstOrNull { it.first == entry.kind }?.second?.removeSuffix("s") ?: "Ours"
                    Tag(kindName, kindColor(entry.kind))
                    if (entry.major) Tag("Major", gold)
                }
                Text(entry.title, style = MaterialTheme.typography.headlineSmall)
                if (entry.sub.isNotBlank()) Text(entry.sub, color = PocketColors.muted)
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PocketColors.panelHigh),
                border = BorderStroke(1.dp, kindColor(entry.kind).copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.at != null) {
                        Text(CalendarStore.countdown(entry.at, now), fontFamily = FontFamily.Monospace, fontSize = 32.sp, color = kindColor(entry.kind))
                        Text(DateText.full(entry.at), fontSize = 15.sp)
                        entry.gameDay?.let { Text("Your time · game day ${"%,.0f".format(it)}", fontSize = 13.sp, color = PocketColors.muted) }
                    } else {
                        Text("No date yet", fontSize = 22.sp, color = kindColor(entry.kind))
                        Text("The game hasn't set one; it shows here when it does.", fontSize = 13.sp, color = PocketColors.muted)
                    }
                }
            }
        }
        if (entry.about.isNotBlank()) item { InfoCard("What it means", listOf(entry.about)) }
        if (entry.details.isNotEmpty()) {
            item { InfoCard(if (entry.id == "power_rules") "Our places off Earth now" else "Now", entry.details) }
        }
        if (entry.at != null && entry.at > now) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remind me", style = MaterialTheme.typography.labelLarge, color = PocketColors.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in CalendarStore.REMIND_CHOICES) {
                            FilterChip(
                                selected = remind == minutes,
                                onClick = { remind = minutes; CalendarStore.setRemindMinutes(context, entry.id, minutes) },
                                label = { Text(CalendarStore.remindLabel(minutes)) },
                            )
                        }
                    }
                    Text(
                        "A notification with a sound, while SOL-Watch runs.",
                        fontSize = 12.sp, color = PocketColors.muted,
                    )
                    OutlinedButton(
                        onClick = { noCalendarApp = !CalendarStore.addToPhoneCalendar(context, entry) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to phone calendar") }
                    if (noCalendarApp) Text("No calendar app on this phone takes new events.", fontSize = 12.sp, color = PocketColors.urgent)
                }
            }
        }
        if (entry.source.isNotBlank()) {
            item { Text("From the ${entry.source} · no extra reads", fontSize = 12.sp, color = PocketColors.muted) }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = PocketColors.muted)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
