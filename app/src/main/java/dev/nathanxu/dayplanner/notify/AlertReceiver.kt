package dev.nathanxu.dayplanner.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import android.util.Log
import dev.nathanxu.dayplanner.repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires alerts, builds the morning summary at the moment it's due (so it's current), and re-plans
 * alarms after a reboot, an app update, a change in exact-alarm permission, or the daily refresh.
 */
class AlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_ALERT -> {
                        val id = intent.getIntExtra(EXTRA_ID, 1)
                        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
                        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
                        val taskId = intent.getLongExtra(EXTRA_TASK, -1L)
                        if (intent.getStringExtra(EXTRA_KIND) == "TODO" && taskId >= 0) {
                            // Skip if it was ticked off since the alarm was set.
                            if (app.repository.isOpen(taskId)) {
                                Notifier.post(
                                    app, id, Notifier.CH_TODO, title, body,
                                    listOf(
                                        action(app, "Done", ACTION_DONE, taskId, id, title, body),
                                        action(app, "Snooze 10 min", ACTION_SNOOZE, taskId, id, title, body),
                                    ),
                                )
                            }
                        } else {
                            Notifier.post(app, id, Notifier.CH_LEAVE, title, body)
                        }
                    }
                    ACTION_DONE -> {
                        Notifier.cancel(app, intent.getIntExtra(EXTRA_ID, 1))
                        app.repository.markDone(intent.getLongExtra(EXTRA_TASK, -1L))
                        app.repository.flushChanges()
                    }
                    ACTION_SNOOZE -> {
                        Notifier.cancel(app, intent.getIntExtra(EXTRA_ID, 1))
                        app.repository.alerts.snooze(
                            intent.getLongExtra(EXTRA_TASK, -1L),
                            intent.getStringExtra(EXTRA_TITLE) ?: "Reminder",
                            "Snoozed · " + (intent.getStringExtra(EXTRA_BODY) ?: ""),
                        )
                    }
                    ACTION_MORNING -> {
                        val (title, body) = app.repository.morningSummary()
                        Notifier.post(app, MORNING_ID, Notifier.CH_SUMMARY, title, body)
                        app.repository.scheduleAlerts()
                    }
                    ACTION_REVIEW -> {
                        val s = app.repository.weekSummary(dev.nathanxu.dayplanner.domain.WeeklyReview.weekOf(java.time.LocalDate.now()))
                        val body = buildString {
                            append("${s.done} done this week")
                            if (s.slipped.isNotEmpty()) append(" · ${s.slipped.size} slipped")
                            if (s.nextWeekDeadlines.isNotEmpty()) append(" · ${s.nextWeekDeadlines.size} due next week")
                            append(". Tap to review and plan next week.")
                        }
                        Notifier.post(app, REVIEW_ID, Notifier.CH_SUMMARY, "Weekly review", body, open = Notifier.OPEN_REVIEW)
                        app.repository.scheduleAlerts()
                    }
                    else -> app.repository.scheduleAlerts() // refresh, boot, update, permission change
                }
            } catch (e: Exception) {
                Log.w("AlertReceiver", "Failed handling ${intent.action}", e)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALERT = "dev.nathanxu.dayplanner.ALERT"
        const val ACTION_MORNING = "dev.nathanxu.dayplanner.MORNING"
        const val ACTION_REFRESH = "dev.nathanxu.dayplanner.REFRESH_ALERTS"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TASK = "task_id"
        const val ACTION_DONE = "dev.nathanxu.dayplanner.TODO_DONE"
        const val ACTION_SNOOZE = "dev.nathanxu.dayplanner.TODO_SNOOZE"

        /** A notification button that comes back to this receiver. */
        private fun action(
            context: Context, label: String, action: String, taskId: Long, notifId: Int, title: String, body: String,
        ): NotificationCompat.Action {
            val intent = Intent(context, AlertReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("dayplanner://todo/$taskId/$action"))
                .putExtra(EXTRA_TASK, taskId)
                .putExtra(EXTRA_ID, notifId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
            val pi = PendingIntent.getBroadcast(
                context, (action + taskId).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }
        const val MORNING_ID = 7300
        const val REVIEW_ID = 7301
        const val ACTION_REVIEW = "dev.nathanxu.dayplanner.REVIEW"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
