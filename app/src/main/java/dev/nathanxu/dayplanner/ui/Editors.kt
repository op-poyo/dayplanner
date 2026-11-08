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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.nathanxu.dayplanner.data.PlanDraft
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.effectiveCadence
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.start
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Everything editors need to show and create tags. */
class TagActions(
    val tags: List<Tag>,
    /** Creates a tag (or returns the existing one with that name); null if the name isn't allowed. */
    val create: (name: String, color: Int?) -> Tag?,
)

// ---------- shared fields ----------

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(selected: String, tagActions: TagActions, onSelect: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    FieldLabel("Tag")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tagActions.tags.forEach { t ->
            FilterChip(
                selected = t.key == selected,
                onClick = { onSelect(t.key) },
                label = { Text(t.name) },
                leadingIcon = { TagDot(t.color) },
            )
        }
        FilterChip(selected = adding, onClick = { adding = !adding }, label = { Text("+ New tag") })
    }
    if (adding) {
        NewTagForm(tagActions.tags) { name, color ->
            tagActions.create(name, color)?.let {
                onSelect(it.key)
                adding = false
            }
        }
    }
}

/** Name + colour for a new tag. Used by the tag picker and the Settings tags tab. */
@Composable
fun NewTagForm(existing: List<Tag>, onAdd: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(Tags.nextColor(existing)) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(20) },
                label = { Text("New tag") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onAdd(name.trim(), color) }, enabled = Tags.isValidName(name)) { Text("Add") }
        }
        ColorPalette(selected = color) { color = it }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, clearLabel: String? = "Someday") {
    val ctx = LocalContext.current
    FieldLabel(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val init = date ?: LocalDate.now()
            DatePickerDialog(ctx, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) }, init.year, init.monthValue - 1, init.dayOfMonth).show()
        }) { Text(date?.format(dateFmt) ?: (clearLabel ?: "Pick date")) }
        if (clearLabel != null && date != null) TextButton(onClick = { onChange(null) }) { Text(clearLabel) }
    }
}

@Composable
private fun TimeField(label: String, time: LocalTime?, onChange: (LocalTime?) -> Unit) {
    val ctx = LocalContext.current
    FieldLabel(label)
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
    saveLabel: String = "Save",
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text(saveLabel) } },
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

// ---------- task ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(
    task: Task,
    goals: List<Goal>,
    tagActions: TagActions,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var date by remember(task.id) { mutableStateOf(task.date) }
    var time by remember(task.id) { mutableStateOf(task.time) }
    var minutes by remember(task.id) { mutableStateOf(task.minutes) }
    var tag by remember(task.id) { mutableStateOf(task.category) }
    var goalId by remember(task.id) { mutableStateOf(task.goalId) }

    EditorDialog(
        title = "Edit to-do",
        canSave = title.isNotBlank(),
        onSave = {
            onSave(task.copy(title = title.trim(), date = date, time = time, minutes = minutes?.takeIf { it > 0 }, category = tag, goalId = goalId))
        },
        onDismiss = onDismiss,
        onDelete = { onDelete(task) },
    ) {
        TitleField(title) { title = it }
        DateField("Date", date, { date = it })
        TimeField("Time", time) { time = it }
        NumberField("Duration", minutes, { minutes = it }, suffix = "min")
        TagPicker(tag, tagActions) { tag = it }
        if (goals.isNotEmpty()) {
            FieldLabel("Counts towards")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("Nothing") })
                goals.forEach { g ->
                    FilterChip(selected = goalId == g.id, onClick = { goalId = g.id }, label = { Text(g.title) })
                }
            }
        }
        if (task.genKey != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Created automatically by a routine or task — edits apply to this one only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- plan (long-term task) ----------

/**
 * Create ([goal] == null) or edit a long-term task. Start defaults to today. A single-day task can
 * have start/end times and becomes an ordinary to-do; longer ones can be broken down into daily,
 * 3-day, weekly or monthly to-dos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditor(
    goal: Goal?,
    today: LocalDate,
    tagActions: TagActions,
    onCreate: (PlanDraft) -> Unit,
    onSave: (Goal) -> Unit,
    onDelete: (Goal) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = goal?.id ?: -1L
    var title by remember(key) { mutableStateOf(goal?.title ?: "") }
    var tag by remember(key) { mutableStateOf(goal?.category ?: Tags.OTHER) }
    var start by remember(key) { mutableStateOf(goal?.start ?: today) }
    var end by remember(key) { mutableStateOf(goal?.end ?: today.plusDays(6)) }
    var startTime by remember(key) { mutableStateOf<LocalTime?>(null) }
    var endTime by remember(key) { mutableStateOf<LocalTime?>(null) }
    var target by remember(key) { mutableIntStateOf(goal?.target ?: 1) }
    var cadence by remember(key) { mutableStateOf(goal?.effectiveCadence ?: Cadence.DAILY) }
    var recurring by remember(key) { mutableStateOf(goal?.recurring ?: false) }

    val singleDay = start == end
    val valid = title.isNotBlank() && !end.isBefore(start) && target >= 1

    EditorDialog(
        title = if (goal == null) "New task" else "Edit task",
        canSave = valid,
        saveLabel = if (goal == null) "Create" else "Save",
        onSave = {
            if (goal == null) {
                onCreate(
                    PlanDraft(
                        title = title.trim(), tag = tag, start = start, end = end,
                        startTime = if (singleDay) startTime else null, endTime = if (singleDay) endTime else null,
                        target = target, cadence = if (target > 1) cadence else Cadence.NONE, recurring = recurring,
                    )
                )
            } else {
                val datesChanged = start != goal.start || end != goal.end
                onSave(
                    goal.copy(
                        title = title.trim(), category = tag, target = target, recurring = recurring,
                        periodStart = start,
                        period = if (datesChanged) Period.CUSTOM else goal.period,
                        endDate = if (datesChanged || goal.period == Period.CUSTOM) end else goal.endDate,
                        cadence = cadence, split = target > 1 && cadence != Cadence.NONE,
                    )
                )
            }
        },
        onDismiss = onDismiss,
        onDelete = goal?.let { g -> { onDelete(g) } },
    ) {
        TitleField(title) { title = it }
        TagPicker(tag, tagActions) { tag = it }

        FieldLabel("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { start = today; end = today }, label = { Text("Today") })
            AssistChip(onClick = {
                start = today; end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            }, label = { Text("This week") })
            AssistChip(onClick = { start = today; end = today.plusWeeks(2).minusDays(1) }, label = { Text("2 weeks") })
            AssistChip(onClick = {
                start = today; end = today.withDayOfMonth(today.lengthOfMonth())
            }, label = { Text("This month") })
        }
        DateField("Start", start, { d -> d?.let { start = it; if (end.isBefore(it)) end = it } }, clearLabel = null)
        DateField("End", end, { d -> d?.let { end = it } }, clearLabel = null)
        if (end.isBefore(start)) {
            Text("End is before start", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (singleDay && goal == null && target <= 1) {
            Row {
                Column(Modifier.weight(1f)) { TimeField("Start time", startTime) { startTime = it } }
                Column(Modifier.weight(1f)) { TimeField("End time", endTime) { endTime = it } }
            }
        }

        NumberField("How many times", target, { target = it ?: 0 }, suffix = "in total")
        Text(
            if (target <= 1) "1 = do it once (add steps on its card to track progress)."
            else "Split into to-dos so you stay on pace:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (target > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cadence.entries.forEach { c ->
                    FilterChip(selected = cadence == c, onClick = { cadence = c }, label = { Text(c.label) })
                }
            }
        }
        if (!singleDay) SwitchRow("Repeat when it ends", recurring) { recurring = it }
    }
}

// ---------- routine ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineEditor(
    routine: Routine,
    tagActions: TagActions,
    checkClashes: suspend (mask: Int, time: LocalTime, minutes: Int, routineId: Long?) -> List<String>,
    onSave: (Routine) -> Unit,
    onDelete: (Routine) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(routine.id) { mutableStateOf(routine.title) }
    var mask by remember(routine.id) { mutableIntStateOf(routine.daysMask) }
    var time by remember(routine.id) { mutableStateOf(routine.time) }
    var minutes by remember(routine.id) { mutableStateOf(routine.minutes) }
    var tag by remember(routine.id) { mutableStateOf(routine.category) }
    var sync by remember(routine.id) { mutableStateOf(routine.syncToCalendar) }
    var active by remember(routine.id) { mutableStateOf(routine.active) }
    var clashes by remember(routine.id) { mutableStateOf<List<String>>(emptyList()) }

    // Re-check whenever the slot changes (debounced so typing a duration doesn't spam queries).
    LaunchedEffect(mask, time, minutes) {
        val t = time
        clashes = if (t == null || mask == 0) emptyList() else {
            delay(300)
            checkClashes(mask, t, minutes ?: 30, routine.id)
        }
    }

    EditorDialog(
        title = "Edit routine",
        canSave = title.isNotBlank() && mask != 0,
        onSave = {
            onSave(
                routine.copy(
                    title = title.trim(), daysMask = mask, time = time, minutes = minutes?.takeIf { it > 0 },
                    category = tag, syncToCalendar = sync, active = active,
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
        TimeField("Time", time) { time = it }
        NumberField("Duration", minutes, { minutes = it }, suffix = "min")
        if (clashes.isNotEmpty()) {
            Text(
                "⚠ Clashes in the next 4 weeks:\n" + clashes.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        TagPicker(tag, tagActions) { tag = it }
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
