package local.solwatch.pocket

import android.content.Context
import java.io.File

/**
 * What each check downloads, from the wrapper's data_use.json (pocket_runner counts it), for Report for Claude and
 * the New from the bridge card. The battery and data sampling it began as (0.6.3) was retired in 0.9.6: Android's
 * own battery and data screens are enough now the figures are known.
 */
object UsageLog {
    /** 0.9.6: the old 10-minute battery and data samples go. */
    fun retireSamples(context: Context) { File(context.filesDir, "usage.csv").delete() }

    // ---- data per check (the wrapper's data_use.json) ----

    data class PathUse(val path: String, val name: String, val bytes: Long, val reads: Long)

    private fun dataUseFile(context: Context) = File(File(context.filesDir, "bridge"), "data_use.json")

    /** (since, paths by bytes, most first) from data_use.json; null before the first save (5 minutes in). */
    fun parseDataUse(json: String): Pair<Long, List<PathUse>> {
        val o = org.json.JSONObject(json)
        val paths = o.optJSONObject("paths") ?: org.json.JSONObject()
        val names = o.optJSONObject("names") ?: org.json.JSONObject()
        fun nameOf(path: String) = if (path.isEmpty()) "API index (the bridge's key check)" else names.optString(path).ifBlank {
            names.keys().asSequence().firstOrNull { path.startsWith("$it/") }?.let { names.optString(it) } ?: path
        }
        // Paths with one name (Industry reads two, the reference tables several) are added up into one line.
        val list = paths.keys().asSequence().map { path ->
            val pair = paths.optJSONArray(path)
            PathUse(path, nameOf(path), pair?.optLong(0) ?: 0, pair?.optLong(1) ?: 0)
        }.groupBy { it.name }.map { (name, uses) ->
            PathUse(uses.joinToString(", ") { it.path }, name, uses.sumOf { it.bytes }, uses.sumOf { it.reads })
        }.sortedByDescending { it.bytes }
        return o.optLong("since") * 1000 to list
    }

    fun dataUse(context: Context): Pair<Long, List<PathUse>>? =
        runCatching { parseDataUse(dataUseFile(context).readText()) }.getOrNull()

    /** "Industry: 41% · 32 MB · 1,234 reads, 27 KB each", the biggest first. */
    fun describeDataUse(use: List<PathUse>, top: Int = 10): List<String> {
        val total = use.sumOf { it.bytes }.coerceAtLeast(1)
        return use.take(top).map {
            val each = if (it.reads > 0) it.bytes / it.reads / 1024.0 else 0.0
            "${it.name}: ${(it.bytes * 100 / total)}% · ${sizeLabel(it.bytes)} · " +
                "%,d reads, %.1f KB each".format(it.reads, each)
        }
    }

    /** "6 MB", or "740 KB" under a megabyte (dataLabel would say "under 1 MB"). */
    fun sizeLabel(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else dataLabel(bytes / 1024.0 / 1024.0)

    /** {host: (connections opened, requests)} from data_use.json. */
    fun parseConnections(json: String): Map<String, Pair<Long, Long>> {
        val hosts = org.json.JSONObject(json).optJSONObject("connections") ?: return emptyMap()
        return hosts.keys().asSequence().associateWith { host ->
            val pair = hosts.optJSONArray(host)
            (pair?.optLong(0) ?: 0) to (pair?.optLong(1) ?: 0)
        }
    }

    /** "solthegame.com: 1,234 new connections for 10,456 requests (12%)". */
    fun describeConnections(hosts: Map<String, Pair<Long, Long>>): List<String> =
        hosts.entries.sortedByDescending { it.value.second }.map { (host, counts) ->
            val (made, used) = counts
            "$host: %,d new connections for %,d requests (%d%%)".format(made, used, if (used > 0) made * 100 / used else 0)
        }

    /** Data per check and new connections, for Report for Claude. */
    fun export(context: Context): String = buildString {
        val use = dataUse(context)
        if (use == null) appendLine("Not counted yet (the first figures come 5 minutes after the bridge starts).")
        use?.let { (since, list) ->
            appendLine("Data per check since ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(since))}:")
            describeDataUse(list, top = 40).forEach { appendLine("  $it") }
        }
        runCatching { parseConnections(dataUseFile(context).readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("Connections (each new one costs a few KB of handshake the figures above don't include):")
            describeConnections(it).forEach { line -> appendLine("  $line") }
        }
    }
}
