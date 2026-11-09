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
import dev.nathanxu.dayplanner.domain.GoalStatus
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
import kotlinx.coroutines.flow.Flow
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

/** Everything the "New plan" form collects. */
data class PlanDraft(
    val title: String,
    val tag: String,
    val start: LocalDate,
    val end: LocalDate,
    /** Only used for single-day plans, which become an ordinary timed task. */
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    /** 1 = done once / tracked by steps; >1 = a count that gets broken down. */
    val target: Int,
    val cadence: Cadence,
    val recurring: Boolean,
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
        db.tasks().observeGoalStats(today),
        db.tasks().observeSteps(),
    ) { goals, stats, steps ->
        val statsBy = stats.associateBy { it.goalId }
        val stepsBy = steps.groupBy { it.goalId }
        goals.filter { !it.end.isBefore(today) }.map { g ->
            val s = statsBy[g.id]
            GoalProgress(
                goal = g,
                status = GoalStatus.of(
                    target = g.target, start = g.start, end = g.end, today = today,
                    doneUnits = s?.doneUnits ?: 0, stepTotal = s?.stepTotal ?: 0, stepDone = s?.stepDone ?: 0,
                    todayUnits = s?.todayUnits ?: 0, todayDoneUnits = s?.todayDoneUnits ?: 0,
                ),
                steps = stepsBy[g.id].orEmpty(),
            )
        }
    }

    suspend fun todayTasks(today: LocalDate): List<Task> = db.tasks().today(today)

    fun observeAllTodos(sinceMillis: Long): Flow<List<Task>> = db.tasks().observeAllTodos(sinceMillis)

    /** Finished one-off plans with their final status, newest first. */
    fun observeFinished(today: LocalDate): Flow<List<GoalProgress>> = combine(
        db.goals().observeFinished(),
        db.tasks().observeGoalStats(today),
        db.tasks().observeSteps(),
    ) { goals, stats, steps ->
        val statsBy = stats.associateBy { it.goalId }
        val stepsBy = steps.groupBy { it.goalId }
        goals.map { g ->
            val s = statsBy[g.id]
            GoalProgress(
                goal = g,
                status = GoalStatus.of(
                    target = g.target, start = g.start, end = g.end, today = g.end,
                    doneUnits = s?.doneUnits ?: 0, stepTotal = s?.stepTotal ?: 0, stepDone = s?.stepDone ?: 0,
                    todayUnits = 0, todayDoneUnits = 0,
                ),
                steps = stepsBy[g.id].orEmpty(),
            )
        }
    }

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
     * clears yesterday's unticked generated to-dos, finishes/renews plans, creates today's
     * routine to-dos and each plan's paced share for the current breakdown bucket.
     */
    suspend fun ensureFor(date: LocalDate) = housekeeping.withLock {
        val tasks = db.tasks()
        val goals = db.goals()
        tasks.deleteStaleGenerated("r:", date)
        tasks.deleteStaleGenerated("g:", date)

        for (g in goals.active()) {
            if (!g.end.isBefore(date)) continue
            tasks.deleteOpenSplits(g.id)
            goals.update(g.copy(archived = true))
            if (g.recurring) goals.insert(renewed(g, date))
        }

        for (g in goals.active()) {
            val cadence = g.effectiveCadence
            if (cadence == Cadence.NONE || g.target <= 1 || !g.isActiveOn(date)) continue
            val doneBefore = tasks.doneUnitsBefore(g.id, date)
            val q = Pacing.quotaToday(g.target, g.start, g.end, cadence, date, doneBefore)
            if (q > 0) {
                tasks.insertIgnore(
                    Task(
                        title = g.title, date = date, category = g.category, goalId = g.id,
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

    /** Next instance of a repeating plan, covering [date]. */
    private fun renewed(g: Goal, date: LocalDate): Goal = when (g.period) {
        Period.WEEK, Period.MONTH -> g.copy(id = 0, periodStart = g.period.startOf(date), endDate = null, archived = false)
        Period.CUSTOM -> {
            val len = ChronoUnit.DAYS.between(g.start, g.end) + 1
            var start = g.end.plusDays(1)
            while (start.plusDays(len - 1).isBefore(date)) start = start.plusDays(len)
            g.copy(id = 0, periodStart = start, endDate = start.plusDays(len - 1), archived = false)
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
                        .filter { it.isActiveOn(today) }
                        .firstOrNull { it.title.lowercase().contains(hint) }
                }
                val newTag = insertDraft(p, goal)
                buildString {
                    append("Added “${p.title}”")
                    append(" · ").append(p.date?.let { if (it == today) "today" else dayFmt.format(it) } ?: "someday")
                    p.time?.let { append(" ").append(hm.format(it)) }
                    if (goal != null) append(" → ${goal.title}")
                    else if (p.goalHint != null) append(" (no plan matched ^${p.goalHint})")
                    newTag?.let { append(" · new tag ${it.name}") }
                }
            }
            is GoalDraft -> {
                val (tagKey, newTag) = tagFor(p.tag)
                db.goals().insert(
                    Goal(
                        title = p.title, period = p.period, periodStart = p.period.startOf(today),
                        target = p.target, recurring = p.recurring, category = tagKey,
                    )
                )
                ensureFor(today)
                refreshWidget()
                val split = if (p.target > 1) ", split into daily to-dos" else ""
                "Plan added: ${p.title} ×${p.target} ${p.period.label.lowercase()}$split" +
                    (if (p.recurring) " ↻" else "") + (newTag?.let { " · new tag ${it.name}" } ?: "")
            }
        }
    }

    /** From the "New plan" form. A single-day plan with a count of 1 is just a task. */
    suspend fun createPlan(d: PlanDraft, today: LocalDate): String {
        if (d.title.isBlank()) return "Give it a title"
        if (d.end.isBefore(d.start)) return "End date is before the start"
        if (d.start == d.end && d.target <= 1) {
            val minutes = if (d.startTime != null && d.endTime != null && d.endTime.isAfter(d.startTime)) {
                Duration.between(d.startTime, d.endTime).toMinutes().toInt()
            } else null
            db.tasks().insert(
                Task(title = d.title.trim(), date = d.start, time = d.startTime, minutes = minutes, category = d.tag)
            )
            refreshWidget()
            return "Added “${d.title.trim()}” · " + if (d.start == today) "today" else dayFmt.format(d.start)
        }
        db.goals().insert(
            Goal(
                title = d.title.trim(), period = Period.CUSTOM, periodStart = d.start, endDate = d.end,
                target = d.target.coerceAtLeast(1), recurring = d.recurring, category = d.tag,
                cadence = d.cadence, split = d.cadence != Cadence.NONE,
            )
        )
        ensureFor(today)
        refreshWidget()
        val range = "${dayFmt.format(d.start)} – ${dayFmt.format(d.end)}"
        val breakdown = if (d.target > 1 && d.cadence != Cadence.NONE) " · ${d.cadence.label.lowercase()} to-dos" else ""
        return "Plan added: ${d.title.trim()} · $range$breakdown"
    }

    /** Adds a step to a plan from its card. */
    suspend fun addStep(goal: Goal, text: String, today: LocalDate): String {
        val p = QuickAdd.parse(text, today) as? TaskDraft ?: return "Steps need a title (no @week/@month)"
        insertDraft(p, goal)
        return "Step added to ${goal.title}"
    }

    /** Returns the tag it created, if any. */
    private suspend fun insertDraft(p: TaskDraft, goal: Goal?): Tag? {
        val (tagKey, newTag) = tagFor(p.tag)
        db.tasks().insert(
            Task(
                title = p.title, date = p.date, time = p.time, minutes = p.minutes, goalId = goal?.id,
                category = if (p.tag == null && goal != null) goal.category else tagKey,
            )
        )
        refreshWidget()
        return newTag
    }

    /** From the full to-do form (also used for steps, with goalId set). */
    suspend fun createTask(task: Task): String {
        if (task.title.isBlank()) return "Give it a title"
        db.tasks().insert(task.copy(id = 0, title = task.title.trim(), createdAt = System.currentTimeMillis()))
        refreshWidget()
        return "Added “${task.title.trim()}”"
    }

    /** Puts back something you just deleted (snackbar Undo). */
    suspend fun restoreTask(task: Task) {
        db.tasks().insertIgnore(task)
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

    /**
     * Checkbox on a task. A plan's to-do worth several units (e.g. "LeetCode ×3 this week") ticks
     * off one unit at a time: each tap logs 1 towards the plan and the to-do shrinks to ×2.
     */
    suspend fun toggle(id: Long) {
        val t = db.tasks().get(id) ?: return
        if (!t.done && t.units > 1 && t.goalId != null && t.genKey?.startsWith("g:") == true) {
            db.tasks().insert(
                Task(
                    title = t.title, date = t.date, category = t.category, goalId = t.goalId,
                    genKey = GenKeys.log(t.goalId), done = true, doneAt = System.currentTimeMillis(),
                )
            )
            db.tasks().update(t.copy(units = t.units - 1))
            refreshWidget()
            return
        }
        setDone(id, !t.done)
    }

    suspend fun deleteTask(id: Long) {
        db.tasks().delete(id)
        refreshWidget()
    }

    // ---------- goals / plans ----------

    /** "+1": logs a done unit against the plan for today. */
    suspend fun logGoal(goal: Goal, today: LocalDate) {
        db.tasks().insert(
            Task(
                title = goal.title, date = today, category = goal.category, goalId = goal.id,
                genKey = GenKeys.log(goal.id), done = true, doneAt = System.currentTimeMillis(),
            )
        )
        refreshWidget()
    }

    /**
     * "−1": takes back the most recent unit of progress. A +1 log is removed (and if it came from
     * partly ticking today's ×n to-do, that to-do gets its unit back); a ticked to-do or step is
     * unticked.
     */
    suspend fun unlogGoal(goal: Goal, today: LocalDate): String {
        val t = db.tasks().latestDoneForGoal(goal.id) ?: return "Nothing to take back on ${goal.title}"
        if (t.genKey?.startsWith("l:") == true) {
            db.tasks().delete(t.id)
            if (t.date == today) {
                db.tasks().byKey(GenKeys.split(goal.id, today))?.takeIf { !it.done }?.let {
                    db.tasks().update(it.copy(units = it.units + 1))
                }
            }
        } else {
            db.tasks().setDone(t.id, false, null)
        }
        refreshWidget()
        return ""
    }

    /** Saves an edited plan; today's to-do is recalculated whenever anything it depends on changed. */
    suspend fun updateGoal(goal: Goal, today: LocalDate) {
        val old = db.goals().get(goal.id)
        val fixed = goal.copy(target = goal.target.coerceAtLeast(1))
        db.goals().update(fixed)
        if (old == null || old.target != fixed.target || old.split != fixed.split || old.title != fixed.title ||
            old.start != fixed.start || old.end != fixed.end || old.effectiveCadence != fixed.effectiveCadence ||
            old.category != fixed.category
        ) {
            db.tasks().deleteOpenSplits(goal.id)
            ensureFor(today)
        }
        refreshWidget()
    }

    /** Moves a plan's end date (and brings a finished one back). Today's to-do is re-paced. */
    suspend fun extendGoal(goal: Goal, newEnd: LocalDate, today: LocalDate): String {
        if (newEnd.isBefore(goal.start)) return "End can't be before the start"
        db.goals().update(goal.copy(endDate = newEnd, period = Period.CUSTOM, archived = newEnd.isBefore(today)))
        db.tasks().deleteOpenSplits(goal.id)
        ensureFor(today)
        refreshWidget()
        return "${goal.title} now runs to ${dayFmt.format(newEnd)}"
    }

    suspend fun restoreGoal(goal: Goal, today: LocalDate) {
        db.goals().insert(goal)
        ensureFor(today)
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
        val (tagKey, newTag) = tagFor(p.tag)
        val id = db.routines().insert(
            Routine(
                title = p.title, category = tagKey, minutes = p.minutes, daysMask = daysMask,
                time = p.time, syncToCalendar = sync,
            )
        )
        ensureFor(today)
        val status = syncRoutines()
        refreshWidget()
        val clashes = p.time?.let { routineClashes(daysMask, it, p.minutes ?: 30, id, today) }.orEmpty()
        val base = "Routine added: ${p.title} · ${Days.describe(daysMask)}" + (p.time?.let { " ${hm.format(it)}" } ?: "")
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
        if (r.daysMask == 0) return "Pick at least one day"
        val id = db.routines().insert(r.copy(id = 0, title = r.title.trim(), calendarEventId = null))
        ensureFor(today)
        val status = syncRoutines()
        refreshWidget()
        val clashes = r.time?.let { routineClashes(r.daysMask, it, r.minutes ?: 30, id, today) }.orEmpty()
        return buildString {
            append("Routine added: ${r.title.trim()} · ${Days.describe(r.daysMask)}")
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
        val clashes = r.time?.let { routineClashes(r.daysMask, it, r.minutes ?: 30, r.id, today) }.orEmpty()
        return buildString {
            append(if (status.ok) "Saved “${r.title}”" else "Saved. ${status.message}")
            if (clashes.isNotEmpty()) append("\n⚠ Clashes: ").append(clashes.joinToString("; "))
        }
    }

    /**
     * What a routine on [mask] days at [time] would overlap in the next 4 weeks: events in your
     * linked calendars and your other routines. [excludeRoutineId] is the routine itself (its own
     * calendar event doesn't count).
     */
    suspend fun routineClashes(
        mask: Int, time: LocalTime, minutes: Int, excludeRoutineId: Long?, from: LocalDate = LocalDate.now(),
    ): List<String> {
        val days = 28
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
            busy += Clashes.routineBusy(o.daysMask, o.time, o.minutes ?: 30, "${o.title} (routine)", from, days)
        }
        return Clashes.summarise(Clashes.find(mask, time, minutes, from, days, busy))
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

    suspend fun restoreRoutine(r: Routine, today: LocalDate) {
        db.routines().insert(r.copy(calendarEventId = null))
        ensureFor(today)
        syncRoutines()
        refreshWidget()
    }

    suspend fun deleteRoutine(r: Routine) {
        r.calendarEventId?.let { calendar.deleteEvent(it) }
        db.routines().delete(r)
        routineCalendar(calendar.calendars()).getOrNull()?.let { calendar.requestSync(it.accountName, it.accountType) }
    }

    /**
     * Makes Google Calendar match the routines: each active, timed routine that wants syncing gets
     * one weekly recurring event in the routine calendar; events for routines that don't (or that
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
        alerts.replaceAll(leave, morning)
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
