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
)

/** Interval choices offered in the menu, in seconds; 0 = off. */
val INTERVAL_CHOICES = listOf(0, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 21600, 86400)

/** The game allows 60 reads a minute per key; the bridge warns above 40. */
const val READS_WARNING = 40.0
const val READS_LIMIT = 60.0

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

fun choicesFor(check: Check): List<Int> = INTERVAL_CHOICES.filter { it == 0 || it >= check.min }

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
