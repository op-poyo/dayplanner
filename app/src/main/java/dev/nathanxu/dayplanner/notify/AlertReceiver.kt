package dev.nathanxu.dayplanner.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                    ACTION_ALERT -> Notifier.post(
                        app,
                        intent.getIntExtra(EXTRA_ID, 1),
                        Notifier.CH_LEAVE,
                        intent.getStringExtra(EXTRA_TITLE) ?: "Time to leave",
                        intent.getStringExtra(EXTRA_BODY) ?: "",
                    )
                    ACTION_MORNING -> {
                        val (title, body) = app.repository.morningSummary()
                        Notifier.post(app, MORNING_ID, Notifier.CH_SUMMARY, title, body)
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
        const val MORNING_ID = 7300

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
