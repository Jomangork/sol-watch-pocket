package local.solwatch.pocket

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The game's /widget data, read tolerantly: the API documents what it holds but not every key
 * inside research, notices and trips, so several likely names are tried for each.
 */
data class GameGlance(
    val company: String,
    val money: Double?,
    val incomeRate: Double?,
    val research: String?,
    val researchProgress: Double?,
    val researchHoursLeft: Double?,
    val unread: Int?,
    val tripsUnderWay: Int?,
    val nextTrip: String?,
    val arrivals: List<String> = emptyList(),
    val receivedAt: Long,
) {
    companion object {
        private const val PREF = "widget_data"
        private const val PREF_AT = "widget_data_at"

        fun store(context: Context, json: String) = Settings.prefs(context).edit()
            .putString(PREF, json).putLong(PREF_AT, System.currentTimeMillis()).apply()

        fun load(context: Context): GameGlance? {
            val prefs = Settings.prefs(context)
            val json = prefs.getString(PREF, null) ?: return null
            return runCatching { parse(JSONObject(json), prefs.getLong(PREF_AT, 0)) }.getOrNull()
        }

        fun parse(o: JSONObject, receivedAt: Long): GameGlance {
            val gameDay = o.optDouble("gameDay", Double.NaN)
            val timeScale = o.optDouble("timeScale", Double.NaN)
            fun realHoursUntil(day: Double): Double? =
                if (day.isNaN() || gameDay.isNaN() || timeScale.isNaN() || timeScale <= 0) null
                else (day - gameDay) / timeScale * 24

            val research = o.optJSONObject("research")
            val notices = o.optJSONObject("notices")
            val trips = o.optJSONObject("trips")
            val nextList = trips?.let { firstArray(it, "next", "arriving", "items", "list", "trips") }
            val arrivals = (0 until (nextList?.length() ?: 0)).mapNotNull { i ->
                nextList?.optJSONObject(i)?.let {
                    val name = it.optString("name")
                    val to = firstString(it, "to", "destination", "toName", "where", "place")
                    val hours = realHoursUntil(firstDouble(it, "day", "arrivalDay", "arrives", "eta"))
                    listOfNotNull(name.ifBlank { null }, to?.let { "→ $it" }, hours?.let { "· " + hoursText(it) })
                        .joinToString(" ").ifBlank { null }
                }
            }
            val nextText = arrivals.firstOrNull()
            return GameGlance(
                company = o.optString("company"),
                money = o.optDouble("money", Double.NaN).takeUnless { it.isNaN() },
                incomeRate = o.optDouble("incomeRate", Double.NaN).takeUnless { it.isNaN() },
                research = research?.let { firstString(it, "name", "title", "id") },
                researchProgress = research?.optDouble("progress", Double.NaN)?.takeUnless { it.isNaN() },
                researchHoursLeft = research?.let { realHoursUntil(firstDouble(it, "doneDay", "day", "done", "eta")) },
                unread = notices?.let { firstInt(it, "unread", "count", "unreadCount") },
                tripsUnderWay = trips?.let { firstInt(it, "underWay", "count", "total", "active") },
                nextTrip = nextText,
                arrivals = arrivals,
                receivedAt = receivedAt,
            )
        }

        private fun firstString(o: JSONObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }

        private fun firstDouble(o: JSONObject, vararg keys: String): Double =
            keys.firstNotNullOfOrNull { k -> o.optDouble(k, Double.NaN).takeUnless { it.isNaN() } } ?: Double.NaN

        private fun firstInt(o: JSONObject, vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optInt(k) else null }

        private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? =
            keys.firstNotNullOfOrNull { o.optJSONArray(it) }
    }
}

fun hoursText(hours: Double): String = when {
    hours < 0 -> "now"
    hours < 1 -> "${(hours * 60).roundToInt()}m"
    hours < 48 -> "${hours.toInt()}h ${((hours % 1) * 60).roundToInt()}m"
    else -> "${(hours / 24).roundToInt()}d"
}

/** $M figures as the game shows them: $662M, $50.15B. */
fun moneyText(millions: Double): String =
    if (kotlin.math.abs(millions) >= 1000) "$" + "%.2f".format(millions / 1000) + "B" else "$" + "%.0f".format(millions) + "M"

class SolWidget : GlanceAppWidget() {
    // Laid out for its real size, so a bigger widget shows more instead of empty space.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AlertStore.get(context)
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
        val urgentCount = store.countSince(dayAgo, "urgent")
        val alertCount = store.countSince(dayAgo)
        val urgent = store.latest("urgent")?.takeIf { it.at >= dayAgo }
        val game = GameGlance.load(context)
        val status = BridgeService.status.value
        val accent = accentFor(Settings.accent(context)).color
        val logo = savedLogoBitmap(context, "company", scale = 4)
        val recent = store.recent("all", "", limit = 8).filter { it.id != urgent?.id && it.priority != "urgent" }
        val now = System.currentTimeMillis()
        val next = CalendarStore.nextMajor(CalendarStore.load(context), now)
        provideContent { WidgetBody(context, urgent, urgentCount, alertCount, game, status, accent, logo, recent, next, now) }
    }
}

private fun c(color: Color) = ColorProvider(color)

@androidx.compose.runtime.Composable
private fun WidgetBody(
    context: Context,
    urgent: Alert?,
    urgentCount: Int,
    alertCount: Int,
    game: GameGlance?,
    status: BridgeStatus,
    accent: Color,
    logo: android.graphics.Bitmap?,
    recent: List<Alert>,
    next: CalEntry?,
    now: Long,
) {
    val (state, label) = health(context, status)
    // Space beyond the basic layout (about 225 dp tall) goes, in order, to the urgent alert's full
    // text, more arrivals, then the latest alerts. One small line is about 16 dp.
    val size = LocalSize.current
    var spare = (size.height.value - 225f - (if (next != null) 44f else 0f)).coerceAtLeast(0f)
    val urgentText = urgent?.plain?.trim()?.replace("\n", " · ")?.take(480).orEmpty()
    val charsPerLine = ((size.width.value - 50f) / 6.5f).coerceAtLeast(20f)
    val urgentNeeds = kotlin.math.ceil(urgentText.length / charsPerLine).toInt().coerceIn(2, 8)
    val urgentLines = 2 + minOf(urgentNeeds - 2, (spare / 16f).toInt())
    spare -= (urgentLines - 2) * 16f
    val arrivalLines = 1 + minOf((game?.arrivals?.size ?: 1) - 1, (spare / 16f).toInt()).coerceAtLeast(0)
    spare -= (arrivalLines - 1) * 16f
    val recentLines = if (spare >= 40f) minOf(recent.size, ((spare - 20f) / 16f).toInt()) else 0
    val checked = (status.lastPollAt.takeIf { it > 0 } ?: Settings.prefs(context).getLong(BridgeService.LAST_POLL_AT, 0))
        .takeIf { it > 0 }
        ?.let { TimeText.time(it) }
    Column(
        GlanceModifier.fillMaxSize().background(c(PocketColors.night)).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) {
                Image(ImageProvider(logo), contentDescription = null, modifier = GlanceModifier.size(30.dp))
                Spacer(GlanceModifier.width(8.dp))
            }
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    game?.company?.ifBlank { null } ?: "Sol Watch",
                    maxLines = 1,
                    style = TextStyle(color = c(PocketColors.text), fontWeight = FontWeight.Bold, fontSize = 14.sp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(7.dp).cornerRadius(4.dp).background(c(state.color))) {}
                    Spacer(GlanceModifier.width(5.dp))
                    Text(
                        label + if (state == Health.OK && checked != null) " · checked $checked" else "",
                        maxLines = 1,
                        style = TextStyle(color = c(state.color), fontSize = 11.sp),
                    )
                }
            }
            Text(
                "$alertCount today",
                style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        // The point of this widget: is anything wrong right now?
        Row(
            GlanceModifier.fillMaxWidth().background(c(if (urgent != null) PocketColors.urgentPanel else PocketColors.panel))
                .cornerRadius(12.dp),
        ) {
            Box(
                GlanceModifier.width(4.dp).height((28 + 16 * (if (urgent != null) urgentLines else 1)).dp)
                    .background(c(if (urgent != null) PocketColors.urgent else accent)),
            ) {}
            Column(GlanceModifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (urgent != null) {
                    Text(
                        "$urgentCount urgent in 24 h",
                        style = TextStyle(color = c(PocketColors.urgent), fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    )
                    Text(
                        urgentText,
                        maxLines = urgentLines,
                        style = TextStyle(color = c(PocketColors.text), fontSize = 12.sp),
                    )
                } else {
                    Text("All quiet", style = TextStyle(color = c(accent), fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    Text(
                        "No urgent alerts in the last 24 hours",
                        style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp),
                    )
                }
            }
        }
        if (next?.at != null) {
            // The next major Calendar entry (a rule change, the ballot closing); opens the Calendar tab.
            Spacer(GlanceModifier.height(8.dp))
            Row(
                GlanceModifier.fillMaxWidth().background(c(PocketColors.panelHigh)).cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(actionStartActivity<MainActivity>(actionParametersOf(TAB_KEY to "calendar"))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(next.title, maxLines = 1,
                        style = TextStyle(color = c(PocketColors.text), fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    Text(DateText.day(next.at) + ", " + DateText.time(next.at), maxLines = 1,
                        style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp))
                }
                Text(CalendarStore.countdown(next.at, now),
                    style = TextStyle(color = c(kindColor(next.kind)), fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
        }
        if (game != null) {
            Spacer(GlanceModifier.height(8.dp))
            // Money on the left half, research on the right, as two equal panels.
            Row(GlanceModifier.fillMaxWidth()) {
                Column(
                    GlanceModifier.defaultWeight().height(PANEL_HEIGHT).background(c(PocketColors.panel))
                        .cornerRadius(12.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Money", style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp))
                    Text(
                        game.money?.let { moneyText(it) } ?: "—",
                        style = TextStyle(color = c(PocketColors.text), fontWeight = FontWeight.Bold, fontSize = 17.sp),
                    )
                    game.incomeRate?.let {
                        Text(
                            (if (it >= 0) "+" else "") + moneyText(it) + "/day",
                            style = TextStyle(color = c(if (it >= 0) PocketColors.good else PocketColors.urgent), fontSize = 11.sp),
                        )
                    }
                }
                Spacer(GlanceModifier.width(8.dp))
                Column(
                    GlanceModifier.defaultWeight().height(PANEL_HEIGHT).background(c(PocketColors.panel))
                        .cornerRadius(12.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Research" + listOfNotNull(
                            game.researchProgress?.let { "${(it * 100).roundToInt()}%" },
                            game.researchHoursLeft?.let { hoursText(it) },
                        ).joinToString(" · ", prefix = " · ").takeIf { game.researchProgress != null || game.researchHoursLeft != null }.orEmpty(),
                        maxLines = 1,
                        style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp),
                    )
                    Text(
                        game.research ?: "Nothing in the lab",
                        maxLines = 2,
                        style = TextStyle(color = c(PocketColors.text), fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    )
                    game.researchProgress?.let {
                        Spacer(GlanceModifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = it.toFloat().coerceIn(0f, 1f),
                            modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                            color = c(accent),
                            backgroundColor = c(PocketColors.line),
                        )
                    }
                }
            }
            if (game.arrivals.isNotEmpty()) {
                Spacer(GlanceModifier.height(4.dp))
                game.arrivals.take(arrivalLines).forEachIndexed { i, trip ->
                    Text(
                        (if (i == 0) "Next arrival: " else "Then: ") + trip +
                            if (i == 0) game.tripsUnderWay?.let { n -> " ($n under way)" }.orEmpty() else "",
                        maxLines = 1,
                        style = TextStyle(color = c(PocketColors.muted), fontSize = 11.sp),
                    )
                }
            }
        }
        if (recentLines > 0) {
            Spacer(GlanceModifier.height(6.dp))
            Text("Latest", style = TextStyle(color = c(accent), fontWeight = FontWeight.Bold, fontSize = 11.sp))
            recent.take(recentLines).forEach { alert ->
                Text(
                    alert.plain.lineSequence().first(),
                    maxLines = 1,
                    style = TextStyle(color = c(PocketColors.text), fontSize = 11.sp),
                )
            }
        }
    }
}

private val PANEL_HEIGHT = 72.dp
private val TAB_KEY = ActionParameters.Key<String>(PocketNav.EXTRA_TAB)

class SolWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SolWidget()
}

/** Refreshes the home-screen widget: at once for urgent alerts, otherwise at most once a minute. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var last = 0L

    fun request(context: Context, now: Boolean = false) {
        val time = System.currentTimeMillis()
        if (!now && time - last < 60_000) return
        last = time
        val app = context.applicationContext
        scope.launch { runCatching { SolWidget().updateAll(app) } }
    }
}
