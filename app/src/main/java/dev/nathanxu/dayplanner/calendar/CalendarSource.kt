package dev.nathanxu.dayplanner.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.domain.Days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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

    fun hasWritePermission(): Boolean = hasPermission() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /** Best calendar to write to: a Google account's own primary calendar if there is one. */
    suspend fun writableCalendarId(): Long? = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        var best: Long? = null
        var bestScore = -1
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)
            ?.use { cur ->
                while (cur.moveToNext()) {
                    val google = cur.getString(1) == "com.google"
                    val primary = cur.getInt(2) == 1 || (cur.getString(3) != null && cur.getString(3) == cur.getString(4))
                    val score = (if (google) 2 else 0) + (if (primary) 1 else 0)
                    if (score > bestScore) {
                        best = cur.getLong(0)
                        bestScore = score
                    }
                }
            }
        best
    }

    /**
     * Inserts a weekly recurring event straight into the calendar provider; the Google account's
     * sync adapter pushes it to Google Calendar. Returns the event id, or null on failure.
     */
    suspend fun createRecurring(
        calendarId: Long,
        title: String,
        daysMask: Int,
        time: LocalTime,
        minutes: Int,
        firstDate: LocalDate,
    ): Long? = withContext(Dispatchers.IO) {
        if (!hasWritePermission() || daysMask == 0) return@withContext null
        val zone = ZoneId.systemDefault()
        val byDay = DayOfWeek.entries.filter { Days.has(daysMask, it) }.joinToString(",") { it.name.take(2) }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, "Routine from DayPlanner. Change it in the app — edits here are overwritten.")
            put(CalendarContract.Events.DTSTART, firstDate.atTime(time).atZone(zone).toInstant().toEpochMilli())
            // Recurring events take DURATION instead of DTEND.
            put(CalendarContract.Events.DURATION, "PT${minutes.coerceAtLeast(5)}M")
            put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;WKST=MO;BYDAY=$byDay")
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        }.onFailure { Log.w(TAG, "Insert failed", it) }.getOrNull()
    }

    suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext false
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
            ) > 0
        }.getOrDefault(false)
    }

    /** False if the event was deleted (e.g. by you in Google Calendar). Null if we can't tell. */
    suspend fun eventExists(eventId: Long): Boolean? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(eventId.toString()),
                null,
            )?.use { it.count > 0 }
        }.getOrNull()
    }

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
        private const val TAG = "CalendarSource"

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
