package dev.nathanxu.dayplanner.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dev.nathanxu.dayplanner.calendar.CalInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Set when several Google accounts exist and none has been chosen. */
    val needsAccountChoice: Boolean = false,
)

/** Result of the last routine → calendar sync, shown on the Routines and Settings screens. */
data class SyncStatus(val ok: Boolean, val message: String, val at: LocalDateTime = LocalDateTime.now())

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val calendar: CalendarSource,
    private val travel: TravelService,
    val settings: Settings,
) {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    /** ensureFor / syncRoutines can be triggered by the app and the widget at once. */
    private val housekeeping = Mutex()

    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    // ---------- observe ----------

    fun observeToday(today: LocalDate): Flow<List<Task>> = db.tasks().observeToday(today)
    fun observeUpcoming(today: LocalDate): Flow<List<Task>> = db.tasks().observeUpcoming(today)
    fun observeBetween(start: LocalDate, endExclusive: LocalDate): Flow<List<Task>> =
        db.tasks().observeBetween(start, endExclusive)
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

    // ---------- account / calendars ----------

    suspend fun calendars(): List<CalInfo> = calendar.calendars()

    fun googleAccounts(cals: List<CalInfo>): List<String> =
        cals.filter { it.isGoogle }.map { it.accountName }.distinct()

    /**
     * Calendars to read: every visible calendar of the chosen account. With no account chosen and
     * a single Google account, that account is chosen automatically; with several, everything is
     * shown until you pick (and routine sync waits for the pick).
     */
    private suspend fun readCalendarIds(): Set<Long>? {
        val cals = calendar.calendars()
        val account = settings.current.accountName ?: autoPickAccount(cals) ?: return null
        return cals.filter { it.accountName == account && it.visible }.map { it.id }.toSet()
    }

    private fun autoPickAccount(cals: List<CalInfo>): String? {
        val accounts = googleAccounts(cals)
        if (accounts.size != 1) return null
        settings.update { it.copy(accountName = accounts.single()) }
        return accounts.single()
    }

    fun chooseAccount(accountName: String) {
        settings.update { SettingsState(accountName = accountName, routineCalendarId = null) }
    }

    fun chooseRoutineCalendar(calendarId: Long) {
        settings.update { it.copy(routineCalendarId = calendarId) }
    }

    /** The calendar routines go into, or an explanation of why there isn't one. */
    private fun routineCalendar(cals: List<CalInfo>): Result<CalInfo> {
        val account = settings.current.accountName ?: autoPickAccount(cals)
        if (account == null) {
            return Result.failure(
                IllegalStateException(
                    if (googleAccounts(cals).isEmpty()) "No Google account calendars on this phone"
                    else "Choose which Google account to use in Settings ⚙"
                )
            )
        }
        val inAccount = cals.filter { it.accountName == account && it.writable }
        val pick = inAccount.firstOrNull { it.id == settings.current.routineCalendarId }
            ?: inAccount.firstOrNull { it.primary }
            ?: inAccount.firstOrNull()
        return pick?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("No writable calendar in $account"))
    }

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

    suspend fun updateTask(task: Task) {
        db.tasks().update(task)
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

    /** Saves an edited goal; today's split is recalculated if the target or split setting changed. */
    suspend fun updateGoal(goal: Goal, today: LocalDate) {
        val old = db.goals().get(goal.id)
        db.goals().update(goal.copy(target = goal.target.coerceAtLeast(1)))
        if (old == null || old.target != goal.target || old.split != goal.split || old.title != goal.title) {
            db.tasks().deleteOpenSplits(goal.id)
            ensureFor(today)
        }
        refreshWidget()
    }

    suspend fun setRecurring(goal: Goal, recurring: Boolean) = db.goals().update(goal.copy(recurring = recurring))

    suspend fun setSplit(goal: Goal, split: Boolean, today: LocalDate) = updateGoal(goal.copy(split = split), today)

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
        val status = syncRoutines()
        refreshWidget()
        val base = "Routine added: ${p.title} · ${Days.describe(daysMask)}" + (p.time?.let { " ${hm.format(it)}" } ?: "")
        return when {
            !sync -> base
            p.time == null -> "$base. Add a time (e.g. 18:30) to put it in Google Calendar"
            !status.ok -> "$base. ${status.message}"
            else -> "$base · added to Google Calendar"
        }
    }

    /**
     * Saves an edited routine. If anything that appears in the calendar changed, the old event is
     * dropped and recreated; today's unticked to-do is regenerated to match.
     */
    suspend fun updateRoutine(r: Routine, today: LocalDate): String {
        if (r.daysMask == 0) return "Pick at least one day"
        val old = db.routines().all().firstOrNull { it.id == r.id } ?: return "Routine not found"
        val calendarChanged = old.title != r.title || old.daysMask != r.daysMask ||
            old.time != r.time || old.minutes != r.minutes
        var next = r
        if (calendarChanged && old.calendarEventId != null) {
            calendar.deleteEvent(old.calendarEventId)
            next = next.copy(calendarEventId = null)
        }
        db.routines().update(next)
        db.tasks().deleteOpenByKey(GenKeys.routine(r.id, today))
        ensureFor(today)
        val status = syncRoutines()
        refreshWidget()
        return if (status.ok) "Saved “${r.title}”" else "Saved. ${status.message}"
    }

    suspend fun setRoutineActive(r: Routine, active: Boolean) {
        db.routines().update(r.copy(active = active))
        if (active) ensureFor(LocalDate.now())
        syncRoutines()
        refreshWidget()
    }

    suspend fun setRoutineSync(r: Routine, sync: Boolean): String? {
        db.routines().update(r.copy(syncToCalendar = sync))
        if (sync && r.time == null) return "Give “${r.title}” a time to sync it — tap it to edit"
        val status = syncRoutines()
        return if (status.ok) null else status.message
    }

    suspend fun deleteRoutine(r: Routine) {
        r.calendarEventId?.let { calendar.deleteEvent(it) }
        db.routines().delete(r)
        settings.current.accountName?.let { calendar.requestSync(it) }
    }

    /**
     * Makes Google Calendar match the routines: each active, timed routine that wants syncing gets
     * one weekly recurring event in the chosen calendar; events for routines that don't (or that
     * sit in a different calendar after you switch) are removed. If you delete a routine's event
     * in Google Calendar yourself, syncing is switched off for it rather than fighting you.
     */
    suspend fun syncRoutines(): SyncStatus = housekeeping.withLock {
        val routines = db.routines().all()
        val wanted = { r: Routine -> r.active && r.syncToCalendar && r.time != null && r.daysMask != 0 }

        if (routines.none { wanted(it) || it.calendarEventId != null }) {
            return@withLock SyncStatus(true, "No routines to sync").also { _syncStatus.value = it }
        }
        if (!calendar.hasWritePermission()) {
            return@withLock SyncStatus(false, "Allow calendar access to sync routines").also { _syncStatus.value = it }
        }
        val cals = calendar.calendars()
        val target = routineCalendar(cals).getOrElse { e ->
            return@withLock SyncStatus(false, e.message ?: "No calendar").also { _syncStatus.value = it }
        }

        val errors = mutableListOf<String>()
        var synced = 0
        var changed = false
        for (r in routines) {
            var eventId = r.calendarEventId
            if (eventId != null) {
                val inCalendar = calendar.eventCalendar(eventId)
                when {
                    inCalendar == null && wanted(r) -> {
                        // You deleted it in Google Calendar: respect that.
                        db.routines().update(r.copy(calendarEventId = null, syncToCalendar = false))
                        continue
                    }
                    inCalendar == null -> {
                        db.routines().update(r.copy(calendarEventId = null))
                        continue
                    }
                    !wanted(r) || inCalendar != target.id -> {
                        calendar.deleteEvent(eventId)
                        db.routines().update(r.copy(calendarEventId = null))
                        eventId = null
                        changed = true
                    }
                }
            }
            if (!wanted(r)) continue
            if (eventId != null) {
                synced++
                continue
            }
            runCatching {
                calendar.createRecurring(
                    target.id, r.title, r.daysMask, r.time!!, r.minutes ?: 30,
                    firstOccurrence(r.daysMask, LocalDate.now()),
                )
            }.onSuccess { id ->
                db.routines().update(r.copy(calendarEventId = id))
                synced++
                changed = true
            }.onFailure { e ->
                Log.w("Repository", "Routine sync failed for ${r.title}", e)
                errors += "“${r.title}”: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (changed) calendar.requestSync(target.accountName, target.accountType)

        val where = "“${target.name}” (${target.accountName})"
        val status = if (errors.isEmpty()) {
            SyncStatus(true, "$synced routine${if (synced == 1) "" else "s"} in $where")
        } else {
            SyncStatus(false, "Couldn't sync ${errors.joinToString("; ")}")
        }
        _syncStatus.value = status
        status
    }

    private fun firstOccurrence(mask: Int, from: LocalDate): LocalDate =
        DayOfWeek.entries.filter { Days.has(mask, it) }
            .map { from.with(TemporalAdjusters.nextOrSame(it)) }
            .minOrNull() ?: from

    // ---------- calendar + travel ----------

    suspend fun eventsBetween(start: LocalDate, endExclusive: LocalDate): List<CalEvent> =
        calendar.eventsBetween(start, endExclusive, readCalendarIds())

    suspend fun todayPlan(now: LocalDateTime = LocalDateTime.now()): TodayPlan {
        val today = now.toLocalDate()
        if (!calendar.hasPermission()) return TodayPlan(emptyList(), false, travel.enabled, null)

        val cals = calendar.calendars()
        val needsChoice = settings.current.accountName == null && googleAccounts(cals).size > 1
        val events = calendar.eventsOn(today, readCalendarIds())
        val minutes = DayPlan.travelLegs(events).associateWith { (a, b) -> travel.minutes(a, b) }

        val windowStart = later(now.withSecond(0).withNano(0), today.atTime(LocalTime.of(7, 0)))
        val windowEnd = later(windowStart, today.atTime(LocalTime.of(23, 0)))
        val items = DayPlan.buildTimeline(events, { a, b -> minutes[a to b] }, windowStart, windowEnd)
        val next = events.filter { !it.allDay && it.end.isAfter(now) }.minByOrNull { it.start }
        return TodayPlan(items, true, travel.enabled, next, needsChoice)
    }

    suspend fun refreshWidget() {
        runCatching { TodayWidget().updateAll(context) }
            .onFailure { Log.w("Repository", "Widget update failed", it) }
    }
}
