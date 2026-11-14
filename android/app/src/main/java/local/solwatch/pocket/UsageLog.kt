package local.solwatch.pocket

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import java.io.File

/**
 * TEMPORARY measuring aid (added in 0.6.3, to be removed once battery and data use are known).
 * Every 10 minutes while SOL-Watch runs it notes the phone's battery level, whether it's charging,
 * the app's own network bytes and whether the network is metered, in files/usage.csv (kept 14 days).
 * Android's own battery screen resets at every full charge, and its data screen reset with an update,
 * so this keeps the figures across both. Removing it: this file, its call in BridgeService and
 * UsageCard in Screens.kt.
 */
object UsageLog {
    private const val FILE = "usage.csv"
    private const val KEEP_MS = 14 * 24 * 3600 * 1000L
    const val EVERY_MS = 10 * 60 * 1000L

    data class Sample(
        val time: Long, val battery: Int, val charging: Boolean, val bytes: Long, val metered: Boolean, val running: Boolean,
    ) {
        fun line() = "$time,$battery,${if (charging) 1 else 0},$bytes,${if (metered) 1 else 0},${if (running) 1 else 0}"

        companion object {
            fun parse(line: String): Sample? {
                val p = line.split(',')
                if (p.size < 6) return null
                return runCatching {
                    Sample(p[0].toLong(), p[1].toInt(), p[2] == "1", p[3].toLong(), p[4] == "1", p[5] == "1")
                }.getOrNull()
            }
        }
    }

    data class Summary(
        val coveredMs: Long, val unpluggedMs: Long, val drained: Int, val wifiBytes: Long, val mobileBytes: Long,
    ) {
        val drainPerHour get() = if (unpluggedMs > 0) drained / (unpluggedMs / 3_600_000.0) else 0.0
    }

    // ---- pure (JVM-tested) ----

    /**
     * Totals over samples after `since`. Battery counts only spans unplugged at both ends and no longer
     * than 30 minutes (a longer gap means the app or phone was off). Data: the app's byte counter
     * restarts at 0 when the phone reboots, so a drop counts the new value. Each span's bytes go to the
     * network of its later sample.
     */
    fun summarize(samples: List<Sample>, since: Long = 0): Summary {
        val list = samples.filter { it.time >= since }.sortedBy { it.time }
        var unplugged = 0L
        var drained = 0
        var wifi = 0L
        var mobile = 0L
        for ((a, b) in list.zipWithNext()) {
            val span = b.time - a.time
            if (!a.charging && !b.charging && span <= 3 * EVERY_MS) {
                unplugged += span
                drained += (a.battery - b.battery).coerceAtLeast(0)
            }
            val bytes = if (b.bytes >= a.bytes) b.bytes - a.bytes else b.bytes
            if (b.metered) mobile += bytes else wifi += bytes
        }
        val covered = if (list.size > 1) list.last().time - list.first().time else 0L
        return Summary(covered, unplugged, drained, wifi, mobile)
    }

    fun describe(summary: Summary): List<String> {
        val hours = summary.coveredMs / 3_600_000.0
        if (hours < 0.1) return listOf("Not enough samples yet (one every 10 minutes).")
        val lines = mutableListOf<String>()
        val unpluggedHours = summary.unpluggedMs / 3_600_000.0
        lines += if (unpluggedHours < 0.1) "Battery: charging the whole time, so no drain to measure."
        else "Battery: unplugged %.1f h, fell %d%% (%.1f%% an hour, whole phone)."
            .format(unpluggedHours, summary.drained, summary.drainPerHour)
        val mib = (summary.wifiBytes + summary.mobileBytes) / 1024.0 / 1024.0
        lines += "Data: ${dataLabel(summary.wifiBytes / 1024.0 / 1024.0)} Wi-Fi, " +
            "${dataLabel(summary.mobileBytes / 1024.0 / 1024.0)} mobile (about ${dataLabel(mib / hours * 24)} a day)."
        return lines
    }

    // ---- on the phone ----

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun read(context: Context): List<Sample> =
        runCatching { file(context).readLines().mapNotNull { Sample.parse(it) } }.getOrDefault(emptyList())

    /** Adds a sample now and drops ones older than 14 days. */
    fun record(context: Context, metered: Boolean, running: Boolean) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val uid = Process.myUid()
        val bytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) + TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val sample = Sample(now, level * 100 / scale, charging, bytes, metered, running)
        synchronized(this) {
            val kept = read(context).filter { it.time >= now - KEEP_MS } + sample
            file(context).writeText(kept.joinToString("\n") { it.line() } + "\n")
        }
    }

    fun clear(context: Context) = synchronized(this) { file(context).delete() }

    /** Summary plus the raw samples, for pasting into a chat. */
    fun export(context: Context): String {
        val samples = read(context)
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("SOL-Watch Pocket ${BuildConfig.VERSION_NAME} usage log")
            appendLine("Last 24 hours:")
            describe(summarize(samples, now - 24 * 3600 * 1000L)).forEach { appendLine("  $it") }
            appendLine("Whole log:")
            describe(summarize(samples)).forEach { appendLine("  $it") }
            appendLine("time,battery%,charging,appBytes,mobile,running")
            samples.forEach { appendLine(it.line()) }
        }
    }
}
