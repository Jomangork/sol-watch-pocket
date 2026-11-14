package local.solwatch.pocket

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Turns alerts into notifications. 🚨 / 🚨⚔️ / 🆘 go to the loud Urgent channel, one
 * notification each; everything else is silent, grouped per bridge channel.
 * Each Android channel's sound and Do Not Disturb behaviour can be changed in system settings.
 */
object Notifier {
    const val URGENT = "urgent"
    const val SERVICE = "service"
    const val SERVICE_ID = 1
    private val QUIET = listOf(
        "corp" to "Corp",
        "general" to "General",
        "alliance" to "Alliance",
    )
    // Chat makes a sound (unlike the other quiet channels). A new id, because Android keeps a
    // channel's importance from when it was first made: the old silent "chat" channel is removed.
    const val CHAT = "chat_messages"
    // Seeded from the clock so a restarted app doesn't replace notifications still showing.
    private var nextId = (System.currentTimeMillis() / 1000 % 1_000_000).toInt() + 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Losses, incoming fleets, sieges against us, escape capsules (🚨 🆘)"
                enableVibration(true)
            }
        )
        for ((id, name) in QUIET) {
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Everything else the bridge would post to $name, silently"
                }
            )
        }
        manager.deleteNotificationChannel("chat")
        manager.createNotificationChannel(
            NotificationChannel(CHAT, "Chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Global and alliance chat, when the Chat relay check is on"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "Running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The ongoing notification Android requires while SOL-Watch runs"
            }
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun show(context: Context, channel: String, header: String, plain: String, priority: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lines = plain.trim().lines()
        val title = lines.first().take(120)
        val androidChannel = when (priority) {
            "urgent" -> URGENT
            "chat" -> CHAT
            else -> channel.takeIf { c -> QUIET.any { it.first == c } } ?: "corp"
        }
        val builder = NotificationCompat.Builder(context, androidChannel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.drop(1).joinToString(" ").ifBlank { headerLabel(header) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain.trim()))
            .setSubText(headerLabel(header))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setColor(if (priority == "urgent") 0xFFE5484D.toInt() else accentArgb(context))
        val manager = NotificationManagerCompat.from(context)
        if (priority == "urgent") {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            manager.notify(nextId++, builder.build())
            return
        }
        val quiet = androidChannel != CHAT
        builder.setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(androidChannel).setSilent(quiet)
        manager.notify(nextId++, builder.build())
        manager.notify(
            androidChannel.hashCode(),
            NotificationCompat.Builder(context, androidChannel)
                .setSmallIcon(R.drawable.ic_notify)
                .setGroup(androidChannel)
                .setGroupSummary(true)
                .setSilent(true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        )
    }

    fun service(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("SOL-Watch is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    private fun accentArgb(context: Context): Int {
        val color = accentFor(Settings.accent(context)).color
        return android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
    }

    private fun headerLabel(header: String) =
        header.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "SOL-Watch" }
}
