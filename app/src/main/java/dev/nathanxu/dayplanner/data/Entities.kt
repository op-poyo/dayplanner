package dev.nathanxu.dayplanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.nathanxu.dayplanner.domain.Category
import dev.nathanxu.dayplanner.domain.Period
import java.time.LocalDate
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
    val category: Category = Category.OTHER,
    val minutes: Int? = null,
    val time: LocalTime? = null,
    val goalId: Long? = null,
    val routineId: Long? = null,
    /** How much this task counts towards its goal when done (daily splits can be ×2, ×3…). */
    val units: Int = 1,
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
    val category: Category = Category.OTHER,
    /** For count goals: auto-create a paced daily to-do. */
    val split: Boolean = true,
)

@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: Category = Category.OTHER,
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
)

@Entity(tableName = "travel_cache")
data class TravelCache(
    @PrimaryKey val key: String,
    val seconds: Int,
    val fetchedAt: Long,
)

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
}
