package com.septaalfauzan.wanci.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.septaalfauzan.wanci.R

/**
 * Posts the monitoring notification and owns its channel.
 *
 * [latest] exists because `Service.startForeground` needs a `Notification` object, not a title and
 * a body — and it must be called within seconds of the service starting or the system kills the
 * process. [NotificationController.start] publishes the idle state first, so by the time the
 * service asks for the notification, one has been built and cached.
 *
 * Channel importance is `LOW`: the overlay already interrupts when a limit is crossed, and a
 * channel that could make sound would have this notification buzzing over another app.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var cached: Notification? = null

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun update(title: String, text: String) {
        val notification = build(title, text)
        cached = notification
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * The most recently built notification. Throws when nothing has been published yet, which is a
     * programming error rather than a runtime state: a service that calls this before
     * [NotificationController.start] has no notification to show the system.
     */
    fun latest(): Notification = checkNotNull(cached) { "no notification published yet" }

    private fun build(title: String, text: String): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(context.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            // PRIVATE, not SECRET: the shade shows the running figure, but the lock screen shows the
            // title only. Live content on the lock screen would name the app the user is on.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "wanci_monitoring"
        const val NOTIFICATION_ID = 2001
    }
}
