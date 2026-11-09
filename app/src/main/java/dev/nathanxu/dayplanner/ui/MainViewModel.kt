package dev.nathanxu.dayplanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nathanxu.dayplanner.calendar.CalInfo
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.PlanDraft
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.SettingsState
import dev.nathanxu.dayplanner.data.SyncStatus
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.TodayPlan
import dev.nathanxu.dayplanner.domain.CalendarLayout
import dev.nathanxu.dayplanner.domain.CalendarMode
import dev.nathanxu.dayplanner.domain.DaySegment
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.notify.Notifier
import dev.nathanxu.dayplanner.notify.ScheduledAlert
import dev.nathanxu.dayplanner.repository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** A snackbar message, optionally with an action such as Undo. */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Notification health for the Travel & alerts settings tab. */
data class AlertState(
    val notificationsOn: Boolean = false,
    val exactAlarms: Boolean = true,
    val upcoming: List<ScheduledAlert> = emptyList(),
)

/** What the Calendar page is showing. [selected] is the highlighted day in month view. */
data class CalendarView(
    val mode: CalendarMode,
    val anchor: LocalDate,
    val selected: LocalDate,
    /** How many days the scrolling schedule covers from [anchor]. */
    val scheduleDays: Int = 28,
) {
    /** The date range whose events and tasks need loading. */
    val range: Pair<LocalDate, LocalDate>
        get() = when (mode) {
            CalendarMode.SCHEDULE -> anchor to anchor.plusDays(scheduleDays.toLong())
            CalendarMode.THREE_DAY, CalendarMode.WEEK ->
                CalendarLayout.gridDays(mode, anchor).let { it.first() to it.last().plusDays(1) }
            CalendarMode.MONTH ->
                CalendarLayout.monthGrid(YearMonth.from(anchor)).let { it.first() to it.last().plusDays(1) }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repository
    private val started = SharingStarted.WhileSubscribed(5_000)

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    val tasks: StateFlow<List<Task>> =
        _today.flatMapLatest { repo.observeToday(it) }.stateIn(viewModelScope, started, emptyList())
    val upcoming: StateFlow<List<Task>> =
        _today.flatMapLatest { repo.observeUpcoming(it) }.stateIn(viewModelScope, started, emptyList())
    val goals: StateFlow<List<GoalProgress>> =
        _today.flatMapLatest { repo.observeGoals(it) }.stateIn(viewModelScope, started, emptyList())
    val routines: StateFlow<List<Routine>> =
        repo.observeRoutines().stateIn(viewModelScope, started, emptyList())

    private val _plan = MutableStateFlow<TodayPlan?>(null)
    val plan: StateFlow<TodayPlan?> = _plan.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _undo = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    /** Everything the snackbar shows. A new message replaces the current one rather than queueing. */
    val snackbars: Flow<UiMessage> = merge(_messages.map { UiMessage(it) }, _undo)

    private fun offerUndo(text: String, undo: suspend () -> Unit) {
        _undo.tryEmit(UiMessage(text, "Undo") { viewModelScope.launch { undo() } })
    }

    // ---------- lists ----------

    /** All open to-dos plus those ticked in the last week. */
    val allTodos: StateFlow<List<Task>> =
        repo.observeAllTodos(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            .stateIn(viewModelScope, started, emptyList())
    val finished: StateFlow<List<GoalProgress>> =
        _today.flatMapLatest { repo.observeFinished(it) }.stateIn(viewModelScope, started, emptyList())

    // ---------- calendar change watching ----------

    private var calendarObserverOn = false
    private var reloadJob: Job? = null
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // The provider fires in bursts while syncing; wait for it to settle, then reload once.
            reloadJob?.cancel()
            reloadJob = viewModelScope.launch {
                delay(1500)
                _plan.value = runCatching { repo.todayPlan() }.getOrNull() ?: _plan.value
                loadCalendarRange()
                repo.refreshWidget()
                repo.scheduleAlerts()
            }
        }
    }

    /** Watches the phone's calendar so Google Calendar edits appear without reopening the app. */
    private fun watchCalendar() {
        if (calendarObserverOn || !repo.calendar.hasPermission()) return
        calendarObserverOn = runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        }.isSuccess
    }

    override fun onCleared() {
        if (calendarObserverOn) getApplication<Application>().contentResolver.unregisterContentObserver(calendarObserver)
        super.onCleared()
    }

    private val _canWriteCalendar = MutableStateFlow(repo.calendar.hasWritePermission())
    val canWriteCalendar: StateFlow<Boolean> = _canWriteCalendar.asStateFlow()

    // ---------- settings ----------

    val settings: StateFlow<SettingsState> = repo.settings.state
    val syncStatus: StateFlow<SyncStatus?> = repo.syncStatus

    private val _calendars = MutableStateFlow<List<CalInfo>>(emptyList())
    val calendars: StateFlow<List<CalInfo>> = _calendars.asStateFlow()

    fun loadCalendars() = viewModelScope.launch { _calendars.value = repo.calendars() }

    fun setAccountLinked(name: String, linked: Boolean) = viewModelScope.launch {
        repo.setAccountLinked(name, linked)
        afterCalendarChoice()
    }

    fun setCalendarShown(id: Long, shown: Boolean) = viewModelScope.launch {
        repo.setCalendarShown(id, shown)
        refresh()
    }

    fun enableCalendarSync(cal: CalInfo) = viewModelScope.launch {
        _messages.emit(repo.enableCalendarSync(cal))
        refresh()
    }

    fun chooseRoutineCalendar(id: Long) = viewModelScope.launch {
        repo.chooseRoutineCalendar(id)
        afterCalendarChoice()
    }

    /** Moves routine events to the new calendar and reloads what's shown. */
    private suspend fun afterCalendarChoice() {
        val status = repo.syncRoutines()
        _messages.emit(status.message)
        refresh()
    }

    fun syncNow() = viewModelScope.launch { _messages.emit(repo.syncRoutines().message) }

    // ---------- travel & alerts ----------

    val travelEnabled: Boolean get() = repo.travelEnabled

    private val _alertState = MutableStateFlow(AlertState())
    val alertState: StateFlow<AlertState> = _alertState.asStateFlow()

    private fun readAlertState() {
        val ctx = getApplication<Application>()
        _alertState.value = AlertState(
            notificationsOn = Notifier.enabled(ctx),
            exactAlarms = repo.alerts.canExact(),
            upcoming = repo.alerts.upcoming(),
        )
    }

    /** Applies a travel/alert setting, then re-plans alarms (and the schedule, for travel changes). */
    fun updateTravel(transform: (SettingsState) -> SettingsState) = viewModelScope.launch {
        val before = repo.settings.current
        repo.settings.update(transform)
        val after = repo.settings.current
        if (before.home != after.home || before.travelMode != after.travelMode) {
            _plan.value = runCatching { repo.todayPlan() }.getOrNull()
        }
        repo.scheduleAlerts()
        readAlertState()
    }

    fun rescheduleAlerts() = viewModelScope.launch {
        repo.scheduleAlerts()
        readAlertState()
    }

    fun testNotification() {
        val ctx = getApplication<Application>()
        val next = repo.alerts.upcoming().firstOrNull()
        val ok = Notifier.post(
            ctx, 1, Notifier.CH_LEAVE, "DayPlanner test",
            "Notifications work." + (next?.let { " Next: ${it.title} at ${it.at.toLocalTime().withSecond(0).withNano(0)}" } ?: ""),
        )
        if (!ok) _messages.tryEmit("Notifications are off for DayPlanner — allow them first")
        readAlertState()
    }

    // ---------- tags ----------

    val tags: StateFlow<List<Tag>> = repo.tags.tags

    /** Returns the new (or existing same-named) tag, or null if the name isn't allowed. */
    fun addTag(name: String, color: Int?): Tag? {
        if (!Tags.isValidName(name)) {
            _messages.tryEmit("Tags are letters/numbers, up to 20 characters, starting with a letter")
            return null
        }
        return repo.tags.add(name, color)
    }
    fun updateTag(tag: Tag) = repo.tags.update(tag)
    fun deleteTag(key: String) = repo.tags.delete(key)

    // ---------- calendar page ----------

    private val _calView = MutableStateFlow(
        LocalDate.now().let { CalendarView(CalendarMode.SCHEDULE, it, it) }
    )
    val calView: StateFlow<CalendarView> = _calView.asStateFlow()

    private val _calEvents = MutableStateFlow<Map<LocalDate, List<DaySegment>>>(emptyMap())
    val calEvents: StateFlow<Map<LocalDate, List<DaySegment>>> = _calEvents.asStateFlow()

    private val _calRange = MutableStateFlow(_calView.value.range)
    val calTasks: StateFlow<List<Task>> =
        _calRange.flatMapLatest { (s, e) -> repo.observeBetween(s, e) }.stateIn(viewModelScope, started, emptyList())

    private var calJob: Job? = null

    private fun setCalView(v: CalendarView) {
        _calView.value = v
        loadCalendarRange()
    }

    fun loadCalendarRange() {
        val (s, e) = _calView.value.range
        _calRange.value = s to e
        calJob?.cancel()
        calJob = viewModelScope.launch {
            _calEvents.value = CalendarLayout.byDay(repo.eventsBetween(s, e), s, e)
        }
    }

    fun setCalMode(mode: CalendarMode) {
        val v = _calView.value
        val anchor = if (mode == CalendarMode.MONTH) v.selected.withDayOfMonth(1) else v.selected
        setCalView(v.copy(mode = mode, anchor = anchor, scheduleDays = 28))
    }

    fun stepCal(forward: Boolean) {
        val v = _calView.value
        val anchor = CalendarLayout.step(v.mode, v.anchor, forward)
        setCalView(v.copy(anchor = anchor, selected = anchor, scheduleDays = 28))
    }

    fun calToday() {
        val t = LocalDate.now()
        val v = _calView.value
        setCalView(v.copy(anchor = if (v.mode == CalendarMode.MONTH) t.withDayOfMonth(1) else t, selected = t, scheduleDays = 28))
    }

    fun extendSchedule() {
        val v = _calView.value
        if (v.scheduleDays < 365) setCalView(v.copy(scheduleDays = v.scheduleDays + 28))
    }

    fun selectDay(date: LocalDate) {
        _calView.value = _calView.value.copy(selected = date)
    }

    /** Month cell → open that day in the 3-day view. */
    fun openDay(date: LocalDate) = setCalView(_calView.value.copy(mode = CalendarMode.THREE_DAY, anchor = date, selected = date))

    // ---------- lifecycle ----------

    fun refresh() = viewModelScope.launch {
        watchCalendar()
        if (repo.calendar.hasPermission()) repo.requestCalendarSync()
        val d = LocalDate.now()
        _today.value = d
        _canWriteCalendar.value = repo.calendar.hasWritePermission()
        repo.ensureFor(d)
        repo.syncRoutines()
        repo.refreshWidget()
        _loading.value = true
        _plan.value = runCatching { repo.todayPlan() }.getOrElse {
            _messages.tryEmit("Couldn't load calendar: ${it.message}")
            null
        }
        _loading.value = false
        loadCalendarRange()
        _calendars.value = repo.calendars()
        repo.scheduleAlerts()
        readAlertState()
    }

    // ---------- tasks ----------

    fun quickAdd(text: String) = viewModelScope.launch {
        _messages.emit(repo.quickAdd(text, _today.value))
    }

    fun toggle(task: Task) = viewModelScope.launch { repo.setDone(task.id, !task.done) }

    fun updateTask(task: Task) = viewModelScope.launch {
        repo.updateTask(task)
        _messages.emit("Saved “${task.title}”")
    }

    fun createTask(task: Task) = viewModelScope.launch { _messages.emit(repo.createTask(task)) }

    fun delete(task: Task) = viewModelScope.launch {
        repo.deleteTask(task.id)
        offerUndo("Deleted “${task.title}”") { repo.restoreTask(task) }
    }

    // ---------- goals ----------

    fun logGoal(goal: Goal) = viewModelScope.launch { repo.logGoal(goal, _today.value) }
    fun unlogGoal(goal: Goal) = viewModelScope.launch {
        repo.unlogGoal(goal, _today.value).takeIf { it.isNotEmpty() }?.let { _messages.emit(it) }
    }
    fun extendGoal(goal: Goal, newEnd: LocalDate) = viewModelScope.launch {
        _messages.emit(repo.extendGoal(goal, newEnd, _today.value))
    }
    fun setRecurring(goal: Goal, on: Boolean) = viewModelScope.launch { repo.setRecurring(goal, on) }
    fun setSplit(goal: Goal, on: Boolean) = viewModelScope.launch { repo.setSplit(goal, on, _today.value) }
    fun updateGoal(goal: Goal) = viewModelScope.launch {
        repo.updateGoal(goal, _today.value)
        _messages.emit("Saved “${goal.title}”")
    }
    fun deleteGoal(goal: Goal) = viewModelScope.launch {
        repo.deleteGoal(goal)
        offerUndo("Deleted “${goal.title}”") { repo.restoreGoal(goal, _today.value) }
    }
    fun createPlan(draft: PlanDraft) = viewModelScope.launch {
        _messages.emit(repo.createPlan(draft, _today.value))
    }
    fun addStep(goal: Goal, text: String) = viewModelScope.launch {
        _messages.emit(repo.addStep(goal, text, _today.value))
    }

    // ---------- routines ----------

    fun addRoutine(text: String, mask: Int, sync: Boolean) = viewModelScope.launch {
        _messages.emit(repo.addRoutine(text, mask, sync, _today.value))
    }
    fun createRoutine(r: Routine) = viewModelScope.launch {
        _messages.emit(repo.createRoutine(r, _today.value))
    }
    fun updateRoutine(r: Routine) = viewModelScope.launch {
        _messages.emit(repo.updateRoutine(r, _today.value))
    }
    /** For the routine editor's live clash warning. */
    suspend fun clashesFor(mask: Int, time: LocalTime, minutes: Int, routineId: Long?): List<String> =
        runCatching { repo.routineClashes(mask, time, minutes, routineId) }.getOrDefault(emptyList())

    fun setRoutineSync(r: Routine, on: Boolean) = viewModelScope.launch {
        repo.setRoutineSync(r, on)?.let { _messages.emit(it) }
    }
    fun setRoutineActive(r: Routine, on: Boolean) = viewModelScope.launch { repo.setRoutineActive(r, on) }
    fun deleteRoutine(r: Routine) = viewModelScope.launch {
        repo.deleteRoutine(r)
        offerUndo("Deleted “${r.title}”") { repo.restoreRoutine(r, _today.value) }
    }
}
