package dev.nathanxu.dayplanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

private const val TODAY_FILTER = """
    (date = :today) OR (date < :today AND done = 0) OR (date IS NULL AND done = 0)
"""
private const val TODAY_ORDER = """
    ORDER BY done ASC, CASE WHEN date IS NULL THEN 1 ELSE 0 END, date ASC, id ASC
"""

@Dao
interface TaskDao {
    /** Today's tasks, overdue undone tasks, and undated undone tasks. */
    @Query("SELECT * FROM tasks WHERE $TODAY_FILTER $TODAY_ORDER")
    fun observeToday(today: LocalDate): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE $TODAY_FILTER $TODAY_ORDER")
    suspend fun today(today: LocalDate): List<Task>

    @Query("SELECT * FROM tasks WHERE date > :today AND done = 0 ORDER BY date ASC, id ASC")
    fun observeUpcoming(today: LocalDate): Flow<List<Task>>

    @Insert suspend fun insert(task: Task): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: Task): Long

    @Query("UPDATE tasks SET done = :done, doneAt = :at WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, at: Long?)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT goalId AS goalId, COUNT(*) AS n FROM tasks WHERE done = 1 AND goalId IS NOT NULL GROUP BY goalId")
    fun observeGoalCounts(): Flow<List<GoalCount>>
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE archived = 0 ORDER BY period ASC, id ASC")
    fun observeActive(): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE archived = 0")
    suspend fun active(): List<Goal>

    @Query("SELECT * FROM goals WHERE recurring = 1")
    suspend fun recurring(): List<Goal>

    @Insert suspend fun insert(goal: Goal): Long
    @Update suspend fun update(goal: Goal)
    @Delete suspend fun delete(goal: Goal)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY active DESC, id ASC")
    fun observeAll(): Flow<List<Routine>>

    @Query("SELECT * FROM routines WHERE active = 1")
    suspend fun active(): List<Routine>

    @Insert suspend fun insert(routine: Routine): Long
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
