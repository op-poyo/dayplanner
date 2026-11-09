package dev.nathanxu.dayplanner.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.nathanxu.dayplanner.domain.Alert
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** One alarm we've set, for the "Scheduled" list in Settings. */
data class ScheduledAlert(val key: String, val at: LocalDateTime, val title: String)

/**
 * Sets and replaces DayPlanner's alarms with AlarmManager. Exact when Android allows it (needed
 * for "leave now" to be on time), otherwise the closest inexact equivalent.
 */
class AlertScheduler(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val sp = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** Cancels everything set last time, then sets [alerts] plus the morning summary. */
    fun replaceAll(alerts: List<Alert>, morningAt: LocalDateTime?, reviewAt: LocalDateTime? = null) {
        stored().forEach { cancel(it.key) }

        val scheduled = mutableListOf<ScheduledAlert>()
        for (a in alerts) {
            val intent = Intent(context, AlertReceiver::class.java)
                .setAction(AlertReceiver.ACTION_ALERT)
                .setData(uriFor(a.key))
                .putExtra(AlertReceiver.EXTRA_TITLE, a.title)
                .putExtra(AlertReceiver.EXTRA_BODY, a.body)
                .putExtra(AlertReceiver.EXTRA_ID, a.key.hashCode())
                .putExtra(AlertReceiver.EXTRA_KIND, a.kind.name)
                .apply { a.taskId?.let { putExtra(AlertReceiver.EXTRA_TASK, it) } }
            set(a.at, pending(a.key, intent))
            scheduled += ScheduledAlert(a.key, a.at, a.title)
        }
        morningAt?.let {
            val intent = Intent(context, AlertReceiver::class.java)
                .setAction(AlertReceiver.ACTION_MORNING)
                .setData(uriFor(MORNING_KEY))
            set(it, pending(MORNING_KEY, intent))
            scheduled += ScheduledAlert(MORNING_KEY, it, "Morning summary")
        }
        reviewAt?.let {
            val intent = Intent(context, AlertReceiver::class.java)
                .setAction(AlertReceiver.ACTION_REVIEW)
                .setData(uriFor(REVIEW_KEY))
            set(it, pending(REVIEW_KEY, intent))
            scheduled += ScheduledAlert(REVIEW_KEY, it, "Weekly review")
        }
        save(scheduled)
        scheduleDailyRefresh()
    }

    /**
     * One-off reminder [minutes] from now (Snooze). Not part of the replaceable set, so a re-plan
     * in the meantime doesn't cancel it.
     */
    fun snooze(taskId: Long, title: String, body: String, minutes: Long = 10) {
        val key = "snooze:$taskId"
        val intent = Intent(context, AlertReceiver::class.java)
            .setAction(AlertReceiver.ACTION_ALERT)
            .setData(uriFor(key))
            .putExtra(AlertReceiver.EXTRA_TITLE, title)
            .putExtra(AlertReceiver.EXTRA_BODY, body)
            .putExtra(AlertReceiver.EXTRA_ID, ("todo:$taskId").hashCode())
            .putExtra(AlertReceiver.EXTRA_KIND, "TODO")
            .putExtra(AlertReceiver.EXTRA_TASK, taskId)
        set(LocalDateTime.now().plusMinutes(minutes), pending(key, intent))
    }

    /**
     * Just after midnight every day: makes the new day's to-dos, re-plans alerts and redraws the
     * widget, so it shows the new day even if the app isn't opened.
     */
    private fun scheduleDailyRefresh() {
        val next = LocalDate.now().plusDays(1).atTime(LocalTime.of(0, 1))
        val intent = Intent(context, AlertReceiver::class.java)
            .setAction(AlertReceiver.ACTION_REFRESH)
            .setData(uriFor(REFRESH_KEY))
        set(next, pending(REFRESH_KEY, intent)) // exact when allowed, so the widget flips at midnight
    }

    fun upcoming(now: LocalDateTime = LocalDateTime.now()): List<ScheduledAlert> =
        stored().filter { it.at.isAfter(now) }.sortedBy { it.at }

    private fun set(at: LocalDateTime, pi: PendingIntent) {
        runCatching {
            if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(at), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(at), pi)
        }.onFailure { Log.w(TAG, "Alarm not set for $at", it) }
    }

    private fun cancel(key: String) {
        val action = when (key) {
            MORNING_KEY -> AlertReceiver.ACTION_MORNING
            REVIEW_KEY -> AlertReceiver.ACTION_REVIEW
            else -> AlertReceiver.ACTION_ALERT
        }
        val intent = Intent(context, AlertReceiver::class.java).setAction(action).setData(uriFor(key))
        PendingIntent.getBroadcast(
            context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    private fun pending(key: String, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The data URI makes each alarm's intent distinct, so they don't overwrite each other. */
    private fun uriFor(key: String): Uri = Uri.parse("dayplanner://alert/" + Uri.encode(key))

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun stored(): List<ScheduledAlert> = runCatching {
        val arr = JSONArray(sp.getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScheduledAlert(o.getString("k"), LocalDateTime.parse(o.getString("at")), o.getString("t"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<ScheduledAlert>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.key).put("at", it.at.toString()).put("t", it.title)) }
        sp.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val TAG = "AlertScheduler"
        const val KEY_LIST = "scheduled"
        const val MORNING_KEY = "morning"
        const val REFRESH_KEY = "refresh"
        const val REVIEW_KEY = "review"
    }
}
