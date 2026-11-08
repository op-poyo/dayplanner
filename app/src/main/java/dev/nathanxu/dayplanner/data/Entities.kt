package dev.nathanxu.dayplanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.nathanxu.dayplanner.domain.Category
import dev.nathanxu.dayplanner.domain.Period
import java.time.LocalDate

@Entity(
    tableName = "tasks",
    indices = [Index("date"), Index("goalId"), Index(value = ["routineId", "date"], unique = true)],
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** null = undated ("someday"). */
    val date: LocalDate?,
    val category: Category = Category.OTHER,
    val minutes: Int? = null,
    val goalId: Long? = null,
    val routineId: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "goals", indices = [Index("periodStart")])
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val period: Period,
    val periodStart: LocalDate,
    val target: Int = 1,
    val recurring: Boolean = false,
    val archived: Boolean = false,
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
)

@Entity(tableName = "travel_cache")
data class TravelCache(
    @PrimaryKey val key: String,
    val seconds: Int,
    val fetchedAt: Long,
)

/** Done-task count per goal. */
data class GoalCount(val goalId: Long, val n: Int)

class Converters {
    @TypeConverter fun dateToLong(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun longToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
}
