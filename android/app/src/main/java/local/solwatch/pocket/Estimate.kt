package local.solwatch.pocket

// Pure helpers for the Checks menu (no Android types, so JVM unit tests cover them).

data class Check(
    val id: String,
    val group: String,
    val title: String,
    val alerts: String,
    val recommended: Int,
    val saver: Int,
    val fast: Int,
    val kib: Double,
    val measured: Boolean,
    val feeds: String,
    val min: Int,
    val minWhy: String = "",  // why min is longer than usual (a large download), shown under the check
)

/** Interval choices offered in the menu, in seconds; 0 = off. */
val INTERVAL_CHOICES = listOf(0, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 21600, 86400)

/** The game allows 60 reads a minute per key; the bridge warns above 40. The menu turns red at 55. */
const val READS_WARNING = 40.0
const val READS_DANGER = 55.0
const val READS_LIMIT = 60.0

enum class ReadsLevel(val symbol: String) { OK(""), CAUTION("⚠️"), DANGER("⛔") }

fun readsLevel(reads: Double) = when {
    reads >= READS_DANGER -> ReadsLevel.DANGER
    reads >= READS_WARNING -> ReadsLevel.CAUTION
    else -> ReadsLevel.OK
}

val PRESETS = listOf("recommended", "saver", "fast")
const val CUSTOM = "custom"

/** Which preset these intervals are exactly, or CUSTOM. */
fun presetInUse(intervals: Map<String, Int>, presets: Map<String, Map<String, Int>>): String =
    presets.entries.firstOrNull { (_, values) -> values.all { (id, seconds) -> (intervals[id] ?: seconds) == seconds } }?.key ?: CUSTOM

private const val SECONDS_PER_MONTH = 30.0 * 24 * 3600

/** Industry reads two endpoints per poll. */
fun readsPerPoll(checkId: String) = if (checkId == "IndustryWatcher") 2 else 1

fun intervalLabel(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds < 60 -> "${seconds} s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    else -> "${seconds / 60} min"
}

/** Every check offers the same choices; those under its minimum are shown but can't be picked. */
fun allowed(check: Check, seconds: Int) = seconds == 0 || seconds >= check.min

/**
 * A stored interval that isn't one of the menu's choices (presets before 0.8.5 had 20 s, 45 s and 3 min), moved
 * to one that is: on Wi-Fi the next shorter choice, on mobile data the next longer one, within the check's minimum.
 */
fun snapInterval(seconds: Int, min: Int, shorter: Boolean): Int {
    if (seconds <= 0 || seconds in INTERVAL_CHOICES) return seconds
    val choices = INTERVAL_CHOICES.filter { it >= min }
    return if (shorter) choices.lastOrNull { it < seconds } ?: choices.first()
    else choices.firstOrNull { it > seconds } ?: choices.last()
}

fun readsPerMinute(checks: List<Check>, intervals: Map<String, Int>): Double =
    checks.sumOf { check ->
        val seconds = intervals[check.id] ?: check.recommended
        if (seconds <= 0) 0.0 else 60.0 / seconds * readsPerPoll(check.id)
    }

/** Worst case: every read finds changed data. Most reads come back unchanged (~1 KiB). */
fun mebibytesPerMonth(check: Check, seconds: Int): Double =
    if (seconds <= 0) 0.0 else SECONDS_PER_MONTH / seconds * check.kib / 1024.0

fun mebibytesPerMonth(checks: List<Check>, intervals: Map<String, Int>): Double =
    checks.sumOf { mebibytesPerMonth(it, intervals[it.id] ?: it.recommended) }

fun dataLabel(mebibytes: Double): String = when {
    mebibytes <= 0.0 -> "none"
    mebibytes < 1.0 -> "under 1 MB"
    mebibytes < 1000.0 -> "${"%.0f".format(mebibytes)} MB"
    else -> "${"%.1f".format(mebibytes / 1024.0)} GB"
}

// Discord time codes, as the bridge writes them: '<t:N:f> (<t:N:R>)' or a lone '<t:N:R>'.
private val DISCORD_TIME = Regex("""<t:(\d+):[tTdDfF]>(?: \(<t:\d+:R>\))?|<t:(\d+):R>""")

/** Writes Discord time codes out with the given formatter (epoch seconds → text). */
fun readableTimes(text: String, format: (Long) -> String): String =
    DISCORD_TIME.replace(text) { match ->
        format((match.groupValues[1].ifEmpty { match.groupValues[2] }).toLong())
    }

/** Splits Discord-style **bold** into (text, bold) runs; backticks are dropped. */
fun boldRuns(text: String): List<Pair<String, Boolean>> {
    val runs = mutableListOf<Pair<String, Boolean>>()
    var bold = false
    for (part in text.replace("`", "").split("**")) {
        if (part.isNotEmpty()) runs.add(part to bold)
        bold = !bold
    }
    return runs
}
