package dev.nathanxu.dayplanner.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.List
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
    GOALS("Goals", Icons.Default.Star),
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

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val snackbar = remember { SnackbarHostState() }
    val quickAddFocus = remember { FocusRequester() }

    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh()
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(quickAddRequest) {
        if (quickAddRequest > 0) {
            tab = Tab.TODAY
            // Give the Today list a frame to compose before focusing.
            kotlinx.coroutines.delay(150)
            runCatching { quickAddFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            Tab.TODAY -> today.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
                            else -> tab.label
                        },
                    )
                },
                actions = {
                    if (tab == Tab.TODAY) {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                },
            )
        },
        bottomBar = {
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
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            Tab.TODAY -> TodayScreen(
                today = today,
                tasks = tasks,
                upcoming = upcoming,
                plan = plan,
                loading = loading,
                quickAddFocus = quickAddFocus,
                onQuickAdd = { vm.quickAdd(it) },
                onToggle = { vm.toggle(it) },
                onDelete = { vm.delete(it) },
                onConnectCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                contentPadding = padding,
            )
            Tab.GOALS -> GoalsScreen(
                today = today,
                goals = goals,
                onQuickAdd = { vm.quickAdd(it) },
                onLog = { vm.logGoal(it) },
                onSetRecurring = { g, on -> vm.setRecurring(g, on) },
                onDelete = { vm.deleteGoal(it) },
                contentPadding = padding,
            )
            Tab.ROUTINES -> RoutinesScreen(
                routines = routines,
                onAdd = { text, mask -> vm.addRoutine(text, mask) },
                onSetActive = { r, on -> vm.setRoutineActive(r, on) },
                onDelete = { vm.deleteRoutine(it) },
                contentPadding = padding,
            )
        }
    }
}
