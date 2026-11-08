package dev.nathanxu.dayplanner.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
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

/** One calendar on the phone (a Google account usually has several). */
data class CalInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int?,
    val writable: Boolean,
    val primary: Boolean,
    val visible: Boolean,
) {
    val isGoogle: Boolean get() = accountType == GOOGLE

    companion object {
        const val GOOGLE = "com.google"
    }
}

/**
 * Reads and writes the calendars synced to the phone through CalendarProvider — no OAuth.
 * Writes land in the provider and Google's sync adapter uploads them; [requestSync] nudges it.
 */
class CalendarSource(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean = hasPermission() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /** Every calendar on the device, Google accounts first. */
    suspend fun calendars(): List<CalInfo> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.OWNER_ACCOUNT,
            Calendars.VISIBLE,
        )
        val out = mutableListOf<CalInfo>()
        runCatching {
            context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2) ?: continue
                    val owner = c.getString(7)
                    out += CalInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: account,
                        accountName = account,
                        accountType = c.getString(3) ?: "",
                        color = if (c.isNull(4)) null else c.getInt(4),
                        writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        primary = c.getInt(6) == 1 || owner == account,
                        visible = c.getInt(8) == 1,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Listing calendars failed", it) }
        out.sortedWith(compareByDescending<CalInfo> { it.isGoogle }.thenBy { it.accountName }.thenByDescending { it.primary })
    }

    /**
     * Event instances overlapping [start, endExclusive). [calendarIds] limits which calendars are
     * read (null = all). Declined invitations are skipped.
     */
    suspend fun eventsBetween(start: LocalDate, endExclusive: LocalDate, calendarIds: Set<Long>?): List<CalEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || calendarIds?.isEmpty() == true) return@withContext emptyList()
            val zone = ZoneId.systemDefault()
            val uri = Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, start.atStartOfDay(zone).toInstant().toEpochMilli())
                ContentUris.appendId(it, endExclusive.atStartOfDay(zone).toInstant().toEpochMilli())
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
            // Ids are longs from the provider, so building the IN list directly is safe.
            val selection = calendarIds?.let { ids -> "${Instances.CALENDAR_ID} IN (${ids.joinToString(",")})" }

            val out = mutableListOf<CalEvent>()
            runCatching {
                context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        val allDay = c.getInt(5) == 1
                        // All-day events are stored at UTC midnight; timed events as absolute instants.
                        val evZone = if (allDay) ZoneOffset.UTC else zone
                        out += CalEvent(
                            id = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                            start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), evZone),
                            end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(3)), evZone),
                            location = c.getString(4)?.takeIf { it.isNotBlank() },
                            allDay = allDay,
                            color = if (c.isNull(6)) null else c.getInt(6),
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Reading events failed", it) }
            out
        }

    suspend fun eventsOn(date: LocalDate, calendarIds: Set<Long>?): List<CalEvent> =
        eventsBetween(date, date.plusDays(1), calendarIds)

    /**
     * Inserts a weekly recurring event into [calendarId]. Returns the event id, or throws with a
     * readable message so the UI can show why it failed.
     */
    suspend fun createRecurring(
        calendarId: Long,
        title: String,
        daysMask: Int,
        time: LocalTime,
        minutes: Int,
        firstDate: LocalDate,
    ): Long = withContext(Dispatchers.IO) {
        require(hasWritePermission()) { "No calendar write permission" }
        require(daysMask != 0) { "No days selected" }
        val zone = ZoneId.systemDefault()
        val byDay = DayOfWeek.entries.filter { Days.has(daysMask, it) }.joinToString(",") { it.name.take(2) }
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, "Routine from DayPlanner. Change it in the app — edits made here get replaced.")
            put(Events.DTSTART, firstDate.atTime(time).atZone(zone).toInstant().toEpochMilli())
            // Recurring events take DURATION (RFC 5545) instead of DTEND.
            put(Events.DURATION, "P${minutes.coerceAtLeast(5) * 60}S")
            put(Events.RRULE, "FREQ=WEEKLY;WKST=MO;BYDAY=$byDay")
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            ?: error("Calendar provider rejected the event")
        uri.lastPathSegment?.toLongOrNull() ?: error("Calendar provider returned no event id")
    }

    suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext false
        runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
        }.getOrDefault(false)
    }

    /** Calendar id of a live event, or null if it no longer exists (e.g. you deleted it). */
    suspend fun eventCalendar(eventId: Long): Long? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        runCatching {
            context.contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(Events.CALENDAR_ID),
                "${Events._ID} = ? AND ${Events.DELETED} = 0",
                arrayOf(eventId.toString()),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
    }

    /** Asks Android to sync this Google account's calendars now rather than whenever it gets round to it. */
    fun requestSync(accountName: String, accountType: String = CalInfo.GOOGLE) {
        runCatching {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(Account(accountName, accountType), CalendarContract.AUTHORITY, extras)
        }.onFailure { Log.w(TAG, "requestSync failed", it) }
    }

    companion object {
        private const val TAG = "CalendarSource"

        /** Opens the Calendar app pre-filled. No WRITE_CALENDAR permission needed. */
        fun insertIntent(title: String, start: LocalDateTime, minutes: Int, description: String? = null): Intent {
            val zone = ZoneId.systemDefault()
            val s = start.atZone(zone).toInstant().toEpochMilli()
            return Intent(Intent.ACTION_INSERT)
                .setData(Events.CONTENT_URI)
                .putExtra(Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s + minutes * 60_000L)
                .apply { description?.let { putExtra(Events.DESCRIPTION, it) } }
        }

        fun openDayIntent(date: LocalDate): Intent {
            val ms = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
                ContentUris.appendId(it, ms)
            }.build()
            return Intent(Intent.ACTION_VIEW).setData(uri)
        }

        /** Opens one event (one occurrence if [start] is given) in the Calendar app. */
        fun viewEventIntent(eventId: Long, start: LocalDateTime?, end: LocalDateTime?): Intent {
            val zone = ZoneId.systemDefault()
            return Intent(Intent.ACTION_VIEW)
                .setData(ContentUris.withAppendedId(Events.CONTENT_URI, eventId))
                .apply {
                    start?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.atZone(zone).toInstant().toEpochMilli()) }
                    end?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.atZone(zone).toInstant().toEpochMilli()) }
                }
        }
    }
}
