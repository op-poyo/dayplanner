package dev.nathanxu.dayplanner.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.domain.DayPlan
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.GoalDraft
import dev.nathanxu.dayplanner.domain.QuickAdd
import dev.nathanxu.dayplanner.domain.TaskDraft
import dev.nathanxu.dayplanner.domain.TimelineItem
import dev.nathanxu.dayplanner.domain.later
import dev.nathanxu.dayplanner.travel.TravelService
import dev.nathanxu.dayplanner.widget.TodayWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class GoalProgress(val goal: Goal, val done: Int) {
    val fraction: Float get() = (done.toFloat() / goal.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val complete: Boolean get() = done >= goal.target
}

data class TodayPlan(
    val items: List<TimelineItem>,
    val calendarConnected: Boolean,
    val travelEnabled: Boolean,
    val nextEvent: CalEvent?,
)

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val calendar: CalendarSource,
    private val travel: TravelService,
) {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

    // ---------- observe ----------

    fun observeToday(today: LocalDate): Flow<List<Task>> = db.tasks().observeToday(today)
    fun observeUpcoming(today: LocalDate): Flow<List<Task>> = db.tasks().observeUpcoming(today)
    fun observeRoutines(): Flow<List<Routine>> = db.routines().observeAll()

    /** Goals whose period contains [today], with progress. */
    fun observeGoals(today: LocalDate): Flow<List<GoalProgress>> =
        combine(db.goals().observeActive(), db.tasks().observeGoalCounts()) { goals, counts ->
            val byGoal = counts.associate { it.goalId to it.n }
            goals.filter { it.period.contains(it.periodStart, today) }
                .map { GoalProgress(it, byGoal[it.id] ?: 0) }
        }

    suspend fun todayTasks(today: LocalDate): List<Task> = db.tasks().today(today)

    // ---------- daily housekeeping ----------

    /** Idempotent: renew recurring goals, archive finished periods, create today's routine tasks. */
    suspend fun ensureFor(date: LocalDate) {
        val goals = db.goals()
        for (g in goals.active()) {
            val currentStart = g.period.startOf(date)
            if (g.periodStart.isBefore(currentStart)) {
                goals.update(g.copy(archived = true))
                if (g.recurring) goals.insert(g.copy(id = 0, periodStart = currentStart, archived = false))
            }
        }
        for (r in db.routines().active()) {
            if (Days.has(r.daysMask, date.dayOfWeek)) {
                db.tasks().insertIgnore(
                    Task(title = r.title, date = date, category = r.category, minutes = r.minutes, routineId = r.id)
                )
            }
        }
    }

    // ---------- tasks ----------

    /** Returns a short confirmation or error message for a snackbar. */
    suspend fun quickAdd(text: String, today: LocalDate): String {
        return when (val p = QuickAdd.parse(text, today)) {
            null -> "Nothing to add — type a title"
            is TaskDraft -> {
                val goal = p.goalHint?.let { hint ->
                    db.goals().active()
                        .filter { it.period.contains(it.periodStart, today) }
                        .firstOrNull { it.title.lowercase().contains(hint) }
                }
                db.tasks().insert(
                    Task(title = p.title, date = p.date, category = p.category, minutes = p.minutes, goalId = goal?.id)
                )
                refreshWidget()
                buildString {
                    append("Added “${p.title}”")
                    append(" · ").append(p.date?.let { if (it == today) "today" else dayFmt.format(it) } ?: "someday")
                    if (goal != null) append(" → ${goal.title}")
                    else if (p.goalHint != null) append(" (no goal matched ^${p.goalHint})")
                }
            }
            is GoalDraft -> {
                db.goals().insert(
                    Goal(
                        title = p.title, period = p.period, periodStart = p.period.startOf(today),
                        target = p.target, recurring = p.recurring,
                    )
                )
                "Goal added: ${p.title} ×${p.target} ${p.period.label.lowercase()}" + if (p.recurring) " ↻" else ""
            }
        }
    }

    suspend fun setDone(id: Long, done: Boolean) {
        db.tasks().setDone(id, done, if (done) System.currentTimeMillis() else null)
        refreshWidget()
    }

    suspend fun toggle(id: Long) {
        val t = db.tasks().get(id) ?: return
        setDone(id, !t.done)
    }

    suspend fun deleteTask(id: Long) {
        db.tasks().delete(id)
        refreshWidget()
    }

    // ---------- goals ----------

    /** "+1": logs a done task against the goal for today. */
    suspend fun logGoal(goal: Goal, today: LocalDate) {
        db.tasks().insert(
            Task(title = goal.title, date = today, goalId = goal.id, done = true, doneAt = System.currentTimeMillis())
        )
        refreshWidget()
    }

    suspend fun setRecurring(goal: Goal, recurring: Boolean) = db.goals().update(goal.copy(recurring = recurring))
    suspend fun deleteGoal(goal: Goal) = db.goals().delete(goal)

    // ---------- routines ----------

    suspend fun addRoutine(text: String, daysMask: Int, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Routines need a title (no @week/@month)"
        if (daysMask == 0) return "Pick at least one day"
        db.routines().insert(Routine(title = p.title, category = p.category, minutes = p.minutes, daysMask = daysMask))
        ensureFor(today)
        refreshWidget()
        return "Routine added: ${p.title} · ${Days.describe(daysMask)}"
    }

    suspend fun setRoutineActive(r: Routine, active: Boolean) {
        db.routines().update(r.copy(active = active))
        if (active) ensureFor(LocalDate.now())
        refreshWidget()
    }

    suspend fun deleteRoutine(r: Routine) = db.routines().delete(r)

    // ---------- calendar + travel ----------

    suspend fun todayPlan(now: LocalDateTime = LocalDateTime.now()): TodayPlan {
        val today = now.toLocalDate()
        if (!calendar.hasPermission()) return TodayPlan(emptyList(), false, travel.enabled, null)

        val events = calendar.eventsOn(today)
        val minutes = DayPlan.travelLegs(events).associateWith { (a, b) -> travel.minutes(a, b) }

        val windowStart = later(now.withSecond(0).withNano(0), today.atTime(LocalTime.of(7, 0)))
        val windowEnd = later(windowStart, today.atTime(LocalTime.of(23, 0)))
        val items = DayPlan.buildTimeline(events, { a, b -> minutes[a to b] }, windowStart, windowEnd)
        val next = events.filter { !it.allDay && it.end.isAfter(now) }.minByOrNull { it.start }
        return TodayPlan(items, true, travel.enabled, next)
    }

    suspend fun refreshWidget() {
        runCatching { TodayWidget().updateAll(context) }
            .onFailure { Log.w("Repository", "Widget update failed", it) }
    }
}
