package local.solwatch.pocket

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * "What's new": after an app update, the release notes of every version since the one last opened (bundled in
 * assets/whats_new/<version>.md), shown once. A fresh install shows nothing.
 */
object WhatsNew {
    private const val SEEN = "whats_new_seen"
    private const val FOLDER = "whats_new"

    fun versionKey(version: String) = version.split(".").map { it.toIntOrNull() ?: 0 }

    private val ORDER = Comparator<String> { a, b ->
        val x = versionKey(a); val y = versionKey(b)
        (0 until maxOf(x.size, y.size)).map { (x.getOrNull(it) ?: 0).compareTo(y.getOrNull(it) ?: 0) }.firstOrNull { it != 0 } ?: 0
    }

    /** Versions newer than seen, up to and including current, newest first. */
    fun between(available: List<String>, seen: String, current: String): List<String> =
        available.filter { ORDER.compare(it, seen) > 0 && ORDER.compare(it, current) <= 0 }.sortedWith(ORDER.reversed())

    fun notes(context: Context, version: String): String =
        runCatching { context.assets.open("$FOLDER/$version.md").bufferedReader().readText().trim() }.getOrDefault("")

    private fun available(context: Context) =
        context.assets.list(FOLDER).orEmpty().filter { it.endsWith(".md") }.map { it.removeSuffix(".md") }

    /** (version, notes) to show now, and remembers this version as seen. Empty on a fresh install. */
    fun pending(context: Context): List<Pair<String, String>> {
        val prefs = Settings.prefs(context)
        val current = BuildConfig.VERSION_NAME
        // Before 0.9.4 nothing was stored: someone with settings already is updating, not installing.
        val seen = prefs.getString(SEEN, null) ?: if (prefs.contains("bridge_enabled")) "0.9.3" else current
        prefs.edit().putString(SEEN, current).apply()
        return between(available(context), seen, current).map { it to notes(context, it) }.filter { it.second.isNotBlank() }
    }

    fun current(context: Context) = listOf(BuildConfig.VERSION_NAME to notes(context, BuildConfig.VERSION_NAME))
}

/** The notes, one version after another: "- **Bold.** text" lines as bullets, ** as bold. */
@Composable
fun WhatsNewDialog(notes: List<Pair<String, String>>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
        title = { Text("What's new in Sol Watch") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((version, text) in notes) {
                    Text(version, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    for (line in text.lines().filter { it.isNotBlank() }) {
                        val indent = line.takeWhile { it == ' ' }.length
                        val body = line.trim().removePrefix("- ")
                        Text(
                            buildAnnotatedString {
                                append(if (indent > 0) "◦ " else "• ")
                                for ((run, bold) in boldRuns(body)) {
                                    if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(run) } else append(run)
                                }
                            },
                            Modifier.padding(start = (indent * 6).dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
    )
}
