package local.solwatch.pocket

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** A tab another part of the app asks to open (the widget's next-event row opens the Calendar). */
object PocketNav {
    const val EXTRA_TAB = "local.solwatch.pocket.TAB"
    var requested by mutableStateOf<String?>(null)

    fun take(intent: android.content.Intent?) {
        intent?.getStringExtra(EXTRA_TAB)?.let { requested = it }
    }
}

/** What the Personalise card changes, so the whole app redraws at once. */
object LookState {
    var accent by mutableStateOf("gold")
    var logos by mutableIntStateOf(0)  // bumped when a logo code is saved

    fun load(context: Context) {
        accent = Settings.accent(context)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReport.file(this).exists()) {  // show the last crash first, once
            startActivity(android.content.Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        LookState.load(this)
        PocketNav.take(intent)
        setContent {
            PocketTheme(accentFor(LookState.accent).color) { PocketScreens() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        PocketNav.take(intent)
    }
}

private val TABS = listOf("🔔" to "Alerts", "📅" to "Calendar", "⏱️" to "Checks", "📡" to "Status")

@Composable
fun PocketScreens() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(PocketNav.requested) {
        val wanted = TABS.indexOfFirst { it.second.equals(PocketNav.requested, ignoreCase = true) }
        if (wanted >= 0) tab = wanted
        PocketNav.requested = null
    }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PocketHeader() },
        bottomBar = {
            NavigationBar(containerColor = PocketColors.panel) {
                TABS.forEachIndexed { index, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(icon) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> AlertsScreen(modifier)
            1 -> CalendarScreen(modifier)
            2 -> ChecksScreen(modifier)
            else -> StatusScreen(modifier)
        }
    }
}

/** Company logo and name, the alliance logo, and a dot that says whether SOL-Watch is running. */
@Composable
private fun PocketHeader() {
    val context = LocalContext.current
    val status by BridgeService.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val logosVersion = LookState.logos  // redraw when a logo changes
    val company = savedLogoBitmap(context, "company")
    val alliance = savedLogoBitmap(context, "alliance")
    var tick by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); tick++ } }
    val (state, label) = androidx.compose.runtime.remember(status, tick, logosVersion) { health(context, status) }
    val name = GameGlance.load(context)?.company?.ifBlank { null } ?: "SOL-Watch Pocket"
    Row(
        Modifier.fillMaxWidth().background(PocketColors.night).windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (company != null) LogoImage(company, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 0.5.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(state.color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (alliance != null) LogoImage(alliance, 30.dp)
    }
}
