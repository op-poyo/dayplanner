package dev.nathanxu.dayplanner.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import dev.nathanxu.dayplanner.notify.Notifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.GoalDraft
import dev.nathanxu.dayplanner.domain.QuickAdd
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.Priority
import dev.nathanxu.dayplanner.domain.TodoSort
import dev.nathanxu.dayplanner.domain.Repeat
import dev.nathanxu.dayplanner.domain.TodayView
import dev.nathanxu.dayplanner.data.withRepeat
import dev.nathanxu.dayplanner.domain.TaskDraft
import java.time.LocalDate
import dev.nathanxu.dayplanner.data.Task
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Incremented each time the widget's "+" opens the app, so the quick-add box takes focus. */
    private val quickAddRequests = mutableIntStateOf(0)

    /** Incremented when the weekly-review notification is tapped. */
    private val reviewRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            DayPlannerTheme { AppRoot(vm, quickAddRequests.intValue, reviewRequests.intValue) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // picks up calendar changes and date rollover
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true) {
            intent.removeExtra(EXTRA_QUICK_ADD)
            quickAddRequests.intValue++
        }
        if (intent?.getStringExtra(Notifier.EXTRA_OPEN) == Notifier.OPEN_REVIEW) {
            intent.removeExtra(Notifier.EXTRA_OPEN)
            reviewRequests.intValue++
        }
    }

    companion object {
        const val EXTRA_QUICK_ADD = "quick_add"
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Home),
    CALENDAR("Calendar", Icons.Default.DateRange),
    TODOS("To-dos", Icons.Default.List),
    GOALS("Goals", Icons.Default.Star),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppRoot(vm: MainViewModel, quickAddRequest: Int, reviewRequest: Int = 0) {
    val today by vm.today.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val canWriteCalendar by vm.canWriteCalendar.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val calView by vm.calView.collectAsStateWithLifecycle()
    val calEvents by vm.calEvents.collectAsStateWithLifecycle()
    val calTasks by vm.calTasks.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val alertState by vm.alertState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    /** null = the Settings index page; otherwise which section is open. */
    var settingsSection by rememberSaveable { mutableStateOf<Int?>(null) }
    var showSyntax by remember { mutableStateOf(false) }
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }
    var creatingPlan by remember { mutableStateOf(false) }
    var planPrefill by remember { mutableStateOf<GoalDraft?>(null) }
    var planTitle by remember { mutableStateOf<String?>(null) }
    var tasksSegment by rememberSaveable { mutableIntStateOf(0) }
    val allTodos by vm.allTodos.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val planner by vm.planner.collectAsStateWithLifecycle()
    val slipped by vm.slipped.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val onboarding by vm.onboarding.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    // Backups go through Android's file picker, so Google Drive, Downloads etc. all work.
    val backupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportBackup(it) }
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }
    val startBackup = { backupFile.launch("DayPlanner-backup-${LocalDate.now()}.json") }
    val startRestore = { restoreFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
    var editingRoutine by remember { mutableStateOf<Routine?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val quickAddFocus = remember { FocusRequester() }

    // Read for the timeline, write so routines can become recurring events. Asked together once.
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }
    val requestCalendar = {
        calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }
    val openSettings = {
        vm.loadCalendars()
        vm.rescheduleAlerts()
        showSettings = true
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.rescheduleAlerts()
    }
    // Ask for notifications once (Android 13+), since leave-now alerts are on by default.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.hasPermission(context) &&
            !prefs.getBoolean("asked_notifications", false) && vm.settings.value.onboarded
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val tagActions = TagActions(tags) { name, color -> vm.addTag(name, color) }
    val settingsActions = remember(vm) {
        SettingsActions(
            requestCalendar = { calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            setAccountLinked = { acc, on -> vm.setAccountLinked(acc, on) },
            setCalendarShown = { id, on -> vm.setCalendarShown(id, on) },
            enableSync = { vm.enableCalendarSync(it) },
            chooseRoutineCalendar = { vm.chooseRoutineCalendar(it) },
            syncNow = { vm.syncNow() },
            addTag = { name, color -> vm.addTag(name, color) },
            updateTag = { vm.updateTag(it) },
            deleteTag = { vm.deleteTag(it) },
            updateTravel = { transform -> vm.updateTravel(transform) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.hasPermission(context)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Permission granted but notifications switched off: open the app's notification settings.
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            openExactAlarmSettings = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            },
            testNotification = { vm.testNotification() },
            exportBackup = startBackup,
            importBackup = startRestore,
            openReview = { vm.openReview() },
        )
    }
    // Not remembered: these capture today and tags, which change.
    val goalActions = run {
        GoalActions(
            onLog = { vm.logGoal(it) },
            onUnlog = { vm.unlogGoal(it) },
            onSetRecurring = { g, on -> vm.setRecurring(g, on) },
            onSetSplit = { g, on -> vm.setSplit(g, on) },
            onDelete = { vm.deleteGoal(it) },
            onAddStep = { g, text -> vm.addStep(g, text) },
            onToggleStep = { vm.toggle(it) },
            onEdit = { editingGoal = it },
            onEditStep = { editingTask = it },
            onExpandStep = { g, text -> editingTask = draftTask(text, today, tags, vm).copy(goalId = g.id, category = g.category) },
            onExtend = { g, d -> vm.extendGoal(g, d) },
            onClearFinished = { vm.clearFinished() },
        )
    }
    val todoActions = remember(vm) {
        TodoActions(onToggle = { vm.toggle(it) }, onEdit = { editingTask = it }, onDelete = { vm.delete(it) })
    }
    val openPlanForm = { text: String ->
        when (val p = QuickAdd.parse(text, today)) {
            is GoalDraft -> { planPrefill = p; planTitle = null }
            is TaskDraft -> { planPrefill = null; planTitle = p.title }
            null -> { planPrefill = null; planTitle = null }
        }
        creatingPlan = true
    }
    /** Quick-add text → full to-do form; "@week" typed in the to-do box opens the task form instead. */
    /** Quick-add text → the repeating to-do form (weekdays if no repeat was typed). */
    val openRepeatingForm = { text: String ->
        val d = QuickAdd.parse(text, today) as? TaskDraft
        val rep = d?.repeat ?: Repeat.weekly(Days.WEEKDAYS)
        editingRoutine = Routine(
            title = d?.title ?: "", daysMask = rep.daysMask, time = d?.time, minutes = d?.minutes,
            category = tagKeyFor(d?.tag, tags, vm), syncToCalendar = true,
        ).withRepeat(rep)
    }
    val openTodoForm = { text: String ->
        val parsed = QuickAdd.parse(text, today)
        when {
            parsed is GoalDraft -> openPlanForm(text)
            parsed is TaskDraft && parsed.repeat != null -> openRepeatingForm(text)
            else -> editingTask = draftTask(text, today, tags, vm)
        }
        Unit
    }

    BackHandler(enabled = showSettings) { if (settingsSection != null) settingsSection = null else showSettings = false }
    BackHandler(enabled = showSearch && !showSettings) { showSearch = false }
    // A new message replaces the one showing (collectLatest cancels the old wait) instead of queueing.
    LaunchedEffect(Unit) {
        vm.snackbars.collectLatest { m ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    // Keep search results current after editing or ticking one.
    LaunchedEffect(allTodos, goals, routines, finished) { if (showSearch) vm.rerunSearch() }
    LaunchedEffect(reviewRequest) {
        if (reviewRequest > 0) vm.openReview()
    }
    LaunchedEffect(quickAddRequest) {
        if (quickAddRequest > 0) {
            showSettings = false
            showSearch = false
            tab = Tab.TODAY
            // Give the Today list a frame to compose before focusing.
            kotlinx.coroutines.delay(150)
            runCatching { quickAddFocus.requestFocus() }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything in the app is replaced by the backup's to-dos, goals, repeating to-dos, tags and settings. You can undo straight after.") },
            confirmButton = {
                TextButton(onClick = { vm.importBackup(uri); pendingRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }

    // First launch: setup instead of the app (and nothing while we're still checking).
    if (onboarding != false) {
        if (onboarding == true) Box(Modifier.fillMaxSize()) {
            Onboarding(
                settings = settings,
                calendarConnected = plan?.calendarConnected == true,
                accounts = calendars.filter { it.isGoogle }.map { it.accountName }.distinct(),
                notificationsOn = alertState.notificationsOn,
                a = OnboardingActions(
                    requestCalendar = requestCalendar,
                    setAccountLinked = { acc, on -> vm.setAccountLinked(acc, on) },
                    requestNotifications = settingsActions.requestNotifications,
                    update = { t -> vm.updateTravel(t) },
                    quickAdd = { vm.quickAdd(it) },
                    restore = startRestore,
                    finish = { vm.finishOnboarding() },
                ),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showSettings || showSearch) {
                        IconButton(onClick = {
                            when {
                                showSettings && settingsSection != null -> settingsSection = null
                                showSettings -> showSettings = false
                                else -> showSearch = false
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                },
                title = {
                    Text(
                        when {
                            showSettings -> settingsSection?.let { SETTINGS_SECTIONS[it].first } ?: "Settings"
                            showSearch -> "Search"
                            tab == Tab.TODAY -> today.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                            else -> tab.label
                        },
                    )
                },
                actions = {
                    if (!showSettings && !showSearch) {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "Search") }
                        if (tab == Tab.TODAY || tab == Tab.CALENDAR) {
                            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        }
                        IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (!showSettings && !showSearch) Column(Modifier.imePadding()) {
                // One way to add: this box (✎ opens the full form, ? the syntax).
                if (tab != Tab.CALENDAR) {
                    Surface(tonalElevation = 3.dp) {
                        QuickAddBar(
                            placeholder = when (tab) {
                                Tab.GOALS -> "LeetCode x15 @month #quant"
                                Tab.TODOS -> if (tasksSegment == 1) "Gym 18:00 1h every mon,wed" else "Essay 2h by fri !high #study"
                                else -> "Essay 2h by fri !high #study"
                            },
                            onSubmit = { vm.quickAdd(it) },
                            onExpand = { text ->
                                when {
                                    tab == Tab.GOALS -> openPlanForm(text)
                                    tab == Tab.TODOS && tasksSegment == 1 -> openRepeatingForm(text)
                                    else -> openTodoForm(text)
                                }
                            },
                            onHelp = { showSyntax = true },
                            focusRequester = quickAddFocus,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                // The tab bar steps aside while typing, so the box sits right on the keyboard.
                if (!WindowInsets.isImeVisible) NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Calendar has no quick-add box: a button adds a to-do on the selected day.
            if (!showSettings && !showSearch && tab == Tab.CALENDAR) {
                ExtendedFloatingActionButton(
                    onClick = { editingTask = Task(title = "", date = calView.selected) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New to-do") },
                )
            }
        },
    ) { padding ->
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                calendars = calendars,
                syncStatus = syncStatus,
                canWriteCalendar = canWriteCalendar,
                tags = tags,
                travelEnabled = vm.travelEnabled,
                alertState = alertState,
                actions = settingsActions,
                contentPadding = padding,
                section = settingsSection,
                onSection = { settingsSection = it },
            )
        } else if (showSearch) {
            SearchScreen(
                query = searchQuery,
                onQuery = { vm.setSearch(it) },
                results = searchResults,
                tags = tags,
                today = today,
                onTask = { editingTask = it },
                onToggle = { vm.toggle(it) },
                onGoal = { editingGoal = it },
                onRoutine = { editingRoutine = it },
                contentPadding = padding,
            )
        } else when (tab) {
            Tab.TODAY -> TodayScreen(
                today = today,
                tasks = tasks,
                upcoming = upcoming,
                plan = plan,
                goals = goals,
                tags = tags,
                loading = loading,
                routines = routines,
                later = TodayView.laterSummary(
                    allTodos.filter { !it.done && (it.date == null || it.date.isAfter(today)) }.map { it.date }, today,
                ),
                onOpenGoals = { tab = Tab.GOALS },
                onOpenTodos = { tab = Tab.TODOS; tasksSegment = 0 },
                onToggle = { vm.toggle(it) },
                onDelete = { vm.delete(it) },
                onEditTask = { editingTask = it },
                onConnectCalendar = requestCalendar,
                onOpenSettings = openSettings,
                contentPadding = padding,
                slipped = slipped,
                onPlan = { vm.openPlanner(1) },
                reviewDue = settings.weeklyReview && today.dayOfWeek == settings.reviewDay,
                onReview = { vm.openReview() },
                sort = TodoSort.of(settings.todoSort),
                onSort = { vm.setSort(it) },
                onTickUpcoming = { vm.tickWithUndo(it) },
                showSwipeTip = !settings.swipeTipSeen,
                onDismissSwipeTip = { vm.dismissSwipeTip() },
            )
            Tab.CALENDAR -> CalendarScreen(
                view = calView,
                today = today,
                events = calEvents,
                tasks = calTasks,
                tags = tags,
                onMode = { vm.setCalMode(it) },
                onStep = { vm.stepCal(it) },
                onToday = { vm.calToday() },
                onLoadMore = { vm.extendSchedule() },
                onSelectDay = { vm.selectDay(it) },
                onOpenDay = { vm.openDay(it) },
                onToggleTask = { vm.toggle(it) },
                onEditTask = { editingTask = it },
                contentPadding = padding,
            )
            Tab.TODOS -> TodosScreen(
                today = today,
                segment = tasksSegment,
                onSegment = { tasksSegment = it },
                todos = allTodos,
                tags = tags,
                todoActions = todoActions,
                contentPadding = padding,
                sort = TodoSort.of(settings.todoSort),
                onSort = { vm.setSort(it) },
                showSwipeTip = !settings.swipeTipSeen,
                onDismissSwipeTip = { vm.dismissSwipeTip() },
                repeating = { header ->
                    RoutinesScreen(
                        routines = routines,
                        tags = tags,
                        canWriteCalendar = canWriteCalendar,
                        onRequestCalendar = requestCalendar,
                        onSetSync = { r, on -> vm.setRoutineSync(r, on) },
                        onSetActive = { r, on -> vm.setRoutineActive(r, on) },
                        onDelete = { vm.deleteRoutine(it) },
                        onEdit = { editingRoutine = it },
                        onNew = { openRepeatingForm("") },
                        syncStatus = syncStatus,
                        onOpenSettings = openSettings,
                        contentPadding = padding,
                        header = header,
                    )
                },
            )
            Tab.GOALS -> GoalsScreen(
                today = today,
                goals = goals,
                finished = finished,
                tags = tags,
                onNewGoal = { openPlanForm("") },
                onReview = { vm.openReview() },
                actions = goalActions,
                contentPadding = padding,
            )
        }
    }

    // ---------- edit dialogs ----------

    editingTask?.let { t ->
        TaskEditor(
            task = t,
            goals = goals.map { it.goal },
            tagActions = tagActions,
            onSave = {
                if (it.id == 0L) vm.createTask(it) else vm.updateTask(it)
                editingTask = null
            },
            onDelete = { vm.delete(it); editingTask = null },
            onDismiss = { editingTask = null },
            places = settings.places,
            defaultReminder = settings.reminderLead,
            onMakeRepeating = { d ->
                editingTask = null
                editingRoutine = Routine(
                    title = d.title, daysMask = Days.WEEKDAYS, time = d.time, minutes = d.minutes,
                    category = d.category, syncToCalendar = true,
                )
            },
            onEditSeries = { id ->
                routines.firstOrNull { it.id == id }?.let { r -> editingTask = null; editingRoutine = r }
            },
        )
    }

    if (showSyntax) SyntaxSheet(onDismiss = { showSyntax = false })

    review?.let { s ->
        ReviewSheet(
            s = s,
            goals = goals,
            tags = tags,
            onWeek = { vm.openReview(it) },
            onMove = { id, d -> vm.reviewMove(id, d) },
            onDone = { vm.reviewDone(it) },
            onPlanNextWeek = { vm.planNextWeek() },
            onDismiss = { vm.closeReview() },
        )
    }

    planner?.let { p ->
        PlanSheet(
            state = p,
            tags = tags,
            onDays = { vm.setPlannerDays(it) },
            onSomeday = { vm.setPlannerSomeday(it) },
            onToggle = { vm.togglePlanned(it) },
            onAccept = { vm.acceptPlan(it) },
            onDismiss = { vm.closePlanner() },
        )
    }
    if (creatingPlan || editingGoal != null) {
        PlanEditor(
            goal = editingGoal,
            today = today,
            tagActions = tagActions,
            onCreate = { vm.createPlan(it); creatingPlan = false },
            prefill = if (editingGoal == null) planPrefill else null,
            prefillTitle = if (editingGoal == null) planTitle else null,
            onSave = { vm.updateGoal(it); editingGoal = null },
            onDelete = { vm.deleteGoal(it); editingGoal = null },
            onDismiss = { creatingPlan = false; editingGoal = null },
            onFinish = { vm.finishGoal(it); editingGoal = null },
        )
    }
    editingRoutine?.let { r ->
        RoutineEditor(
            routine = r,
            tagActions = tagActions,
            checkClashes = { repeat, time, minutes, id -> vm.clashesFor(repeat, time, minutes, id) },
            onSave = {
                if (it.id == 0L) vm.createRoutine(it) else vm.updateRoutine(it)
                editingRoutine = null
            },
            onDelete = { vm.deleteRoutine(it); editingRoutine = null },
            onDismiss = { editingRoutine = null },
        )
    }
}

/** Tag key for "#raw" typed before opening a form: an existing tag, a new one, or Other. */
private fun tagKeyFor(raw: String?, tags: List<Tag>, vm: MainViewModel): String =
    raw?.let { Tags.resolve(it, tags)?.key ?: vm.addTag(it.replaceFirstChar(Char::uppercase), null)?.key } ?: Tags.OTHER

/** A new, unsaved to-do prefilled from quick-add text (empty text = blank form for today). */
private fun draftTask(text: String, today: LocalDate, tags: List<Tag>, vm: MainViewModel): Task {
    val d = QuickAdd.parse(text, today) as? TaskDraft
    return Task(
        title = d?.title ?: "",
        date = if (d == null) today else d.date,
        time = d?.time,
        minutes = d?.minutes,
        category = tagKeyFor(d?.tag, tags, vm),
        priority = d?.priority?.let { Priority.store(it) },
        deadline = d?.deadline,
    )
}
