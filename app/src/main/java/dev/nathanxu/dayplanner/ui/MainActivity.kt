package dev.nathanxu.dayplanner.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Incremented each time the widget's "+" opens the app, so the quick-add box takes focus. */
    private val quickAddRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            DayPlannerTheme { AppRoot(vm, quickAddRequests.intValue) }
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
    }

    companion object {
        const val EXTRA_QUICK_ADD = "quick_add"
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Home),
    CALENDAR("Calendar", Icons.Default.DateRange),
    GOALS("Tasks", Icons.Default.Star),
    ROUTINES("Routines", Icons.Default.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel, quickAddRequest: Int) {
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

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }
    var creatingPlan by remember { mutableStateOf(false) }
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
        showSettings = true
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
        )
    }
    val goalActions = remember(vm) {
        GoalActions(
            onLog = { vm.logGoal(it) },
            onSetRecurring = { g, on -> vm.setRecurring(g, on) },
            onSetSplit = { g, on -> vm.setSplit(g, on) },
            onDelete = { vm.deleteGoal(it) },
            onAddStep = { g, text -> vm.addStep(g, text) },
            onToggleStep = { vm.toggle(it) },
            onEdit = { editingGoal = it },
            onEditStep = { editingTask = it },
        )
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(quickAddRequest) {
        if (quickAddRequest > 0) {
            showSettings = false
            tab = Tab.TODAY
            // Give the Today list a frame to compose before focusing.
            kotlinx.coroutines.delay(150)
            runCatching { quickAddFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                title = {
                    Text(
                        when {
                            showSettings -> "Settings"
                            tab == Tab.TODAY -> today.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                            else -> tab.label
                        },
                    )
                },
                actions = {
                    if (!showSettings) {
                        if (tab == Tab.TODAY || tab == Tab.CALENDAR) {
                            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        }
                        IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
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
    ) { padding ->
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                calendars = calendars,
                syncStatus = syncStatus,
                canWriteCalendar = canWriteCalendar,
                tags = tags,
                actions = settingsActions,
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
                onOpenGoals = { tab = Tab.GOALS },
                quickAddFocus = quickAddFocus,
                onQuickAdd = { vm.quickAdd(it) },
                onToggle = { vm.toggle(it) },
                onDelete = { vm.delete(it) },
                onEditTask = { editingTask = it },
                onConnectCalendar = requestCalendar,
                onOpenSettings = openSettings,
                contentPadding = padding,
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
            Tab.GOALS -> GoalsScreen(
                today = today,
                goals = goals,
                tags = tags,
                onQuickAdd = { vm.quickAdd(it) },
                onNew = { creatingPlan = true },
                actions = goalActions,
                contentPadding = padding,
            )
            Tab.ROUTINES -> RoutinesScreen(
                routines = routines,
                tags = tags,
                canWriteCalendar = canWriteCalendar,
                onRequestCalendar = requestCalendar,
                onAdd = { text, mask, sync -> vm.addRoutine(text, mask, sync) },
                onSetSync = { r, on -> vm.setRoutineSync(r, on) },
                onSetActive = { r, on -> vm.setRoutineActive(r, on) },
                onDelete = { vm.deleteRoutine(it) },
                onEdit = { editingRoutine = it },
                syncStatus = syncStatus,
                onOpenSettings = openSettings,
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
            onSave = { vm.updateTask(it); editingTask = null },
            onDelete = { vm.delete(it); editingTask = null },
            onDismiss = { editingTask = null },
        )
    }
    if (creatingPlan || editingGoal != null) {
        PlanEditor(
            goal = editingGoal,
            today = today,
            tagActions = tagActions,
            onCreate = { vm.createPlan(it); creatingPlan = false },
            onSave = { vm.updateGoal(it); editingGoal = null },
            onDelete = { vm.deleteGoal(it); editingGoal = null },
            onDismiss = { creatingPlan = false; editingGoal = null },
        )
    }
    editingRoutine?.let { r ->
        RoutineEditor(
            routine = r,
            tagActions = tagActions,
            checkClashes = { mask, time, minutes, id -> vm.clashesFor(mask, time, minutes, id) },
            onSave = { vm.updateRoutine(it); editingRoutine = null },
            onDelete = { vm.deleteRoutine(it); editingRoutine = null },
            onDismiss = { editingRoutine = null },
        )
    }
}
