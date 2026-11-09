package dev.nathanxu.dayplanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.Repeat
import dev.nathanxu.dayplanner.domain.Tags
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.LocalTime

@Entity(
    tableName = "tasks",
    indices = [Index("date"), Index("goalId"), Index(value = ["genKey"], unique = true)],
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** null = undated ("someday"). */
    val date: LocalDate?,
    /** Tag key (see [Tags]); built-in keys match the old category names. */
    val category: String = Tags.OTHER,
    val minutes: Int? = null,
    val time: LocalTime? = null,
    val goalId: Long? = null,
    val routineId: Long? = null,
    /** How much this task counts towards its goal when done (daily splits can be ×2, ×3…). */
    val units: Int = 1,
    /** Minutes before [time] to remind. null or −1 = no reminder (new to-dos start from the Settings default). */
    val remindBefore: Int? = null,
    /** Remind at exactly this moment instead (works without a [time]). */
    val remindAt: LocalDateTime? = null,
    /** Priority name (HIGH/LOW); null = normal. See [dev.nathanxu.dayplanner.domain.Priority]. */
    val priority: String? = null,
    /** Must be done by (separate from [date], the day you plan to do it). */
    val deadline: LocalDate? = null,
    /** Where it happens: a saved place's name or an address. Used for travel time. */
    val place: String? = null,
    /**
     * Set when "Plan my day" gave this to-do its time: comma-separated calendar event ids of its
     * blocks ("" if they weren't added to the calendar). null = you set the time yourself.
     */
    val plannedEvents: String? = null,
    /** All sittings of a split planned to-do ([dev.nathanxu.dayplanner.domain.Sittings]); null if one block. */
    val sittings: String? = null,
    /**
     * A routine or daily-split to-do you deleted: kept (hidden) so it isn't made again that day.
     * null/false = normal.
     */
    val skipped: Boolean? = null,
    /**
     * Set for app-generated tasks so they're created once:
     * "r:<routineId>:<epochDay>" routine instance, "g:<goalId>:<epochDay>" goal daily split,
     * "l:<goalId>:<millis>" a +1 log. null = a task you typed.
     */
    val genKey: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

object GenKeys {
    fun routine(routineId: Long, date: LocalDate) = "r:$routineId:${date.toEpochDay()}"
    fun split(goalId: Long, date: LocalDate) = "g:$goalId:${date.toEpochDay()}"
    fun log(goalId: Long) = "l:$goalId:${System.currentTimeMillis()}"
}

@Entity(tableName = "goals", indices = [Index("periodStart")])
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val period: Period,
    val periodStart: LocalDate,
    val target: Int = 1,
    val recurring: Boolean = false,
    val archived: Boolean = false,
    /** Tag key (see [Tags]); built-in keys match the old category names. */
    val category: String = Tags.OTHER,
    /** For count goals: auto-create paced to-dos. Off = log with +1 only. */
    val split: Boolean = true,
    /** Inclusive last day. null = end of the WEEK/MONTH period that starts at [periodStart]. */
    val endDate: LocalDate? = null,
    /** Breakdown size for the paced to-dos. null = daily (rows from before v1.3). */
    val cadence: Cadence? = null,
    /** Priority name for its to-dos (HIGH/LOW); null = normal. */
    val priority: String? = null,
)

/** [periodStart] is the plan's first day for every kind of plan. */
val Goal.start: LocalDate get() = periodStart
val Goal.end: LocalDate get() = endDate ?: period.lastDay(periodStart)
val Goal.effectiveCadence: Cadence get() = if (!split) Cadence.NONE else cadence ?: Cadence.DAILY
/** No end date: the target is per breakdown bucket (e.g. 3 a week), forever. */
val Goal.isOngoing: Boolean get() = endDate == dev.nathanxu.dayplanner.domain.Pacing.ONGOING_END
fun Goal.isActiveOn(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Tag key (see [Tags]); built-in keys match the old category names. */
    val category: String = Tags.OTHER,
    val minutes: Int? = null,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int,
    val active: Boolean = true,
    /** Needed for calendar sync. */
    val time: LocalTime? = null,
    /** User wants this routine as a recurring Google Calendar event. */
    val syncToCalendar: Boolean = true,
    /** CalendarContract event id once created. */
    val calendarEventId: Long? = null,
    /** Remind at the routine's time. Only true reminds (null = off, as for older rows). */
    val remind: Boolean? = null,
    /** Repeat rule (see [Repeat]); all null = weekly on [daysMask], as before v1.9. */
    val repeatUnit: String? = null,
    val repeatEvery: Int? = null,
    val repeatFrom: LocalDate? = null,
)

val Routine.repeat: Repeat get() = Repeat.of(repeatUnit, repeatEvery, daysMask, repeatFrom)

/** Copy with [r]'s rule. */
fun Routine.withRepeat(r: Repeat): Routine = copy(
    daysMask = r.daysMask, repeatFrom = r.from,
    repeatUnit = if (r == Repeat.weekly(r.daysMask)) null else r.unit.name,
    repeatEvery = if (r == Repeat.weekly(r.daysMask)) null else r.every,
)

@Entity(tableName = "travel_cache")
data class TravelCache(
    @PrimaryKey val key: String,
    val seconds: Int,
    val fetchedAt: Long,
)

/** Done units for one plan on one day. */
data class GoalDayUnits(val goalId: Long, val date: LocalDate, val units: Int)

/** Per-goal aggregates over linked tasks. */
data class GoalStats(
    val goalId: Long,
    val doneUnits: Int,
    val stepTotal: Int,
    val stepDone: Int,
    val todayUnits: Int,
    val todayDoneUnits: Int,
)

class Converters {
    @TypeConverter fun dateToLong(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun longToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
    @TypeConverter fun timeToInt(t: LocalTime?): Int? = t?.let { it.hour * 60 + it.minute }
    @TypeConverter fun intToTime(v: Int?): LocalTime? = v?.let { LocalTime.of(it / 60, it % 60) }
    // Wall-clock time (as if UTC), so a reminder stays at 08:00 even if the phone's time zone changes.
    @TypeConverter fun dateTimeToLong(t: LocalDateTime?): Long? = t?.toEpochSecond(ZoneOffset.UTC)
    @TypeConverter fun longToDateTime(v: Long?): LocalDateTime? = v?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
}
