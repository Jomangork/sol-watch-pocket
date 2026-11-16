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
 * Turns alerts into notifications, all in one bundle. 🚨 / 🚨⚔️ / 🆘 / 🔑 go to the loud Urgent channel;
 * everything else to its bridge channel's (Corp, General, Alliance), silent by default.
 * "Sound for every alert" (Settings.soundAll) sends those to twin channels that make a sound,
 * because Android fixes a channel's importance when it is first made. Each channel's sound can
 * still be changed in Android's settings: the app never forces a notification silent.
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
    // Twins of the quiet channels that make a sound, for "Sound for every alert".
    private fun soundId(id: String) = "${id}_sound"

    // Chat makes a sound (unlike the other quiet channels). A new id, because Android keeps a
    // channel's importance from when it was first made: the old silent "chat" channel is removed.
    const val CHAT = "chat_messages"
    // Reminders the user set on Calendar entries: they asked for them, so they make a sound.
    const val REMINDER = "calendar_reminders"
    // Every alert goes in one bundle (before 0.7.0: one per channel, whose summaries had the channels' hash codes).
    private const val GROUP = "sol_alerts"
    private const val SUMMARY_ID = 3
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
        for ((id, name) in QUIET) {
            manager.createNotificationChannel(
                NotificationChannel(soundId(id), "$name (with sound)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Everything else the bridge would post to $name, with a sound " +
                        "(used when Sound for every alert is on)"
                }
            )
        }
        manager.deleteNotificationChannel("chat")
        // The per-channel bundle summaries from before 0.7.0.
        (QUIET.map { it.first } + QUIET.map { soundId(it.first) } + CHAT).forEach { manager.cancel(it.hashCode()) }
        manager.createNotificationChannel(
            NotificationChannel(CHAT, "Chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Global and alliance chat, when the Chat relay check is on"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(REMINDER, "Calendar reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders you set on Calendar entries"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "Running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The ongoing notification Android requires while Sol Watch runs"
            }
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * One alert. The title is the alert's emoji and headline, the text is the rest of it (nothing twice),
     * and the game time goes in the header next to its Alerts tab (topic) or, failing that, its channel's name. All alerts share one bundle.
     */
    fun show(
        context: Context, channel: String, header: String, priority: String, title: String, body: String, gameTime: String,
        topic: String = "",
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val androidChannel = when (priority) {
            "urgent" -> URGENT
            "chat" -> CHAT
            "reminder" -> REMINDER
            else -> (channel.takeIf { c -> QUIET.any { it.first == c } } ?: "corp")
                .let { if (Settings.soundAll(context)) soundId(it) else it }
        }
        val label = topic.ifBlank { channelLabel(channel) }  // "Contracts · Alliance jobs", else the bridge's channel
        val builder = NotificationCompat.Builder(context, androidChannel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.ifBlank { label })
            .setSubText(listOf(label, gameTime).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setColor(if (priority == "urgent") 0xFFE5484D.toInt() else accentArgb(context))
        if (body.isNotBlank()) {
            builder.setContentText(body.lines().joinToString(" "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        // Otherwise no setSilent: the channel decides, so a sound chosen in Android's settings plays.
        if (QuietHours.silences(context, priority)) builder.setSilent(true)
        when {
            priority == "urgent" -> builder.setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            QUIET.any { it.first == androidChannel } -> builder.setPriority(NotificationCompat.PRIORITY_LOW)
            else -> builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
        val manager = NotificationManagerCompat.from(context)
        manager.notify(nextId++, builder.build())
        summarize(context, androidChannel, title.ifBlank { body.lineSequence().firstOrNull().orEmpty() })
    }

    /**
     * The bundle's summary. Android files a bundle by its summary's channel, so the summary uses the most
     * important channel among the alerts still showing: an urgent alert isn't filed with the silent ones.
     * It never makes a sound itself (the alerts do).
     */
    private fun summarize(context: Context, latestChannel: String, latest: String) {
        val showing = context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.notification.group == GROUP && it.id != SUMMARY_ID }
        // Android may not list the alert just posted yet, so it's counted in by hand.
        val channels = showing.map { it.notification.channelId }.toSet() + latestChannel
        val summaryChannel = when {
            URGENT in channels -> URGENT
            CHAT in channels -> CHAT
            REMINDER in channels -> REMINDER
            channels.any { it.endsWith("_sound") } -> channels.first { it.endsWith("_sound") }
            else -> "corp"
        }
        val count = showing.size + if (showing.any { it.id == nextId - 1 }) 0 else 1
        NotificationManagerCompat.from(context).notify(
            SUMMARY_ID,
            NotificationCompat.Builder(context, summaryChannel)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (count == 1) "1 alert" else "$count alerts")
                .setContentText(latest)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)  // the alert sounds, not its summary
                .setSilent(true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        )
    }

    fun service(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Sol Watch is running")
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

    private fun channelLabel(channel: String) =
        QUIET.firstOrNull { it.first == channel }?.second ?: if (channel == "calendar") "Calendar" else "Sol Watch"
}
