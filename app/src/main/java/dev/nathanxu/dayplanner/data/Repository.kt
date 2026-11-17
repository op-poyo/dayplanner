package dev.nathanxu.dayplanner.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dev.nathanxu.dayplanner.calendar.CalInfo
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.notify.AlertScheduler
import dev.nathanxu.dayplanner.domain.AlertPlanner
import dev.nathanxu.dayplanner.domain.Busy
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.domain.CalendarLayout
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Clashes
import dev.nathanxu.dayplanner.domain.DayPlan
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.GoalDraft
import dev.nathanxu.dayplanner.domain.GoalMath
import dev.nathanxu.dayplanner.domain.GoalKind
import dev.nathanxu.dayplanner.domain.PlanItem
import dev.nathanxu.dayplanner.domain.GoalState
import dev.nathanxu.dayplanner.domain.Linked
import dev.nathanxu.dayplanner.domain.Pacing
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.QuickAdd
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.TaskDraft
import dev.nathanxu.dayplanner.domain.TimelineItem
import dev.nathanxu.dayplanner.domain.later
import dev.nathanxu.dayplanner.travel.TravelMode
import dev.nathanxu.dayplanner.travel.TravelService
import dev.nathanxu.dayplanner.widget.TodayWidget
import dev.nathanxu.dayplanner.domain.ReminderInput
import dev.nathanxu.dayplanner.domain.Sitting
import dev.nathanxu.dayplanner.domain.Sittings
import dev.nathanxu.dayplanner.domain.Repeat
import dev.nathanxu.dayplanner.domain.Block
import dev.nathanxu.dayplanner.domain.Plan
import dev.nathanxu.dayplanner.domain.PlanCandidate
import dev.nathanxu.dayplanner.domain.PlanInputs
import dev.nathanxu.dayplanner.domain.Priority
import dev.nathanxu.dayplanner.domain.Scheduler
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import dev.nathanxu.dayplanner.domain.ReviewTask
import dev.nathanxu.dayplanner.domain.RoutineInput
import dev.nathanxu.dayplanner.domain.WeekSummary
import dev.nathanxu.dayplanner.domain.WeeklyReview
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A goal with its progress. [steps] are its linked one-off to-dos (open first): a checklist's
 * steps, or a count goal's planned sessions. [routines] are repeating to-dos counting towards it.
 */
data class GoalProgress(val goal: Goal, val status: GoalState, val steps: List<Task>, val routines: List<Routine> = emptyList())

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

/** Everything the "New plan" form collects. */
data class PlanDraft(
    val title: String,
    val tag: String,
    val start: LocalDate,
    /** Inclusive; [Pacing.ONGOING_END] = no end. */
    val end: LocalDate,
    /** A checklist goal (progress = its steps) rather than a count. */
    val checklist: Boolean,
    /** How many, per [per] (or in total when [per] is null). */
    val target: Int,
    val per: Cadence?,
    /** Stored priority (see [Priority.store]). */
    val priority: String? = null,
)

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val calendar: CalendarSource,
    private val travel: TravelService,
    val settings: Settings,
    val tags: TagStore,
    val alerts: AlertScheduler,
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

    /** Current and upcoming plans (not finished ones), with % / pace / today's share and steps. */
    fun observeGoals(today: LocalDate): Flow<List<GoalProgress>> = combine(
        db.goals().observeActive(),
        db.tasks().observeLinked(),
        db.routines().observeAll(),
    ) { goals, linked, routines -> goals.filter { !it.end.isBefore(today) }.map { progressOf(it, linked, routines, today) } }

    private val zone get() = java.time.ZoneId.systemDefault()

    private fun progressOf(g: Goal, linked: List<Task>, routines: List<Routine>, today: LocalDate): GoalProgress {
        val mine = linked.filter { it.goalId == g.id }
        val state = GoalMath.state(
            g.spec,
            mine.map { t ->
                Linked(
                    done = t.done,
                    on = if (t.done) t.doneAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: t.date else t.date,
                    units = t.units.coerceAtLeast(1),
                    repeating = t.routineId != null,
                )
            },
            today,
        )
        val steps = mine.filter { it.routineId == null }
            .sortedWith(compareBy({ it.done }, { it.date == null }, { it.date }, { -(it.doneAt ?: 0) }, { it.id }))
        return GoalProgress(g, state, steps, routines.filter { it.goalId == g.id })
    }

    suspend fun todayTasks(today: LocalDate): List<Task> = db.tasks().today(today)

    fun observeAllTodos(sinceMillis: Long): Flow<List<Task>> = db.tasks().observeAllTodos(sinceMillis)

    /** Finished goals with their final progress, newest first. */
    fun observeFinished(today: LocalDate): Flow<List<GoalProgress>> = combine(
        db.goals().observeFinished(),
        db.tasks().observeLinked(),
    ) { goals, linked -> goals.map { progressOf(it, linked, emptyList(), it.end) } }
    
    // ---------- tags ----------

    /** Tag key for "#raw" (created if new), or Other. Second value: whether it was created. */
    private fun tagFor(raw: String?): Pair<String, Tag?> {
        if (raw == null) return Tags.OTHER to null
        val (tag, created) = tags.resolveOrCreate(raw)
        return tag.key to if (created) tag else null
    }

    // ---------- accounts / calendars ----------
    suspend fun calendars(): List<CalInfo> = calendar.calendars()

    fun googleAccounts(cals: List<CalInfo>): List<String> =
        cals.filter { it.isGoogle }.map { it.accountName }.distinct()

    /** Linked accounts; with none ever chosen and exactly one Google account, that one is linked. */
    private fun linkedAccounts(cals: List<CalInfo>): Set<String>? {
        settings.current.accounts?.let { return it }
        val google = googleAccounts(cals)
        if (google.size != 1) return null
        settings.update { it.copy(accounts = google.toSet()) }
        return google.toSet()
    }

    /** Calendars to read, or null for "all" (no account chosen yet). */
    private suspend fun readCalendarIds(): Set<Long>? {
        val cals = calendar.calendars()
        val accounts = linkedAccounts(cals) ?: return null
        val hidden = settings.current.hiddenCalendars
        return cals.filter { it.accountName in accounts && it.id !in hidden }.map { it.id }.toSet()
    }

    /** Asks Android to sync the linked Google accounts now, so edits on either side show up sooner. */
    suspend fun requestCalendarSync() {
        val cals = calendar.calendars()
        val accounts = linkedAccounts(cals) ?: googleAccounts(cals).toSet()
        cals.filter { it.isGoogle && it.accountName in accounts }
            .map { it.accountName to it.accountType }.distinct()
            .forEach { (name, type) -> calendar.requestSync(name, type) }
    }

    fun setAccountLinked(account: String, linked: Boolean) {
        settings.update { s ->
            val base = s.accounts ?: emptySet()
            s.copy(accounts = if (linked) base + account else base - account)
        }
    }

    fun setCalendarShown(calendarId: Long, shown: Boolean) {
        settings.update { s ->
            s.copy(hiddenCalendars = if (shown) s.hiddenCalendars - calendarId else s.hiddenCalendars + calendarId)
        }
    }

    fun chooseRoutineCalendar(calendarId: Long) {
        settings.update { it.copy(routineCalendarId = calendarId) }
    }

    /** The calendar routines go into, or an explanation of why there isn't one. */
    private fun routineCalendar(cals: List<CalInfo>): Result<CalInfo> {
        val accounts = linkedAccounts(cals)
        if (accounts == null) {
            return Result.failure(
                IllegalStateException(
                    if (googleAccounts(cals).isEmpty()) "No Google account calendars on this phone"
                    else "Link a Google account in Settings ⚙"
                )
            )
        }
        if (accounts.isEmpty()) return Result.failure(IllegalStateException("No Google accounts linked — see Settings ⚙"))
        val writable = cals.filter { it.accountName in accounts && it.writable }
        val pick = writable.firstOrNull { it.id == settings.current.routineCalendarId }
            ?: writable.firstOrNull { it.primary && it.isGoogle }
            ?: writable.firstOrNull()
        return pick?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("No writable calendar in the linked accounts"))
    }

    suspend fun enableCalendarSync(cal: CalInfo): String =
        if (calendar.enableSync(cal)) {
            setCalendarShown(cal.id, true)
            "Sync turned on for “${cal.name}”. Events can take a few minutes to arrive."
        } else {
            "Android didn't allow it. In the Google Calendar app: Settings → ${cal.name} → turn on Sync."
        }

    // ---------- daily housekeeping ----------

    /**
     * Idempotent, run on app open, widget refresh and after edits:
     * clears yesterday's unticked repeating to-dos, moves goals past their end to Finished, and
     * makes today's to-do for each repeating to-do. Goals never make to-dos.
     */
    suspend fun ensureFor(date: LocalDate) = housekeeping.withLock {
        val tasks = db.tasks()
        val goals = db.goals()
        tasks.deleteStaleGenerated("r:", date)

        for (g in goals.active()) {
            if (g.end.isBefore(date)) goals.update(g.copy(archived = true))
        }

        for (r in db.routines().active()) {
            if (r.repeat.occursOn(date)) {
                tasks.insertIgnore(
                    Task(
                        title = r.title, date = date, category = r.category, minutes = r.minutes,
                        time = r.time, routineId = r.id, genKey = GenKeys.routine(r.id, date), goalId = r.goalId,
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
                // "every mon,wed", "daily"…: a repeating to-do.
                val goal = p.goalHint?.let { matchGoal(it, today) }
                p.repeat?.let { rep ->
                    val (tagKey, newTag) = tagFor(p.tag)
                    val msg = createRoutine(
                        Routine(
                            title = p.title, category = if (p.tag == null && goal != null) goal.category else tagKey,
                            minutes = p.minutes, time = p.time,
                            syncToCalendar = p.time != null, remind = p.time != null && settings.current.reminderLead != null,
                            daysMask = rep.daysMask, goalId = goal?.id,
                        ).withRepeat(rep),
                        today,
                    )
                    return msg + (goal?.let { " → ${it.title}" } ?: if (p.goalHint != null) " (no goal matched ^${p.goalHint})" else "") +
                        (newTag?.let { " · new tag ${it.name}" } ?: "")
                }
                val newTag = insertDraft(p, goal)
                buildString {
                    append("Added “${p.title}”")
                    append(" · ").append(p.date?.let { if (it == today) "today" else dayFmt.format(it) } ?: "someday")
                    p.time?.let { append(" ").append(hm.format(it)) }
                    p.deadline?.let { append(" · due ").append(if (it == today) "today" else dayFmt.format(it)) }
                    if (p.priority != Priority.NORMAL) append(" · ").append(p.priority.label.lowercase()).append(" priority")
                    if (goal != null) append(" → ${goal.title}")
                    else if (p.goalHint != null) append(" (no goal matched ^${p.goalHint})")
                    newTag?.let { append(" · new tag ${it.name}") }
                }
            }
            is GoalDraft -> {
                val (tagKey, newTag) = tagFor(p.tag)
                // "x3 @week" = 3 every week from now on; "x15 by 30/11" = 15 in total by then.
                val per = when (p.period) {
                    Period.WEEK -> Cadence.WEEKLY
                    Period.MONTH -> Cadence.MONTHLY
                    Period.CUSTOM -> null
                }
                val g = Goal(
                    title = p.title, period = Period.CUSTOM, periodStart = today,
                    endDate = p.end ?: Pacing.ONGOING_END, target = p.target.coerceAtLeast(1),
                    category = tagKey, cadence = per, split = false,
                )
                db.goals().insert(g)
                changed()
                "Goal added: ${p.title} · ${GoalMath.describe(g.spec)}" + (newTag?.let { " · new tag ${it.name}" } ?: "")
            }
        }
    }

    /**
     * A live goal for "^hint": title containing it, or its tag. Nearest-ending first, so a
     * this-month goal beats an ongoing one with a similar name.
     */
    private suspend fun matchGoal(hint: String, today: LocalDate): Goal? {
        val h = hint.lowercase()
        val tagKeys = tags.current.filter { it.name.equals(h, ignoreCase = true) || it.key.equals(h, ignoreCase = true) }.map { it.key }.toSet()
        return db.goals().active()
            .filter { !it.end.isBefore(today) && (it.title.lowercase().contains(h) || it.category in tagKeys) }
            .minWithOrNull(compareBy({ !it.title.lowercase().contains(h) }, { it.end }))
    }

    /** From the goal form. */
    suspend fun createPlan(d: PlanDraft, today: LocalDate): String {
        if (d.title.isBlank()) return "Give it a title"
        if (d.end.isBefore(d.start)) return "End date is before the start"
        val g = Goal(
            title = d.title.trim(), period = Period.CUSTOM, periodStart = d.start, endDate = d.end,
            target = if (d.checklist) 1 else d.target.coerceAtLeast(1), category = d.tag, priority = d.priority,
            cadence = if (d.checklist) null else d.per, split = false,
        )
        db.goals().insert(g)
        changed()
        return "Goal added: ${g.title} · ${GoalMath.describe(g.spec)}"
    }

    /** Adds a step (a linked to-do, no date unless typed) from a goal's card. */
    suspend fun addStep(goal: Goal, text: String, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Steps need a title (no @week/@month)"
        // A step has no day until you give it one (typing "fri" still sets it).
        val typedDate = Regex("\\s").split(text.trim()).drop(1).any { w -> w.lowercase() in setOf("today", "tod", "tmr", "tmrw", "tomorrow") || QuickAdd.parseDate(w, today) != null }
        insertDraft(if (typedDate) p else p.copy(date = null), goal)
        return "Step added to ${goal.title}"
    }

    /** Returns the tag it created, if any. */
    private suspend fun insertDraft(p: TaskDraft, goal: Goal?): Tag? {
        val (tagKey, newTag) = tagFor(p.tag)
        db.tasks().insert(
            Task(
                title = p.title, date = p.date, time = p.time, minutes = p.minutes, goalId = goal?.id,
                category = if (p.tag == null && goal != null) goal.category else tagKey,
                priority = Priority.store(p.priority), deadline = p.deadline,
                // Quick-add has no reminder picker: timed to-dos get the Settings default (off unless set).
                remindBefore = if (p.time != null) settings.current.reminderLead else null,
            )
        )
        changed()
        return newTag
    }

    /** From the full to-do form (also used for steps, with goalId set). */
    suspend fun createTask(task: Task): String {
        if (task.title.isBlank()) return "Give it a title"
        db.tasks().insert(task.copy(id = 0, title = task.title.trim(), createdAt = System.currentTimeMillis()))
        changed()
        return "Added “${task.title.trim()}”"
    }

    /** Puts back something you just deleted (snackbar Undo). */
    suspend fun restoreTask(task: Task) {
        // A skipped routine/split to-do is still there (hidden): put the old row back over it.
        if (db.tasks().get(task.id) != null) db.tasks().update(task) else db.tasks().insertIgnore(task)
        changed()
    }

    suspend fun updateTask(task: Task) {
        val old = db.tasks().get(task.id)
        var next = task
        // Moved by hand: the planner's calendar blocks no longer match, so remove them.
        if (old?.plannedEvents != null && (old.date != task.date || old.time != task.time)) {
            deletePlannedEvents(old.plannedEvents, futureOnly = false)
            next = task.copy(plannedEvents = null, sittings = null)
        }
        db.tasks().update(next)
        changed()
    }

    suspend fun setDone(id: Long, done: Boolean) {
        db.tasks().setDone(id, done, if (done) System.currentTimeMillis() else null)
        changed()
    }

    /** Checkbox on a to-do. */
    suspend fun toggle(id: Long) {
        val t = db.tasks().get(id) ?: return
        setDone(id, !t.done)
    }

    /**
     * Deletes a to-do. One made by a repeating to-do is skipped instead
     * (kept hidden for that day), so it isn't made again on the next refresh. Returns true if skipped.
     */
    suspend fun deleteTask(id: Long): Boolean {
        val t = db.tasks().get(id) ?: return false
        t.plannedEvents?.let { deletePlannedEvents(it, futureOnly = true) }
        val generated = t.genKey?.startsWith("r:") == true
        if (generated) db.tasks().update(t.copy(skipped = true, done = false, doneAt = null))
        else db.tasks().delete(id)
        changed()
        return generated
    }

    // ---------- search ----------

    data class SearchResults(val tasks: List<Task>, val goals: List<Goal>, val routines: List<Routine>) {
        val isEmpty get() = tasks.isEmpty() && goals.isEmpty() && routines.isEmpty()
    }

    /** To-dos (title, place or tag name), long-term tasks and routines containing [query]. */
    suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val tagKeys = tags.current.filter { it.name.contains(q, ignoreCase = true) }.map { it.key }
        val has = { s: String -> s.contains(q, ignoreCase = true) }
        return SearchResults(
            tasks = db.tasks().search(q, tagKeys),
            goals = db.goals().all().filter { has(it.title) || it.category in tagKeys }
                .sortedWith(compareBy({ it.archived }, { -it.periodStart.toEpochDay() })),
            routines = db.routines().all().filter { has(it.title) || it.category in tagKeys },
        )
    }

    // ---------- goals / plans ----------

    /** "Log one": a done to-do for today, counting towards the goal (and in its history). Returns it, for Undo. */
    suspend fun logGoal(goal: Goal, today: LocalDate): Task {
        val t = Task(
            title = goal.title, date = today, category = goal.category, goalId = goal.id,
            minutes = typicalMinutes(goal), done = true, doneAt = System.currentTimeMillis(),
        )
        val id = db.tasks().insert(t)
        changed()
        return t.copy(id = id)
    }

    /** "+ To-do": an open to-do for today, linked to the goal. */
    suspend fun addTodoForGoal(goal: Goal, today: LocalDate): String {
        db.tasks().insert(
            Task(title = goal.title, date = today, category = goal.category, goalId = goal.id, minutes = typicalMinutes(goal), priority = goal.priority)
        )
        changed()
        return "Added “${goal.title}” for today"
    }

    /** Length of the goal's most recent session, so new ones match. */
    private suspend fun typicalMinutes(goal: Goal): Int? =
        db.tasks().latestLinked(goal.id)?.minutes

    /** Gives a step (or any to-do) a day, keeping everything else. */
    suspend fun scheduleTask(id: Long, date: LocalDate?) {
        val t = db.tasks().get(id) ?: return
        updateTask(t.copy(date = date, time = if (date == null) null else t.time))
    }

    suspend fun updateGoal(goal: Goal, today: LocalDate) {
        db.goals().update(goal.copy(target = goal.target.coerceAtLeast(1)))
        changed()
    }

    /** Moves a goal's end date (and brings a finished one back). */
    suspend fun extendGoal(goal: Goal, newEnd: LocalDate, today: LocalDate): String {
        if (newEnd.isBefore(goal.start)) return "End can't be before the start"
        db.goals().update(goal.copy(endDate = newEnd, period = Period.CUSTOM, archived = newEnd.isBefore(today)))
        changed()
        return "${goal.title} now runs to ${dayFmt.format(newEnd)}"
    }

    /** Puts back a deleted goal, relinking the repeating to-dos that counted towards it. */
    suspend fun restoreGoal(goal: Goal, routineIds: List<Long> = emptyList()) {
        db.goals().insert(goal)
        db.routines().all().filter { it.id in routineIds }.forEach { db.routines().update(it.copy(goalId = goal.id)) }
        changed()
    }

    /** Deletes a goal. Its to-dos stay (unlinked from nothing: they just stop counting). Returns the repeating ones it unlinked. */
    suspend fun deleteGoal(goal: Goal): List<Long> {
        val linked = db.routines().all().filter { it.goalId == goal.id }
        linked.forEach { db.routines().update(it.copy(goalId = null)) }
        db.goals().delete(goal)
        changed()
        return linked.map { it.id }
    }

    /** Ends a goal today: it moves to Finished with its progress. */
    suspend fun finishGoal(goal: Goal, today: LocalDate) {
        db.goals().update(goal.copy(archived = true, recurring = false, endDate = maxOf(today, goal.periodStart), period = Period.CUSTOM))
        changed()
    }

    /** Deletes every finished goal; returns them for Undo. */
    suspend fun clearFinished(): List<Goal> {
        val gone = db.goals().all().filter { it.archived && !it.recurring }
        gone.forEach { db.goals().delete(it) }
        changed()
        return gone
    }

    suspend fun restoreGoals(goals: List<Goal>) {
        goals.forEach { db.goals().insert(it) }
        changed()
    }

    // ---------- routines ----------

    suspend fun addRoutine(text: String, daysMask: Int, sync: Boolean, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Repeating to-dos need a title (no @week/@month)"
        if (daysMask == 0) return "Pick at least one day"
        val (tagKey, newTag) = tagFor(p.tag)
        val id = db.routines().insert(
            Routine(
                title = p.title, category = tagKey, minutes = p.minutes, daysMask = daysMask,
                time = p.time, syncToCalendar = sync,
            )
        )
        ensureFor(today)
        val status = syncRoutines()
        changed()
        val clashes = p.time?.let { routineClashes(Repeat.weekly(daysMask), it, p.minutes ?: 30, id, today) }.orEmpty()
        val base = "Repeating to-do added: ${p.title} · ${Days.describe(daysMask)}" + (p.time?.let { " ${hm.format(it)}" } ?: "")
        return buildString {
            append(
                when {
                    !sync -> base
                    p.time == null -> "$base. Add a time (e.g. 18:30) to put it in Google Calendar"
                    !status.ok -> "$base. ${status.message}"
                    else -> "$base · added to Google Calendar"
                }
            )
            newTag?.let { append(" · new tag ${it.name}") }
            if (clashes.isNotEmpty()) append("\n⚠ Clashes: ").append(clashes.joinToString("; "))
        }
    }

    /** From the "New routine" form. */
    suspend fun createRoutine(r: Routine, today: LocalDate): String {
        if (r.title.isBlank()) return "Give it a name"
        if (!r.repeat.isValid) return "Pick at least one day"
        val id = db.routines().insert(r.copy(id = 0, title = r.title.trim(), calendarEventId = null))
        ensureFor(today)
        val status = syncRoutines()
        changed()
        val clashes = r.time?.let { routineClashes(r.repeat, it, r.minutes ?: 30, id, today) }.orEmpty()
        return buildString {
            append("Repeating to-do added: ${r.title.trim()} · ${r.repeat.describe()}")
            r.time?.let { append(" ${hm.format(it)}") }
            when {
                !r.syncToCalendar -> Unit
                r.time == null -> append(". Add a time to put it in Google Calendar")
                !status.ok -> append(". ${status.message}")
                else -> append(" · added to Google Calendar")
            }
            if (clashes.isNotEmpty()) append("\n⚠ Clashes: ").append(clashes.joinToString("; "))
        }
    }

    /**
     * Saves an edited routine. If anything that appears in the calendar changed, the old event is
     * dropped and recreated; today's unticked to-do is regenerated to match.
     */
    suspend fun updateRoutine(r: Routine, today: LocalDate): String {
        if (!r.repeat.isValid) return "Pick at least one day"
        val old = db.routines().all().firstOrNull { it.id == r.id } ?: return "Repeating to-do not found"
        val calendarChanged = old.title != r.title || old.repeat != r.repeat ||
            old.time != r.time || old.minutes != r.minutes
        var next = r
        if (calendarChanged && old.calendarEventId != null) {
            calendar.deleteEvent(old.calendarEventId)
            next = next.copy(calendarEventId = null)
        }
        db.routines().update(next)
        if (old.goalId != r.goalId) db.tasks().relinkRoutine(r.id, r.goalId)
        db.tasks().deleteOpenByKey(GenKeys.routine(r.id, today))
        ensureFor(today)
        val status = syncRoutines()
        changed()
        val clashes = r.time?.let { routineClashes(r.repeat, it, r.minutes ?: 30, r.id, today) }.orEmpty()
        return buildString {
            append(if (status.ok) "Saved “${r.title}”" else "Saved. ${status.message}")
            if (clashes.isNotEmpty()) append("\n⚠ Clashes: ").append(clashes.joinToString("; "))
        }
    }

    /**
     * What a routine repeating per [repeat] at [time] would overlap in the next 4 weeks: events in your
     * linked calendars and your other routines. [excludeRoutineId] is the routine itself (its own
     * calendar event doesn't count).
     */
    suspend fun routineClashes(
        repeat: Repeat, time: LocalTime, minutes: Int, excludeRoutineId: Long?, from: LocalDate = LocalDate.now(),
    ): List<String> {
        // Long enough to catch monthly routines once or twice.
        val days = if (repeat.unit == Repeat.Unit.MONTH) 62 else 28
        val routines = db.routines().all()
        val ownEvent = routines.firstOrNull { it.id == excludeRoutineId }?.calendarEventId
        val events = runCatching { eventsBetween(from, from.plusDays(days.toLong())) }.getOrDefault(emptyList())
        val eventIds = events.map { it.id }.toSet()

        val busy = mutableListOf<Busy>()
        CalendarLayout.byDay(events.filter { !it.allDay && it.id != ownEvent }, from, from.plusDays(days.toLong()))
            .forEach { (day, segs) -> segs.forEach { busy += Busy(day, it.startMin, it.endMin, it.event.title) } }
        for (o in routines) {
            if (o.id == excludeRoutineId || !o.active || o.time == null) continue
            // A synced routine already shows up as a calendar event; don't count it twice.
            if (o.calendarEventId != null && o.calendarEventId in eventIds) continue
            busy += Clashes.routineBusy(o.repeat, o.time, o.minutes ?: 30, "${o.title} (repeating)", from, days)
        }
        return Clashes.summarise(Clashes.find(repeat, time, minutes, from, days, busy))
    }

    suspend fun setRoutineActive(r: Routine, active: Boolean) {
        db.routines().update(r.copy(active = active))
        if (active) ensureFor(LocalDate.now())
        syncRoutines()
        changed()
    }

    suspend fun setRoutineSync(r: Routine, sync: Boolean): String? {
        db.routines().update(r.copy(syncToCalendar = sync))
        if (sync && r.time == null) return "Give “${r.title}” a time to sync it — tap it to edit"
        val status = syncRoutines()
        return if (status.ok) null else status.message
    }

    suspend fun restoreRoutine(r: Routine, today: LocalDate) {
        db.routines().insert(r.copy(calendarEventId = null))
        ensureFor(today)
        syncRoutines()
        changed()
    }

    suspend fun deleteRoutine(r: Routine) {
        r.calendarEventId?.let { calendar.deleteEvent(it) }
        db.routines().delete(r)
        routineCalendar(calendar.calendars()).getOrNull()?.let { calendar.requestSync(it.accountName, it.accountType) }
    }

    /**
     * Makes Google Calendar match the routines: each active, timed routine that wants syncing gets
     * one recurring event (following its repeat rule) in the routine calendar; events for routines that don't (or that
     * sit in a different calendar after you switch) are removed. If you delete a routine's event
     * in Google Calendar yourself, syncing is switched off for it rather than fighting you.
     */
    suspend fun syncRoutines(): SyncStatus = housekeeping.withLock {
        val routines = db.routines().all()
        val wanted = { r: Routine -> r.active && r.syncToCalendar && r.time != null && r.repeat.isValid }

        if (routines.none { wanted(it) || it.calendarEventId != null }) {
            return@withLock SyncStatus(true, "Nothing repeating to sync").also { _syncStatus.value = it }
        }
        if (!calendar.hasWritePermission()) {
            return@withLock SyncStatus(false, "Allow calendar access to sync repeating to-dos").also { _syncStatus.value = it }
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
                    target.id, r.title, r.repeat.rrule(), r.time!!, r.minutes ?: 30,
                    r.repeat.first(LocalDate.now()) ?: error("No upcoming day for this repeating to-do"),
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
            SyncStatus(true, "$synced repeating to-do${if (synced == 1) "" else "s"} in $where")
        } else {
            SyncStatus(false, "Couldn't sync ${errors.joinToString("; ")}")
        }
        _syncStatus.value = status
        status
    }

    // ---------- calendar + travel ----------

    suspend fun eventsBetween(start: LocalDate, endExclusive: LocalDate): List<CalEvent> =
        calendar.eventsBetween(start, endExclusive, readCalendarIds())

    suspend fun todayPlan(now: LocalDateTime = LocalDateTime.now()): TodayPlan {
        val today = now.toLocalDate()
        if (!calendar.hasPermission()) return TodayPlan(emptyList(), false, travel.enabled, null)

        val cals = calendar.calendars()
        val needsChoice = settings.current.accounts == null && googleAccounts(cals).size > 1
        val events = calendar.eventsOn(today, readCalendarIds())
        val home = settings.current.home
        val minutes = travelMinutes(events, home)

        val windowStart = later(now.withSecond(0).withNano(0), today.atTime(LocalTime.of(7, 0)))
        val windowEnd = later(windowStart, today.atTime(LocalTime.of(23, 0)))
        val items = DayPlan.buildTimeline(events, { a, b -> minutes[a to b] }, windowStart, windowEnd, home = home)
        val next = events.filter { !it.allDay && it.end.isAfter(now) }.minByOrNull { it.start }
        return TodayPlan(items, true, travel.enabled, next, needsChoice)
    }

    val travelEnabled: Boolean get() = travel.enabled

    /** Travel time for every journey the day needs, in your chosen mode (cached for 7 days). */
    private suspend fun travelMinutes(events: List<CalEvent>, home: String?): Map<Pair<String, String>, Int?> {
        val mode = runCatching { TravelMode.valueOf(settings.current.travelMode) }.getOrDefault(TravelMode.TRANSIT)
        return DayPlan.travelLegs(events, home).associateWith { (a, b) -> travel.minutes(a, b, mode) }
    }

    // ---------- planner ----------

    /** A plan to review before accepting. */
    data class PlanPreview(
        val plan: Plan,
        val days: List<LocalDate>,
        /** Things worth knowing about how it was made (e.g. no calendar access). */
        val notes: List<String>,
        /** Where blocks would go, or null if they can't be written (with why in [notes]). */
        val calendarName: String?,
        /** Suggested sessions for goals that are behind: plan item id → its goal. Start unticked. */
        val suggested: Map<Long, Goal> = emptyMap(),
    )

    /** What accepting changed, so Undo can put it back. */
    data class PlanUndo(val before: List<Task>, val createdEvents: List<Long>, val createdTasks: List<Long> = emptyList())

    private fun candidateOf(t: Task, goalPriority: Map<Long, String?>, travelBefore: Int) = PlanCandidate(
        id = t.id, title = t.title, date = t.date, time = t.time, minutes = t.minutes,
        priority = Priority.of(t.priority ?: t.goalId?.let { goalPriority[it] }),
        deadline = t.deadline, tag = t.category.takeIf { it != Tags.OTHER },
        planned = t.plannedEvents != null, done = t.done, travelBefore = travelBefore,
        generated = t.genKey != null,
    )

    /** Open planned to-dos whose block is over (for the "slipped" banner). */
    fun observeSlipped(): Flow<List<Task>> = db.tasks().observePlannedOpen().map { list ->
        val now = LocalDateTime.now()
        val rules = settings.current.planRules
        list.filter { PlanInputs.slipped(candidateOf(it, emptyMap(), 0), now, rules) }
    }

    /** Plans [dayCount] days from today (today = only what's left of it). Doesn't change anything. */
    suspend fun previewPlan(dayCount: Int, includeSomeday: Boolean, now: LocalDateTime = LocalDateTime.now()): PlanPreview {
        val s = settings.current
        val today = now.toLocalDate()
        ensureFor(today)
        val days = (0 until dayCount.coerceIn(1, 14)).map { today.plusDays(it.toLong()) }
        val rules = s.planRules
        val notes = mutableListOf<String>()

        // Calendar events (with travel before located ones, when travel times are on).
        val events = if (calendar.hasPermission()) {
            runCatching { eventsBetween(today, days.last().plusDays(1)) }.getOrDefault(emptyList())
        } else {
            notes += "No calendar access, so lectures and events aren't avoided."
            emptyList()
        }
        val pad = mutableMapOf<Long, Int>()
        if (travel.enabled) {
            days.forEach { d ->
                val dayEvents = events.filter { !it.allDay && it.start.toLocalDate() == d }
                val minutes = travelMinutes(dayEvents, s.home)
                DayPlan.legs(dayEvents, s.home).forEach { leg ->
                    minutes[leg.from to leg.destination]?.let { pad[leg.to.id] = it }
                }
            }
        }
        val eventBusy = PlanInputs.busyFromEvents(events, days, pad)

        // Timed routines, even on days whose routine to-dos don't exist yet.
        val routineBusy = db.routines().all().filter { it.active && it.time != null }.flatMap { r ->
            Clashes.routineBusy(r.repeat, r.time!!, r.minutes ?: rules.defaultMinutes, r.title, today, days.size)
        }

        // To-dos: what to place, and timed ones that block time.
        val goalPriority = db.goals().active().associate { it.id to it.priority }
        var placesWithoutTravel = 0
        val candidates = db.tasks().open().map { t ->
            val travelBefore = if (t.place != null && t.time == null) {
                val address = s.addressOf(t.place)
                val home = s.home
                if (travel.enabled && address != null && home != null && !DayPlan.sameLocation(home, address)) {
                    val mode = runCatching { TravelMode.valueOf(s.travelMode) }.getOrDefault(TravelMode.TRANSIT)
                    travel.minutes(home, address, mode) ?: 0
                } else {
                    placesWithoutTravel++
                    0
                }
            } else 0
            candidateOf(t, goalPriority, travelBefore)
        }
        // (Travel is out of stage 1: no note about it when it's off.)
        if (travel.enabled && placesWithoutTravel > 0 && s.home == null) notes += "Set Home in Settings to keep travel time before to-dos with a place."

        val (items, taskBusy) = PlanInputs.select(candidates, days, now, includeSomeday, rules)
        val (suggestItems, suggested) = goalSuggestions(days, today, rules.defaultMinutes)
        val raw = Scheduler.plan(items + suggestItems, days, eventBusy + routineBusy + taskBusy, s.protectedBlocks, rules, now)
        // A suggestion that doesn't fit isn't worth a line under "Didn't fit".
        val plan = raw.copy(unplaced = raw.unplaced.filter { it.itemId !in suggested })

        val cal = if (calendar.hasWritePermission()) routineCalendar(calendar.calendars()) else Result.failure(IllegalStateException("No calendar write access"))
        cal.exceptionOrNull()?.let { notes += "Blocks can't be added to Google Calendar: ${it.message}." }
        return PlanPreview(plan, days, notes, cal.getOrNull()?.name, suggested)
    }

    /**
     * Sessions for count goals that won't make their number this period otherwise: what's left,
     * minus open linked to-dos already in the period and the linked repeating to-dos still to
     * come, spread one a day over the plan's days in the period.
     */
    private suspend fun goalSuggestions(days: List<LocalDate>, today: LocalDate, defaultMinutes: Int): Pair<List<PlanItem>, Map<Long, Goal>> {
        val linked = db.tasks().linked()
        val routines = db.routines().all()
        val items = mutableListOf<PlanItem>()
        val byId = mutableMapOf<Long, Goal>()
        for (g in db.goals().active().filter { it.kind == GoalKind.COUNT && !it.end.isBefore(today) && !it.start.isAfter(today) }) {
            val p = progressOf(g, linked, routines, today)
            val w = p.status.window ?: continue
            val open = linked.count { it.goalId == g.id && !it.done && it.date != null && it.date in w.start..w.end && !it.date.isBefore(today) }
            val coming = p.routines.filter { it.active }.sumOf { r ->
                r.repeat.occurrences(today.plusDays(1), ChronoUnit.DAYS.between(today, w.end).toInt().coerceAtLeast(0)).size
            }
            val need = p.status.remaining - open - coming
            if (need <= 0) continue
            val minutes = typicalMinutes(g) ?: defaultMinutes
            GoalMath.suggestionDays(need, days.filter { !it.isAfter(w.end) }).forEachIndexed { k, d ->
                val id = -(g.id * 64 + k + 1)
                byId[id] = g
                items += PlanItem(id, g.title, minutes, Priority.of(g.priority), day = d, tag = g.category.takeIf { it != Tags.OTHER }, splittable = false)
            }
        }
        return items to byId
    }

    /**
     * Gives each placed to-do its first block's date and time; with [addToCalendar], writes every
     * block to the routines calendar. A slipped to-do's future blocks are replaced.
     */
    suspend fun acceptPlan(plan: Plan, addToCalendar: Boolean, suggested: Map<Long, Goal> = emptyMap()): Pair<String, PlanUndo> {
        val s = settings.current
        val cal = if (addToCalendar && calendar.hasWritePermission()) routineCalendar(calendar.calendars()).getOrNull() else null
        val before = mutableListOf<Task>()
        val created = mutableListOf<Long>()
        val newTasks = mutableListOf<Long>()
        var failed = 0
        for ((id, blocks) in plan.blocks.groupBy { it.itemId }) {
            // A suggested session becomes a new to-do linked to its goal.
            val goal = suggested[id]
            val t = if (goal != null) {
                val newId = db.tasks().insert(
                    Task(title = goal.title, date = blocks.first().date, category = goal.category, goalId = goal.id,
                        minutes = blocks.sumOf { it.minutes }, priority = goal.priority)
                )
                newTasks += newId
                db.tasks().get(newId) ?: continue
            } else db.tasks().get(id) ?: continue
            if (t.done) continue
            if (goal == null) before += t
            t.plannedEvents?.let { deletePlannedEvents(it, futureOnly = true) }
            val ids = if (cal != null) blocks.mapNotNull { b ->
                runCatching {
                    calendar.createEvent(
                        cal.id, eventTitle(t, b), b.date.atTime(b.start), b.date.atTime(b.end),
                        s.addressOf(t.place), "Planned by DayPlanner. Move it in the app (Plan my day) — edits here aren't read back.",
                    )
                }.onFailure { failed++; Log.w("Repository", "Block not added", it) }.getOrNull()
            } else emptyList()
            created += ids
            val first = blocks.minWith(compareBy<Block>({ it.date }, { it.startMin }))
            db.tasks().update(
                t.copy(
                    date = first.date, time = first.start, plannedEvents = ids.joinToString(","),
                    sittings = Sittings.encode(blocks.sortedWith(compareBy({ it.date }, { it.startMin })).map { Sitting(it.date.atTime(it.start), it.minutes) }),
                    // Newly timed by the planner: the Settings default reminder, if any.
                    remindBefore = t.remindBefore ?: s.reminderLead,
                )
            )
        }
        if (cal != null) calendar.requestSync(cal.accountName)
        changed()
        val n = before.size + newTasks.size
        val msg = buildString {
            append(if (n == 1) "Planned 1 to-do" else "Planned $n to-dos")
            if (newTasks.isNotEmpty()) append(" (${newTasks.size} for goals)")
            if (cal != null && created.isNotEmpty()) append(" · added to ${cal.name}")
            if (failed > 0) append(" · $failed block(s) couldn't be added")
        }
        return msg to PlanUndo(before, created, newTasks)
    }

    suspend fun undoPlan(undo: PlanUndo) {
        undo.createdEvents.forEach { calendar.deleteEvent(it) }
        undo.before.forEach { db.tasks().update(it) }
        undo.createdTasks.forEach { db.tasks().delete(it) }
        changed()
    }

    private fun eventTitle(t: Task, b: Block) = if (b.parts > 1) "${t.title} (${b.part}/${b.parts})" else t.title

    /** Removes a to-do's planned blocks (only those still ahead, if [futureOnly]: past ones are history). */
    private suspend fun deletePlannedEvents(ids: String, futureOnly: Boolean) {
        val now = LocalDateTime.now()
        ids.split(',').mapNotNull { it.trim().toLongOrNull() }.forEach { id ->
            if (!futureOnly || calendar.eventStart(id)?.isAfter(now) == true) calendar.deleteEvent(id)
        }
    }

    // ---------- weekly review ----------

    /** Summary of the week starting [weekStart] (Monday), as of [today]. */
    suspend fun weekSummary(weekStart: LocalDate, today: LocalDate = LocalDate.now()): WeekSummary {
        val zone = ZoneId.systemDefault()
        val to = weekStart.plusDays(14) // this week and next
        val ms = { d: LocalDate -> d.atStartOfDay(zone).toInstant().toEpochMilli() }
        val tasks = db.tasks().forReview(weekStart, to, ms(weekStart), ms(weekStart.plusDays(7))).map { t ->
            ReviewTask(
                id = t.id, title = t.title, date = t.date, done = t.done,
                doneOn = t.doneAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                tag = t.category, minutes = t.minutes, routineId = t.routineId, deadline = t.deadline,
                isLog = t.genKey?.startsWith("l:") == true,
            )
        }
        val routines = db.routines().all().map { RoutineInput(it.id, it.title, it.daysMask, it.active, it.repeat) }
        return WeeklyReview.summarise(tasks, routines, today, weekStart)
    }

    /** Review action on a slipped to-do: move it to [date] (null = someday), clearing its time. */
    suspend fun moveTask(id: Long, date: LocalDate?) {
        val t = db.tasks().get(id) ?: return
        updateTask(t.copy(date = date, time = null))
    }

    // ---------- onboarding ----------

    /** Anything made yet? An existing install upgrading to v1.8 skips the first-launch setup. */
    suspend fun hasAnyData(): Boolean =
        db.tasks().count() > 0 || db.routines().all().isNotEmpty() || db.goals().all().isNotEmpty() || settings.current.accounts != null

    // ---------- backup ----------

    /** The whole app as a backup file's text. */
    suspend fun exportBackup(now: LocalDateTime = LocalDateTime.now()): String = BackupCodec.encode(
        BackupContents(db.tasks().all(), db.goals().all(), db.routines().all(), tags.current, settings.current, now),
        now,
    )

    /**
     * Replaces everything with [text]'s contents. Nothing changes if the file is bad (throws
     * [BackupCodec.BadBackup]). Returns a summary and the previous state (for Undo).
     */
    suspend fun importBackup(text: String): Pair<String, String> {
        val incoming = BackupCodec.decode(text) // validate before touching anything
        val previous = exportBackup()
        val oldRoutines = db.routines().all()

        // Routine events: keep ones the backup still points at (same phone), remove the rest;
        // routines whose event is gone get a fresh one from syncRoutines().
        val keepIds = incoming.routines.mapNotNull { it.calendarEventId }.toSet()
        oldRoutines.mapNotNull { it.calendarEventId }.filter { it !in keepIds }.forEach { calendar.deleteEvent(it) }
        val routines = incoming.routines.map { r ->
            val alive = r.calendarEventId?.let { calendar.eventCalendar(it) != null } ?: false
            if (alive) r else r.copy(calendarEventId = null)
        }

        db.withTransaction {
            db.tasks().deleteAll()
            db.goals().deleteAll()
            db.routines().deleteAll()
            db.goals().insertAll(incoming.goals)
            db.routines().insertAll(routines)
            db.tasks().insertAll(incoming.tasks)
        }
        if (incoming.tags.isNotEmpty()) tags.replaceAll(incoming.tags)
        val here = settings.current
        settings.update {
            incoming.settings.copy(
                hiddenCalendars = here.hiddenCalendars, routineCalendarId = here.routineCalendarId, onboarded = true,
            )
        }
        val today = LocalDate.now()
        ensureFor(today)
        runCatching { syncRoutines() }
        changed()
        val from = incoming.exportedAt?.let { " from " + dayFmt.format(it) } ?: ""
        return "Restored ${incoming.summary()}$from" to previous
    }

    // ---------- notifications ----------

    /**
     * Re-plans today's alarms: a "leave now" for every journey with a known travel time, and the
     * next morning summary. Called on app open, after settings changes, daily at 04:30, and after
     * a reboot or update.
     */
    suspend fun scheduleAlerts(now: LocalDateTime = LocalDateTime.now()) {
        val s = settings.current
        val today = now.toLocalDate()
        val leave = if (s.leaveAlerts && calendar.hasPermission()) {
            val events = runCatching { eventsBetween(today, today.plusDays(1)) }.getOrDefault(emptyList())
            val minutes = travelMinutes(events, s.home)
            AlertPlanner.leaveAlerts(events, s.home, { a, b -> minutes[a to b] }, s.bufferMinutes, now)
        } else emptyList()
        val morning = if (s.morningSummary) {
            AlertPlanner.morningAt(today, s.morningTime, now) ?: AlertPlanner.morningAt(today.plusDays(1), s.morningTime, now)
        } else null
        val reminders = if (s.remindTodos) {
            val routines = db.routines().all().associateBy { it.id }
            val timed = db.tasks().timedOpenBetween(today, today.plusDays(2))
            val exact = db.tasks().remindAtBetween(today.atStartOfDay(), today.plusDays(2).atStartOfDay())
            // Later sittings of split plans (the first is the to-do's own time).
            val window = today.atStartOfDay() to today.plusDays(2).atStartOfDay()
            val sittingInputs = db.tasks().withSittings().flatMap { t ->
                val lead = t.remindBefore?.takeIf { it >= 0 } ?: return@flatMap emptyList()
                val all = Sittings.decode(t.sittings)
                all.withIndex().drop(1).filter { (_, s) -> !s.start.isBefore(window.first) && s.start.isBefore(window.second) }
                    .map { (i, s) ->
                        ReminderInput(
                            taskId = t.id, title = "${t.title} (${i + 1}/${all.size})",
                            date = s.start.toLocalDate(), time = s.start.toLocalTime(), leadMinutes = lead, minutes = s.minutes,
                        )
                    }
            }
            val inputs = sittingInputs + (timed + exact).distinctBy { it.id }.mapNotNull { t ->
                val lead = when {
                    t.remindAt != null -> -1
                    // Routine to-dos remind at their start only if the routine says so.
                    t.routineId != null -> if (routines[t.routineId]?.remind == true) 0 else -1
                    else -> t.remindBefore ?: -1
                }
                if (lead < 0 && t.remindAt == null) return@mapNotNull null
                ReminderInput(
                    taskId = t.id,
                    title = t.title + if (t.units > 1) " ×${t.units}" else "",
                    date = t.date ?: t.remindAt!!.toLocalDate(), time = t.time,
                    leadMinutes = lead,
                    minutes = t.minutes,
                    at = t.remindAt,
                )
            }
            AlertPlanner.todoReminders(inputs, now)
        } else emptyList()
        val review = if (s.weeklyReview) {
            val d = WeeklyReview.nextReviewDate(today, s.reviewDay)
            val at = d.atTime(s.reviewTime)
            if (at.isAfter(now)) at else d.plusWeeks(1).atTime(s.reviewTime)
        } else null
        alerts.replaceAll(leave + reminders, morning, review)
    }

    /** For the reminder notification: still worth showing? */
    suspend fun isOpen(taskId: Long): Boolean = db.tasks().get(taskId)?.done == false

    /** "Done" from a reminder notification. */
    suspend fun markDone(taskId: Long) {
        val t = db.tasks().get(taskId) ?: return
        if (!t.done) toggle(taskId)
    }

    // ---------- change fan-out ----------

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var changeJob: Job? = null

    /**
     * Called after every edit. Bursts (ticking several things quickly) collapse into one
     * widget refresh and one alarm re-plan, off the calling coroutine.
     */
    private fun changed() {
        changeJob?.cancel()
        changeJob = background.launch {
            delay(400)
            refreshWidget()
            runCatching { scheduleAlerts() }.onFailure { Log.w("Repository", "Re-planning alerts failed", it) }
        }
    }

    /**
     * Runs a pending [changed] now. For receivers and widget actions, whose process may be
     * stopped as soon as they return.
     */
    suspend fun flushChanges() {
        changeJob?.cancel()
        changeJob = null
        refreshWidget()
        runCatching { scheduleAlerts() }.onFailure { Log.w("Repository", "Re-planning alerts failed", it) }
    }

    /** Built when the morning notification fires, so it reflects the day as it is then. */
    suspend fun morningSummary(): Pair<String, String> {
        val today = LocalDate.now()
        ensureFor(today)
        val events = runCatching { eventsBetween(today, today.plusDays(1)) }.getOrDefault(emptyList())
        val todos = db.tasks().today(today)
            .filter { !it.done }
            .map { it.title + if (it.units > 1) " ×${it.units}" else "" }
        return AlertPlanner.summary(events, todos)
    }

    suspend fun refreshWidget() {
        runCatching { TodayWidget().updateAll(context) }
            .onFailure { Log.w("Repository", "Widget update failed", it) }
    }
}
