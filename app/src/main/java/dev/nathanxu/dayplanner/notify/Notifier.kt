package dev.nathanxu.dayplanner.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.nathanxu.dayplanner.R
import dev.nathanxu.dayplanner.ui.MainActivity

/** Posts DayPlanner's notifications. Two channels so you can silence one in Android settings. */
object Notifier {
    const val CH_LEAVE = "leave_now"
    const val CH_SUMMARY = "morning_summary"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_LEAVE, "Leave now", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When to set off for your next event, based on travel time"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUMMARY, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your day at a glance"
            }
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun enabled(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Returns false if notifications are blocked, so callers can say so. */
    @SuppressLint("MissingPermission") // checked by enabled()
    fun post(context: Context, id: Int, channel: String, title: String, body: String): Boolean {
        if (!enabled(context)) return false
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_LEAVE) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (channel == CH_LEAVE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        return true
    }
}
