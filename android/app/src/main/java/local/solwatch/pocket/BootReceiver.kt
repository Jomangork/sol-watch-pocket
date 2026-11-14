package local.solwatch.pocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the bridge again after the phone restarts or the app is updated, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            Settings.enabled(context)
        ) {
            BridgeService.start(context)
        }
    }
}
