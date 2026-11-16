package dev.nathanxu.dayplanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** +1 logs and skipped generated to-dos are bookkeeping, never shown as to-dos. */
private const val NOT_LOG = "((genKey IS NULL OR genKey NOT LIKE 'l:%') AND (skipped IS NULL OR skipped = 0))"
private const val TODAY_FILTER = """
    $NOT_LOG AND ((date = :today) OR (date < :today AND done = 0))
"""
private const val TODAY_ORDER = """
    ORDER BY done ASC, CASE WHEN date IS NULL THEN 1 ELSE 0 END, date ASC,
    CASE WHEN time IS NULL THEN 1 ELSE 0 END, time ASC,
    CASE priority WHEN 'HIGH' THEN 0 WHEN 'LOW' THEN 2 ELSE 1 END, id ASC
"""

@Dao
interface TaskDao {
    /** Today's to-dos and overdue undone ones (someday ones live in the To-dos tab). */
    @Query("SELECT * FROM tasks WHERE $TODAY_FILTER $TODAY_ORDER")
    fun observeToday(today: LocalDate): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE $TODAY_FILTER $TODAY_ORDER")
    suspend fun today(today: LocalDate): List<Task>

    @Query("SELECT * FROM tasks WHERE $NOT_LOG AND date > :today AND done = 0 ORDER BY date ASC, id ASC")
    fun observeUpcoming(today: LocalDate): Flow<List<Task>>


    /** Dated tasks in [start, endExclusive), for the Calendar page. */
    @Query(
        """
        SELECT * FROM tasks WHERE date >= :start AND date < :endExclusive AND $NOT_LOG
        ORDER BY date ASC, CASE WHEN time IS NULL THEN 1 ELSE 0 END, time ASC, id ASC
        """
    )
    fun observeBetween(start: LocalDate, endExclusive: LocalDate): Flow<List<Task>>

    /** Every open to-do (any date or none) plus ones ticked since [sinceMillis], for the To-dos view. */
    @Query(
        """
        SELECT * FROM tasks WHERE $NOT_LOG AND (done = 0 OR doneAt >= :sinceMillis)
        ORDER BY done ASC, CASE WHEN date IS NULL THEN 1 ELSE 0 END, date ASC,
        CASE WHEN time IS NULL THEN 1 ELSE 0 END, time ASC, id ASC
        """
    )
    fun observeAllTodos(sinceMillis: Long): Flow<List<Task>>

    /** Open to-dos with a time in [from, toExclusive), for reminders. */
    @Query(
        """
        SELECT * FROM tasks WHERE $NOT_LOG AND done = 0 AND time IS NOT NULL
        AND date >= :from AND date < :toExclusive
        """
    )
    suspend fun timedOpenBetween(from: LocalDate, toExclusive: LocalDate): List<Task>

    /** Open to-dos with an exact-time reminder in [from, toExclusive). */
    @Query("SELECT * FROM tasks WHERE $NOT_LOG AND done = 0 AND remindAt >= :from AND remindAt < :toExclusive")
    suspend fun remindAtBetween(from: java.time.LocalDateTime, toExclusive: java.time.LocalDateTime): List<Task>

    /** For the weekly review: dated in [from, to), ticked in [fromMs, toMs), or open with a deadline before [to]. */
    @Query(
        """
        SELECT * FROM tasks WHERE (date >= :from AND date < :to) OR (doneAt >= :fromMs AND doneAt < :toMs)
        OR (done = 0 AND deadline IS NOT NULL AND deadline < :to)
        """
    )
    suspend fun forReview(from: LocalDate, to: LocalDate, fromMs: Long, toMs: Long): List<Task>

    @Query("SELECT * FROM tasks") suspend fun all(): List<Task>

    /** Search: to-dos whose title or place contains [q], or with one of [tags] (open first). */
    @Query(
        """
        SELECT * FROM tasks WHERE $NOT_LOG AND (title LIKE '%' || :q || '%' OR place LIKE '%' || :q || '%' OR category IN (:tags))
        ORDER BY done ASC, CASE WHEN date IS NULL THEN 1 ELSE 0 END, date DESC, id DESC LIMIT 200
        """
    )
    suspend fun search(q: String, tags: List<String>): List<Task>
    @Query("SELECT COUNT(*) FROM tasks") suspend fun count(): Int
    @Query("DELETE FROM tasks") suspend fun deleteAll()
    @Insert suspend fun insertAll(tasks: List<Task>)

    /** Every to-do linked to a goal (done or not, not skipped), for goal progress. */
    @Query("SELECT * FROM tasks WHERE goalId IS NOT NULL AND (skipped IS NULL OR skipped = 0)")
    fun observeLinked(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE goalId IS NOT NULL AND (skipped IS NULL OR skipped = 0)")
    suspend fun linked(): List<Task>

    /** The goal's most recent linked to-do with a duration (for "same length as last time"). */
    @Query("SELECT * FROM tasks WHERE goalId = :goalId AND minutes IS NOT NULL ORDER BY COALESCE(doneAt, createdAt) DESC LIMIT 1")
    suspend fun latestLinked(goalId: Long): Task?

    /** Open planned to-dos split into sittings, for a reminder per sitting. */
    @Query("SELECT * FROM tasks WHERE $NOT_LOG AND done = 0 AND sittings IS NOT NULL")
    suspend fun withSittings(): List<Task>

    /** Every open to-do (not +1 logs), for the planner. */
    @Query("SELECT * FROM tasks WHERE $NOT_LOG AND done = 0")
    suspend fun open(): List<Task>

    /** Open to-dos the planner placed, so slipped ones can be offered for re-planning. */
    @Query("SELECT * FROM tasks WHERE $NOT_LOG AND done = 0 AND plannedEvents IS NOT NULL AND date IS NOT NULL AND time IS NOT NULL")
    fun observePlannedOpen(): Flow<List<Task>>

    @Insert suspend fun insert(task: Task): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: Task): Long

    @Update suspend fun update(task: Task)


    @Query("SELECT * FROM tasks WHERE genKey = :key")
    suspend fun byKey(key: String): Task?




    @Query("DELETE FROM tasks WHERE genKey = :key AND done = 0")
    suspend fun deleteOpenByKey(key: String)

    /** Points every to-do a repeating to-do made at [goalId] (so its past ticks count too). */
    @Query("UPDATE tasks SET goalId = :goalId WHERE genKey LIKE 'r:' || :routineId || ':%'")
    suspend fun relinkRoutine(routineId: Long, goalId: Long?)

    @Query("UPDATE tasks SET done = :done, doneAt = :at WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, at: Long?)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)



    /**
     * Unticked generated tasks from earlier days. Routine misses just expire; a goal's missed
     * share is folded into the next day's quota, so neither should pile up as overdue.
     */
    @Query("DELETE FROM tasks WHERE genKey LIKE :prefix || '%' AND done = 0 AND date < :date")
    suspend fun deleteStaleGenerated(prefix: String, date: LocalDate)

}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE archived = 0 ORDER BY period ASC, id ASC")
    fun observeActive(): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE archived = 0")
    suspend fun active(): List<Goal>

    /** Plans that have ended (one-offs only; repeating ones live on as their next instance). */
    @Query("SELECT * FROM goals WHERE archived = 1 AND recurring = 0 ORDER BY periodStart DESC LIMIT 30")
    fun observeFinished(): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun get(id: Long): Goal?

    @Insert suspend fun insert(goal: Goal): Long
    @Insert suspend fun insertAll(goals: List<Goal>)
    @Query("SELECT * FROM goals") suspend fun all(): List<Goal>
    @Query("DELETE FROM goals") suspend fun deleteAll()
    @Update suspend fun update(goal: Goal)
    @Delete suspend fun delete(goal: Goal)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY active DESC, CASE WHEN time IS NULL THEN 1 ELSE 0 END, time ASC, id ASC")
    fun observeAll(): Flow<List<Routine>>

    @Query("SELECT * FROM routines")
    suspend fun all(): List<Routine>

    @Query("SELECT * FROM routines WHERE active = 1")
    suspend fun active(): List<Routine>

    @Insert suspend fun insert(routine: Routine): Long
    @Insert suspend fun insertAll(routines: List<Routine>)
    @Query("DELETE FROM routines") suspend fun deleteAll()
    @Update suspend fun update(routine: Routine)
    @Delete suspend fun delete(routine: Routine)
}

@Dao
interface TravelDao {
    @Query("SELECT * FROM travel_cache WHERE `key` = :key")
    suspend fun get(key: String): TravelCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TravelCache)
}
