package dev.nathanxu.dayplanner.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.domain.Category
import dev.nathanxu.dayplanner.domain.DayPlan
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.GoalDraft
import dev.nathanxu.dayplanner.domain.GoalStatus
import dev.nathanxu.dayplanner.domain.Pacing
import dev.nathanxu.dayplanner.domain.QuickAdd
import dev.nathanxu.dayplanner.domain.TaskDraft
import dev.nathanxu.dayplanner.domain.TimelineItem
import dev.nathanxu.dayplanner.domain.later
import dev.nathanxu.dayplanner.travel.TravelService
import dev.nathanxu.dayplanner.widget.TodayWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class GoalProgress(val goal: Goal, val status: GoalStatus, val steps: List<Task>)

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
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    /** ensureFor / syncRoutines can be triggered by the app and the widget at once. */
    private val housekeeping = Mutex()

    // ---------- observe ----------

    fun observeToday(today: LocalDate): Flow<List<Task>> = db.tasks().observeToday(today)
    fun observeUpcoming(today: LocalDate): Flow<List<Task>> = db.tasks().observeUpcoming(today)
    fun observeRoutines(): Flow<List<Routine>> = db.routines().observeAll()

    /** Goals whose period contains [today], with % / pace / today's share and their steps. */
    fun observeGoals(today: LocalDate): Flow<List<GoalProgress>> = combine(
        db.goals().observeActive(),
        db.tasks().observeGoalStats(today),
        db.tasks().observeSteps(),
    ) { goals, stats, steps ->
        val statsBy = stats.associateBy { it.goalId }
        val stepsBy = steps.groupBy { it.goalId }
        goals.filter { it.period.contains(it.periodStart, today) }.map { g ->
            val s = statsBy[g.id]
            GoalProgress(
                goal = g,
                status = GoalStatus.of(
                    target = g.target, period = g.period, start = g.periodStart, today = today,
                    doneUnits = s?.doneUnits ?: 0, stepTotal = s?.stepTotal ?: 0, stepDone = s?.stepDone ?: 0,
                    todayUnits = s?.todayUnits ?: 0, todayDoneUnits = s?.todayDoneUnits ?: 0,
                ),
                steps = stepsBy[g.id].orEmpty(),
            )
        }
    }

    suspend fun todayTasks(today: LocalDate): List<Task> = db.tasks().today(today)

    // ---------- daily housekeeping ----------

    /**
     * Idempotent, run on app open, widget refresh and after edits:
     * clears yesterday's unticked generated tasks, renews recurring goals, creates today's
     * routine tasks and each count goal's paced daily share.
     */
    suspend fun ensureFor(date: LocalDate) = housekeeping.withLock {
        val tasks = db.tasks()
        val goals = db.goals()
        tasks.deleteStaleGenerated("r:", date)
        tasks.deleteStaleGenerated("g:", date)

        for (g in goals.active()) {
            val currentStart = g.period.startOf(date)
            if (g.periodStart.isBefore(currentStart)) {
                tasks.deleteOpenSplits(g.id)
                goals.update(g.copy(archived = true))
                if (g.recurring) goals.insert(g.copy(id = 0, periodStart = currentStart, archived = false))
            }
        }

        for (g in goals.active()) {
            if (!g.split || g.target <= 1 || !g.period.contains(g.periodStart, date)) continue
            val doneBefore = tasks.doneUnitsBefore(g.id, date)
            val q = Pacing.quotaToday(g.target, g.period, g.periodStart, date, doneBefore)
            if (q > 0) {
                tasks.insertIgnore(
                    Task(
                        title = if (q > 1) "${g.title} ×$q" else g.title,
                        date = date, category = g.category, goalId = g.id,
                        units = q, genKey = GenKeys.split(g.id, date),
                    )
                )
            }
        }

        for (r in db.routines().active()) {
            if (Days.has(r.daysMask, date.dayOfWeek)) {
                tasks.insertIgnore(
                    Task(
                        title = r.title, date = date, category = r.category, minutes = r.minutes,
                        time = r.time, routineId = r.id, genKey = GenKeys.routine(r.id, date),
                    )
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
                insertDraft(p, goal)
                buildString {
                    append("Added “${p.title}”")
                    append(" · ").append(p.date?.let { if (it == today) "today" else dayFmt.format(it) } ?: "someday")
                    p.time?.let { append(" ").append(hm.format(it)) }
                    if (goal != null) append(" → ${goal.title}")
                    else if (p.goalHint != null) append(" (no goal matched ^${p.goalHint})")
                }
            }
            is GoalDraft -> {
                db.goals().insert(
                    Goal(
                        title = p.title, period = p.period, periodStart = p.period.startOf(today),
                        target = p.target, recurring = p.recurring, category = p.category,
                    )
                )
                ensureFor(today)
                refreshWidget()
                val split = if (p.target > 1) ", split into daily to-dos" else ""
                "Goal added: ${p.title} ×${p.target} ${p.period.label.lowercase()}$split" + if (p.recurring) " ↻" else ""
            }
        }
    }

    /** Adds a step to a goal from the goal card. */
    suspend fun addStep(goal: Goal, text: String, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Steps need a title (no @week/@month)"
        insertDraft(p, goal)
        return "Step added to ${goal.title}"
    }

    private suspend fun insertDraft(p: TaskDraft, goal: Goal?) {
        db.tasks().insert(
            Task(
                title = p.title, date = p.date, time = p.time, minutes = p.minutes, goalId = goal?.id,
                category = if (p.category == Category.OTHER && goal != null) goal.category else p.category,
            )
        )
        refreshWidget()
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

    /** "+1": logs a done unit against the goal for today. */
    suspend fun logGoal(goal: Goal, today: LocalDate) {
        db.tasks().insert(
            Task(
                title = goal.title, date = today, category = goal.category, goalId = goal.id,
                genKey = GenKeys.log(goal.id), done = true, doneAt = System.currentTimeMillis(),
            )
        )
        refreshWidget()
    }

    suspend fun setRecurring(goal: Goal, recurring: Boolean) = db.goals().update(goal.copy(recurring = recurring))

    suspend fun setSplit(goal: Goal, split: Boolean, today: LocalDate) {
        db.goals().update(goal.copy(split = split))
        if (split) ensureFor(today) else db.tasks().deleteOpenSplits(goal.id)
        refreshWidget()
    }

    suspend fun deleteGoal(goal: Goal) {
        db.tasks().deleteOpenSplits(goal.id)
        db.goals().delete(goal)
        refreshWidget()
    }

    // ---------- routines ----------

    suspend fun addRoutine(text: String, daysMask: Int, sync: Boolean, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Routines need a title (no @week/@month)"
        if (daysMask == 0) return "Pick at least one day"
        db.routines().insert(
            Routine(
                title = p.title, category = p.category, minutes = p.minutes, daysMask = daysMask,
                time = p.time, syncToCalendar = sync,
            )
        )
        ensureFor(today)
        val syncMsg = syncRoutines()
        refreshWidget()
        val base = "Routine added: ${p.title} · ${Days.describe(daysMask)}" + (p.time?.let { " ${hm.format(it)}" } ?: "")
        return when {
            !sync -> base
            p.time == null -> "$base. Add a time (e.g. 18:30) to put it in Google Calendar"
            syncMsg != null -> "$base. $syncMsg"
            else -> "$base · in Google Calendar"
        }
    }

    suspend fun setRoutineActive(r: Routine, active: Boolean) {
        db.routines().update(r.copy(active = active))
        if (active) ensureFor(LocalDate.now())
        syncRoutines()
        refreshWidget()
    }

    suspend fun setRoutineSync(r: Routine, sync: Boolean): String? {
        db.routines().update(r.copy(syncToCalendar = sync))
        return when {
            sync && r.time == null -> "Give “${r.title}” a time to sync it — delete and re-add with e.g. 18:30"
            else -> syncRoutines()
        }
    }

    suspend fun deleteRoutine(r: Routine) {
        r.calendarEventId?.let { calendar.deleteEvent(it) }
        db.routines().delete(r)
    }

    /**
     * Makes Google Calendar match the routines: creates a weekly recurring event for each active,
     * timed routine that wants syncing, and removes events for ones that don't. If you delete a
     * routine's event in Google Calendar, syncing is switched off rather than fighting you.
     * Returns a problem description, or null if all is well.
     */
    suspend fun syncRoutines(): String? = housekeeping.withLock {
        val routines = db.routines().all()
        val wantsAny = routines.any { it.active && it.syncToCalendar && it.time != null }
        if (!calendar.hasWritePermission()) {
            return@withLock if (wantsAny) "Allow calendar access to sync routines" else null
        }
        var calendarId: Long? = null
        var problem: String? = null

        for (r in routines) {
            val eventId = r.calendarEventId
            if (eventId != null && calendar.eventExists(eventId) == false) {
                db.routines().update(r.copy(calendarEventId = null, syncToCalendar = false))
                continue
            }
            val wanted = r.active && r.syncToCalendar && r.time != null && r.daysMask != 0
            when {
                wanted && eventId == null -> {
                    val calId = calendarId ?: calendar.writableCalendarId().also { calendarId = it }
                    if (calId == null) {
                        problem = "No writable Google calendar on this phone"
                        continue
                    }
                    val first = firstOccurrence(r.daysMask, LocalDate.now())
                    val id = calendar.createRecurring(calId, r.title, r.daysMask, r.time!!, r.minutes ?: 30, first)
                    if (id != null) db.routines().update(r.copy(calendarEventId = id))
                    else problem = "Couldn't create calendar event for “${r.title}”"
                }
                !wanted && eventId != null -> {
                    calendar.deleteEvent(eventId)
                    db.routines().update(r.copy(calendarEventId = null))
                }
            }
        }
        problem
    }

    private fun firstOccurrence(mask: Int, from: LocalDate): LocalDate =
        DayOfWeek.entries.filter { Days.has(mask, it) }
            .map { from.with(TemporalAdjusters.nextOrSame(it)) }
            .minOrNull() ?: from

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
