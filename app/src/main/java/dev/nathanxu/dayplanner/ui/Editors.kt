package dev.nathanxu.dayplanner.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.domain.Category
import dev.nathanxu.dayplanner.domain.Days
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

// ---------- shared fields ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(selected: Category, onSelect: (Category) -> Unit) {
    FieldLabel("Category")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Category.entries.forEach { c ->
            FilterChip(selected = c == selected, onClick = { onSelect(c) }, label = { Text("${c.emoji} ${c.label}") })
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DateField(date: LocalDate?, onChange: (LocalDate?) -> Unit, clearLabel: String? = "Someday") {
    val ctx = LocalContext.current
    FieldLabel("Date")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val init = date ?: LocalDate.now()
            DatePickerDialog(ctx, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) }, init.year, init.monthValue - 1, init.dayOfMonth).show()
        }) { Text(date?.format(dateFmt) ?: (clearLabel ?: "Pick date")) }
        if (clearLabel != null && date != null) TextButton(onClick = { onChange(null) }) { Text(clearLabel) }
    }
}

@Composable
private fun TimeField(time: LocalTime?, onChange: (LocalTime?) -> Unit) {
    val ctx = LocalContext.current
    FieldLabel("Time")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val init = time ?: LocalTime.of(9, 0)
            TimePickerDialog(ctx, { _, h, m -> onChange(LocalTime.of(h, m)) }, init.hour, init.minute, true).show()
        }) { Text(time?.format(timeFmt) ?: "No time") }
        if (time != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
}

@Composable
private fun NumberField(label: String, value: Int?, onChange: (Int?) -> Unit, suffix: String = "") {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(4)
            onChange(text.toIntOrNull())
        },
        label = { Text(label) },
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text("Title") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorDialog(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---------- editors ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(
    task: Task,
    goals: List<Goal>,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var date by remember(task.id) { mutableStateOf(task.date) }
    var time by remember(task.id) { mutableStateOf(task.time) }
    var minutes by remember(task.id) { mutableStateOf(task.minutes) }
    var category by remember(task.id) { mutableStateOf(task.category) }
    var goalId by remember(task.id) { mutableStateOf(task.goalId) }

    EditorDialog(
        title = "Edit task",
        canSave = title.isNotBlank(),
        onSave = {
            onSave(task.copy(title = title.trim(), date = date, time = time, minutes = minutes?.takeIf { it > 0 }, category = category, goalId = goalId))
        },
        onDismiss = onDismiss,
        onDelete = { onDelete(task) },
    ) {
        TitleField(title) { title = it }
        DateField(date, { date = it })
        TimeField(time) { time = it }
        NumberField("Duration", minutes, { minutes = it }, suffix = "min")
        CategoryPicker(category) { category = it }
        if (goals.isNotEmpty()) {
            FieldLabel("Counts towards")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("No goal") })
                goals.forEach { g ->
                    FilterChip(selected = goalId == g.id, onClick = { goalId = g.id }, label = { Text(g.title) })
                }
            }
        }
        if (task.genKey != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Created automatically by a routine or goal — edits apply to this one only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun GoalEditor(goal: Goal, onSave: (Goal) -> Unit, onDelete: (Goal) -> Unit, onDismiss: () -> Unit) {
    var title by remember(goal.id) { mutableStateOf(goal.title) }
    var target by remember(goal.id) { mutableIntStateOf(goal.target) }
    var category by remember(goal.id) { mutableStateOf(goal.category) }
    var recurring by remember(goal.id) { mutableStateOf(goal.recurring) }
    var split by remember(goal.id) { mutableStateOf(goal.split) }

    EditorDialog(
        title = "Edit goal",
        canSave = title.isNotBlank() && target >= 1,
        onSave = { onSave(goal.copy(title = title.trim(), target = target, category = category, recurring = recurring, split = split)) },
        onDismiss = onDismiss,
        onDelete = { onDelete(goal) },
    ) {
        TitleField(title) { title = it }
        NumberField("Target (1 = track by steps)", target, { target = it ?: 0 }, suffix = "× ${goal.period.label.lowercase()}")
        CategoryPicker(category) { category = it }
        SwitchRow("Repeat every ${if (goal.period.name == "WEEK") "week" else "month"}", recurring) { recurring = it }
        if (target > 1) SwitchRow("Split into daily to-dos", split) { split = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineEditor(routine: Routine, onSave: (Routine) -> Unit, onDelete: (Routine) -> Unit, onDismiss: () -> Unit) {
    var title by remember(routine.id) { mutableStateOf(routine.title) }
    var mask by remember(routine.id) { mutableIntStateOf(routine.daysMask) }
    var time by remember(routine.id) { mutableStateOf(routine.time) }
    var minutes by remember(routine.id) { mutableStateOf(routine.minutes) }
    var category by remember(routine.id) { mutableStateOf(routine.category) }
    var sync by remember(routine.id) { mutableStateOf(routine.syncToCalendar) }
    var active by remember(routine.id) { mutableStateOf(routine.active) }

    EditorDialog(
        title = "Edit routine",
        canSave = title.isNotBlank() && mask != 0,
        onSave = {
            onSave(
                routine.copy(
                    title = title.trim(), daysMask = mask, time = time, minutes = minutes?.takeIf { it > 0 },
                    category = category, syncToCalendar = sync, active = active,
                )
            )
        },
        onDismiss = onDismiss,
        onDelete = { onDelete(routine) },
    ) {
        TitleField(title) { title = it }
        FieldLabel("Days")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { d ->
                FilterChip(selected = Days.has(mask, d), onClick = { mask = Days.toggle(mask, d) }, label = { Text(d.name.take(1)) })
            }
        }
        TimeField(time) { time = it }
        NumberField("Duration", minutes, { minutes = it }, suffix = "min")
        CategoryPicker(category) { category = it }
        SwitchRow("Add to Google Calendar", sync) { sync = it }
        if (sync && time == null) {
            Text(
                "Needs a time to appear in the calendar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        SwitchRow("Active", active) { active = it }
    }
}
