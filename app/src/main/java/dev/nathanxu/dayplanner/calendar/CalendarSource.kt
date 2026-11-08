package dev.nathanxu.dayplanner.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import dev.nathanxu.dayplanner.domain.CalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads the Google Calendar(s) already synced to the phone through CalendarProvider.
 * No OAuth, works offline. Writes go through the Calendar app (see [insertIntent]).
 */
class CalendarSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun eventsOn(date: LocalDate): List<CalEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val begin = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()

        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
            Instances.SELF_ATTENDEE_STATUS,
        )

        val out = mutableListOf<CalEvent>()
        context.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(5) == 1
                // All-day events are stored in UTC midnight; timed events in the event zone.
                val evZone = if (allDay) ZoneOffset.UTC else zone
                out += CalEvent(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "(no title)",
                    start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), evZone),
                    end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(3)), evZone),
                    location = c.getString(4)?.takeIf { it.isNotBlank() },
                    allDay = allDay,
                    color = if (c.isNull(6)) null else c.getInt(6),
                )
            }
        }
        out
    }

    companion object {
        /** Opens the Calendar app pre-filled. No WRITE_CALENDAR permission needed. */
        fun insertIntent(title: String, start: LocalDateTime, minutes: Int, description: String? = null): Intent {
            val zone = ZoneId.systemDefault()
            val s = start.atZone(zone).toInstant().toEpochMilli()
            return Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s + minutes * 60_000L)
                .apply { description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) } }
        }

        fun openDayIntent(date: LocalDate): Intent {
            val ms = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
                ContentUris.appendId(it, ms)
            }.build()
            return Intent(Intent.ACTION_VIEW).setData(uri)
        }
    }
}
