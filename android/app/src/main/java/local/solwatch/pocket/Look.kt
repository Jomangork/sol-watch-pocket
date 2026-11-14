package local.solwatch.pocket

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp

// The app's own look: a dark "control room" palette with one accent colour the user picks.
// Gold is the default, matching the user's own in-game colour choice.

data class Accent(val name: String, val label: String, val color: Color)

val ACCENTS = listOf(
    Accent("gold", "Gold", Color(0xFFE2B84A)),
    Accent("cyan", "Cyan", Color(0xFF4FC3F7)),
    Accent("green", "Green", Color(0xFF5BD18B)),
    Accent("rose", "Rose", Color(0xFFF06A8A)),
    Accent("violet", "Violet", Color(0xFFA98BFF)),
)

fun accentFor(name: String): Accent = ACCENTS.firstOrNull { it.name == name } ?: ACCENTS.first()

object PocketColors {
    val night = Color(0xFF0A0E16)      // page
    val panel = Color(0xFF141B27)      // cards
    val panelHigh = Color(0xFF1C2433)  // raised cards, menus
    val line = Color(0xFF2A3446)
    val text = Color(0xFFE6EBF2)
    val muted = Color(0xFF93A0B4)
    val urgent = Color(0xFFE5484D)
    val urgentPanel = Color(0xFF3A1518)
    val good = Color(0xFF5BD18B)
}

@Composable
fun PocketTheme(accent: Color, content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = accent,
        onPrimary = PocketColors.night,
        secondary = accent,
        onSecondary = PocketColors.night,
        secondaryContainer = accent.copy(alpha = 0.22f),
        onSecondaryContainer = PocketColors.text,
        background = PocketColors.night,
        onBackground = PocketColors.text,
        surface = PocketColors.night,
        onSurface = PocketColors.text,
        surfaceVariant = PocketColors.panel,
        onSurfaceVariant = PocketColors.muted,
        surfaceContainer = PocketColors.panel,
        surfaceContainerLow = PocketColors.panel,
        surfaceContainerHigh = PocketColors.panelHigh,
        surfaceContainerHighest = PocketColors.panelHigh,
        outline = PocketColors.line,
        outlineVariant = PocketColors.line,
        error = PocketColors.urgent,
        errorContainer = PocketColors.urgentPanel,
        onErrorContainer = Color(0xFFFFDADA),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/** A logo as a crisp bitmap (nearest-neighbour scaling, so the pixels stay square). */
fun logoBitmap(logo: Logo, scale: Int = 6): Bitmap {
    val small = Bitmap.createBitmap(logo.size, logo.size, Bitmap.Config.ARGB_8888)
    for (y in 0 until logo.size) for (x in 0 until logo.size) small.setPixel(x, y, logo.pixel(x, y))
    return Bitmap.createScaledBitmap(small, logo.size * scale, logo.size * scale, false)
}

fun savedLogo(context: Context, which: String): Logo? = Logo.decode(Settings.logoCode(context, which))

private fun logoFile(context: Context, which: String) = java.io.File(context.filesDir, "logo_$which.png")

/** The logo to show: from its share code if there is one, else from an image the user picked. */
fun savedLogoBitmap(context: Context, which: String, scale: Int = 6): Bitmap? =
    savedLogo(context, which)?.let { logoBitmap(it, scale) }
        ?: logoFile(context, which).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }

/** Keeps a picked image (e.g. the alliance logo downloaded from the game), at most 256 px. */
fun saveLogoImage(context: Context, which: String, uri: android.net.Uri): Boolean {
    val picked = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        ?: return false
    val longest = maxOf(picked.width, picked.height)
    // Tiny pixel art (the game's alliance logo downloads at 16 px) is enlarged by a whole
    // factor without smoothing, so it stays sharp on the widget; big images are shrunk.
    val factor = if (longest < 128) 128 / longest else 1
    val image = when {
        longest > 256 -> Bitmap.createScaledBitmap(picked, picked.width * 256 / longest, picked.height * 256 / longest, true)
        factor > 1 -> Bitmap.createScaledBitmap(picked, picked.width * factor, picked.height * factor, false)
        else -> picked
    }
    logoFile(context, which).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return true
}

fun clearLogo(context: Context, which: String) {
    Settings.setLogoCode(context, which, null)
    logoFile(context, which).delete()
}

@Composable
fun LogoImage(bitmap: Bitmap, size: Dp, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(image, contentDescription = null, filterQuality = FilterQuality.None, modifier = modifier.size(size))
}

@Composable
fun LogoImage(logo: Logo, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(logo) { logoBitmap(logo) }
    LogoImage(bitmap, size, modifier)
}

/** Green: watching. Yellow: running but something needs a look. Red: not watching. */
enum class Health(val color: Color) { OK(PocketColors.good), WARN(Color(0xFFF2C14E)), DOWN(PocketColors.urgent) }

private const val WARN_UNTIL = "warn_until"
private const val WARN_REASON = "warn_reason"
private const val STALE_MS = 5 * 60 * 1000L

/** Marks the app as needing a look for half an hour (a key problem, failed checks, an update). */
fun raiseWarning(context: Context, reason: String) {
    Settings.prefs(context).edit()
        .putLong(WARN_UNTIL, System.currentTimeMillis() + 30 * 60 * 1000L).putString(WARN_REASON, reason).apply()
}

fun health(context: Context, status: BridgeStatus): Pair<Health, String> {
    val prefs = Settings.prefs(context)
    val now = System.currentTimeMillis()
    val lastPoll = status.lastPollAt.takeIf { it > 0 } ?: prefs.getLong(BridgeService.LAST_POLL_AT, 0)
    return when {
        !Settings.enabled(context) -> Health.DOWN to "Stopped"
        status.error.isNotBlank() && !status.running -> Health.DOWN to status.message
        status.keyRejected || prefs.getBoolean(BridgeService.KEY_REJECTED, false) -> Health.DOWN to "API key rejected"
        lastPoll > 0 && now - lastPoll > STALE_MS -> Health.WARN to "No check for ${(now - lastPoll) / 60000} min"
        prefs.getLong(WARN_UNTIL, 0) > now -> Health.WARN to (prefs.getString(WARN_REASON, null) ?: "Needs a look")
        lastPoll == 0L -> Health.WARN to "Starting…"
        else -> Health.OK to "Watching"
    }
}
