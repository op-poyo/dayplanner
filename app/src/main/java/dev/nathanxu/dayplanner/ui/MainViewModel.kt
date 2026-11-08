package dev.nathanxu.dayplanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.TodayPlan
import dev.nathanxu.dayplanner.repository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val messages = _messages.asSharedFlow()

    fun refresh() = viewModelScope.launch {
        val d = LocalDate.now()
        _today.value = d
        repo.ensureFor(d)
        repo.refreshWidget()
        _loading.value = true
        _plan.value = runCatching { repo.todayPlan() }.getOrElse {
            _messages.tryEmit("Couldn't load calendar: ${it.message}")
            null
        }
        _loading.value = false
    }

    fun quickAdd(text: String) = viewModelScope.launch {
        _messages.emit(repo.quickAdd(text, _today.value))
    }

    fun toggle(task: Task) = viewModelScope.launch { repo.setDone(task.id, !task.done) }

    fun delete(task: Task) = viewModelScope.launch {
        repo.deleteTask(task.id)
        _messages.emit("Deleted “${task.title}”")
    }

    fun logGoal(goal: Goal) = viewModelScope.launch { repo.logGoal(goal, _today.value) }
    fun setRecurring(goal: Goal, on: Boolean) = viewModelScope.launch { repo.setRecurring(goal, on) }
    fun deleteGoal(goal: Goal) = viewModelScope.launch { repo.deleteGoal(goal) }

    fun addRoutine(text: String, mask: Int) = viewModelScope.launch {
        _messages.emit(repo.addRoutine(text, mask, _today.value))
    }
    fun setRoutineActive(r: Routine, on: Boolean) = viewModelScope.launch { repo.setRoutineActive(r, on) }
    fun deleteRoutine(r: Routine) = viewModelScope.launch { repo.deleteRoutine(r) }
}
