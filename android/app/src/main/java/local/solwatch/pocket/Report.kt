package local.solwatch.pocket

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Report for Claude": one text file with what's usually needed to look into a problem, to share or save.
 * The API key is never in it. Alert and chat text (the company's private information) only when asked for:
 * otherwise the bridge log keeps only the bridge's own [tagged] lines.
 */
object Report {
    data class Part(val id: String, val label: String, val about: String, val default: Boolean)

    val PARTS = listOf(
        Part("status", "Status, checks and settings", "Versions, running time, each check's interval, Storage and alert choices", true),
        Part("usage", "Data per check", "How much each check downloads, since the count started", true),
        Part("errors", "Errors log", "The last 300 lines, and the last crash if there was one", true),
        Part("bridge", "Bridge log", "The last 1,500 lines of what the bridge did (reads, retries, outages)", true),
        Part("alerts", "Alert and chat text", "Lines in the bridge log that are alerts or chat, plus the last day's alerts", false),
    )
    const val ERROR_LINES = 300
    const val BRIDGE_LINES = 1500
    private const val TAIL_BYTES = 600_000L  // enough for BRIDGE_LINES lines, without reading a whole 5 MB log

    // A bridge log line starts with its time: "2026-11-15 16:03:31 text" in bridge.log ("[12:57:31] text" on screen).
    // The bridge's own notes go on "[Tag] …"; alerts and chat start with an emoji.
    private val STAMP = Regex("""^(?:\d{4}-\d{2}-\d{2} \d{1,2}:\d{2}:\d{2}|\[\d{1,2}:\d{2}:\d{2}]) ?""")

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "sol-watch-report-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".txt"

    /** The bridge log without alert and chat text: (kept lines, how many were left out). */
    fun withoutAlertText(lines: List<String>): Pair<List<String>, Int> {
        var dropped = 0
        var inAlert = false
        val kept = lines.filter { line ->
            val stamp = STAMP.find(line)
            if (stamp != null) inAlert = !line.substring(stamp.range.last + 1).startsWith("[")
            // A line with no time continues the line before it (a multi-line alert, or a traceback).
            val keep = !inAlert
            if (!keep) dropped++
            keep
        }
        return kept to dropped
    }

    /** Anything that looks like the API key is replaced, in case a log line ever quoted it. */
    fun redact(text: String, key: String?): String =
        if (key.isNullOrBlank() || key.length < 8) text else text.replace(key, "[API key removed]")

    fun build(context: Context, parts: Set<String>, now: Long = System.currentTimeMillis()): String {
        val logs = File(context.filesDir, "bridge/logs")
        val status = BridgeService.status.value
        val text = buildString {
            appendLine("== Sol Watch ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · bridge ${status.version.ifBlank { "not started" }}")
            appendLine("== Made ${Date(now)} · Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
            appendLine("== Parts: ${PARTS.filter { it.id in parts }.joinToString(", ") { it.label }}")
            if ("status" in parts) {
                section("Status")
                appendLine("Running: ${status.running} · ${status.message}")
                appendLine("Polls: ${status.polls} · failed: ${status.failed} · last poll: ${if (status.lastPollAt > 0) Date(status.lastPollAt) else "none"}")
                appendLine("Mobile data: ${status.metered} · key rejected: ${status.keyRejected} · outage since: ${if (status.outageSince > 0) Date(status.outageSince) else "none"}")
                if (status.error.isNotBlank()) appendLine("Error: ${status.error}")
                appendLine("API key: ${if (ApiKey.get(context) != null) "set" else "not set"}")
                section("Checks (seconds, 0 = off)")
                val separate = Settings.separateMobile(context)
                for (set in if (separate) listOf(Settings.WIFI, Settings.MOBILE) else listOf(Settings.WIFI)) {
                    appendLine("$set: " + Settings.intervals(context, set).entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
                }
                section("Settings")
                appendLine("Separate mobile settings: $separate · auto bridge update: ${Settings.autoUpdate(context)} · sound for every alert: ${Settings.soundAll(context)}")
                appendLine("Fleet flights: ${Settings.fleetFlights(context)} · elevator cargo: ${Settings.elevatorTrips(context)} · reminder before landing: ${Settings.fleetWarningMinutes(context)} min")
                appendLine("Log sizes (MB, copies, MB, copies): ${Settings.logSizes(context)} · alert history: ${Settings.historyDays(context)} days")
                appendLine("Alert types changed: " + Settings.kindModes(context).entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" }.ifBlank { "none" })
                section("My alerts")
                val (myStatus, _) = MyAlerts.state(context)
                val rules = MyAlerts.load(context)
                if (rules.isEmpty()) appendLine("None")
                for (rule in rules) {
                    val now = myStatus[rule.id]
                    appendLine(
                        "${now?.title?.ifBlank { null } ?: MyAlerts.fallbackTitle(rule, "")} · ${rule.kind} ${MyAlerts.lineText(rule)}" +
                            listOf(rule.place, rule.good, rule.region, rule.spaceport).filter { it.isNotBlank() }.joinToString("") { " · $it" } +
                            " · ${if (rule.on) "on" else "off"} · ${if (rule.loud) "loud" else "quiet"}" +
                            " · ${now?.now ?: "not checked yet"}${if (now?.met == true) " (past its line)" else ""}"
                    )
                }
                section("Alerts in the last 24 hours, by tab")
                val store = AlertStore.get(context)
                val dayAgo = now - 24 * 3600 * 1000L
                appendLine("All ${store.countSince(dayAgo)} · urgent ${store.countSince(dayAgo, "urgent")}")
                appendLine(store.countsByKind(dayAgo).entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }.ifBlank { "No labelled alerts" })
                section("Storage")
                appendLine(logs.listFiles()?.sortedBy { it.name }?.joinToString(", ") { "${it.name} ${it.length() / 1024} KB" } ?: "No logs")
            }
            if ("usage" in parts) {
                section("Data per check")
                append(UsageLog.export(context))
            }
            if ("errors" in parts) {
                section("Errors log (last $ERROR_LINES lines)")
                tailLines(File(logs, "errors.log"), ERROR_LINES).forEach { appendLine(it) }
                CrashReport.file(context).takeIf { it.exists() }?.let {
                    section("Last crash")
                    appendLine(it.readText())
                }
            }
            if ("bridge" in parts) {
                val lines = tailLines(File(logs, "bridge.log"), BRIDGE_LINES)
                if ("alerts" in parts) {
                    section("Bridge log (last $BRIDGE_LINES lines)")
                    lines.forEach { appendLine(it) }
                } else {
                    val (kept, dropped) = withoutAlertText(lines)
                    section("Bridge log (last $BRIDGE_LINES lines; $dropped lines that may hold alert or chat text left out)")
                    kept.forEach { appendLine(it) }
                }
            }
            if ("alerts" in parts) {
                section("Alerts in the last 24 hours")
                AlertStore.get(context).recent("all", "", limit = 1000).filter { it.at >= now - 24 * 3600 * 1000L }.forEach {
                    appendLine("${Date(it.at)} · ${AlertTopics.label(it.tab to it.sub).ifBlank { it.channel }} · ${it.priority}${if (it.kind.isNotBlank()) " · " + it.kind else ""}")
                    appendLine("  " + it.plain.replace("\n", "\n  "))
                }
            }
        }
        return redact(text, ApiKey.get(context))
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("== $title")
    }

    /** The last lines of a file, reading only its end. */
    fun tailLines(file: File, count: Int): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                val lines = String(bytes, Charsets.UTF_8).lines().let { if (start > 0) it.drop(1) else it }  // a cut first line
                lines.dropLastWhile { it.isEmpty() }.takeLast(count)
            }
        }.getOrDefault(emptyList())
    }

    /** Opens Android's share menu with the report as a text file. */
    fun share(context: Context, text: String) {
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }  // only the newest report is kept
        val file = File(folder, fileName()).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves the report in Downloads; returns its name. */
    fun save(context: Context, text: String): String {
        val name = fileName()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file in Downloads")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Couldn't write the file")
        return name
    }
}
