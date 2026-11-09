package dev.nathanxu.dayplanner.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.nathanxu.dayplanner.data.SavedPlace
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.effectiveCadence
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.start
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.Pacing
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.Priority
import dev.nathanxu.dayplanner.domain.Repeat
import dev.nathanxu.dayplanner.data.repeat
import dev.nathanxu.dayplanner.data.withRepeat
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

/** "Today", "Tomorrow" or e.g. "Wed 11 Nov". */
private fun friendlyDate(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(if (d.year == today.year) DateTimeFormatter.ofPattern("EEE d MMM") else dateFmt)
    }
}

/**
 * Two chips: the date (default today; tap to pick another) and, if [noneLabel] is given, an
 * alternative such as "Someday" or "No end". Choosing the alternative keeps the date chip, so
 * one tap brings the date back.
 */
@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    noneLabel: String? = "Someday",
) {
    val ctx = LocalContext.current
    var last by remember { mutableStateOf(date ?: LocalDate.now()) }
    LaunchedEffect(date) { if (date != null) last = date }
    val pick = {
        DatePickerDialog(ctx, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) }, last.year, last.monthValue - 1, last.dayOfMonth).show()
    }
    FieldLabel(label)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = date != null,
            onClick = { if (date == null) onChange(last) else pick() },
            label = { Text(friendlyDate(last)) },
            trailingIcon = { Text("▾", style = MaterialTheme.typography.labelSmall) },
        )
        if (noneLabel != null) {
            FilterChip(selected = date == null, onClick = { onChange(null) }, label = { Text(noneLabel) })
        }
    }
}

/**
 * Two chips: a time (00:00 until you pick one; tap to choose) and "No time" for things that
 * happen on a day but not at a set hour.
 */
@Composable
private fun TimeField(label: String, time: LocalTime?, onChange: (LocalTime?) -> Unit, noneLabel: String? = "No time") {
    val ctx = LocalContext.current
    var last by remember { mutableStateOf(time ?: LocalTime.MIDNIGHT) }
    LaunchedEffect(time) { if (time != null) last = time }
    FieldLabel(label)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = time != null,
            onClick = {
                TimePickerDialog(ctx, { _, h, m -> onChange(LocalTime.of(h, m)) }, last.hour, last.minute, true).show()
            },
            label = { Text(last.format(timeFmt)) },
            trailingIcon = { Text("▾", style = MaterialTheme.typography.labelSmall) },
        )
        if (noneLabel != null) {
            FilterChip(selected = time == null, onClick = { onChange(null) }, label = { Text(noneLabel) })
        }
    }
}

/** Duration as hours + minutes (stored as minutes). Empty both = no duration. */
@Composable
private fun DurationField(minutes: Int?, onChange: (Int?) -> Unit, label: String = "Duration") {
    var h by remember { mutableStateOf(minutes?.let { (it / 60).takeIf { v -> v > 0 }?.toString() } ?: "") }
    var m by remember { mutableStateOf(minutes?.let { (it % 60).takeIf { v -> v > 0 }?.toString() } ?: "") }
    // Follow outside changes (e.g. picking an end time).
    LaunchedEffect(minutes) {
        val current = (h.toIntOrNull() ?: 0) * 60 + (m.toIntOrNull() ?: 0)
        if (current != (minutes ?: 0)) {
            h = minutes?.let { (it / 60).takeIf { v -> v > 0 }?.toString() } ?: ""
            m = minutes?.let { (it % 60).takeIf { v -> v > 0 }?.toString() } ?: ""
        }
    }
    fun emit() {
        val total = (h.toIntOrNull() ?: 0) * 60 + (m.toIntOrNull() ?: 0)
        onChange(total.takeIf { it > 0 })
    }
    FieldLabel(label)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = h, onValueChange = { h = it.filter(Char::isDigit).take(2); emit() },
            suffix = { Text("h") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = m, onValueChange = { m = it.filter(Char::isDigit).take(3); emit() },
            suffix = { Text("min") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int?, onChange: (Int?) -> Unit, suffix: String = "") {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    // Follow changes made elsewhere (e.g. picking an end time recalculates the duration).
    LaunchedEffect(value) {
        if ((text.toIntOrNull() ?: 0) != (value ?: 0)) text = value?.toString() ?: ""
    }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    // A bottom sheet rather than a dialog: more room, sits above the keyboard, and the list
    // stays visible behind it (the "task expands into a card" feel).
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Text(
                title, Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Button(onClick = onSave, enabled = canSave) { Text(saveLabel) }
        }
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            content()
            if (onDelete != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/**
 * Off, a lead before the to-do's time (only when it has one), or an exact clock time on its day.
 * [lead]: minutes before, null = off. [at]: exact time, wins over [lead].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderChips(
    lead: Int?,
    at: LocalTime?,
    taskTime: LocalTime?,
    onLead: (Int?) -> Unit,
    onAt: (LocalTime?) -> Unit,
) {
    val ctx = LocalContext.current
    FieldLabel("Remind me")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = at == null && lead == null, onClick = { onAt(null); onLead(null) }, label = { Text("Off") })
        if (taskTime != null) {
            listOf(0 to "At time", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 h").forEach { (v, label) ->
                FilterChip(selected = at == null && lead == v, onClick = { onAt(null); onLead(v) }, label = { Text(label) })
            }
        }
        FilterChip(
            selected = at != null,
            onClick = {
                val start = at ?: taskTime?.minusHours(1) ?: LocalTime.of(9, 0)
                TimePickerDialog(ctx, { _, h, m -> onAt(LocalTime.of(h, m)); onLead(null) }, start.hour, start.minute, true).show()
            },
            label = { Text(at?.let { "at " + it.format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "Pick time…") },
        )
    }
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
    places: List<SavedPlace> = emptyList(),
    /** Settings default for new to-dos (minutes before; null = off). */
    defaultReminder: Int? = null,
    /** New to-do → the repeating form, carrying what's been filled in. */
    onMakeRepeating: ((Task) -> Unit)? = null,
    /** A repeating to-do's instance → edit the repeating to-do itself. */
    onEditSeries: ((Long) -> Unit)? = null,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var date by remember(task.id) { mutableStateOf(task.date) }
    var time by remember(task.id) { mutableStateOf(task.time) }
    var minutes by remember(task.id) { mutableStateOf(task.minutes) }
    var tag by remember(task.id) { mutableStateOf(task.category) }
    var goalId by remember(task.id) { mutableStateOf(task.goalId) }
    // Off is null here (−1 when saved, so a default can't overwrite it later).
    var remindBefore by remember(task.id) {
        mutableStateOf((if (task.id == 0L) task.remindBefore ?: defaultReminder else task.remindBefore)?.takeIf { it >= 0 })
    }
    var remindAt by remember(task.id) { mutableStateOf(task.remindAt?.toLocalTime()) }
    var priority by remember(task.id) { mutableStateOf(Priority.of(task.priority)) }
    var deadline by remember(task.id) { mutableStateOf(task.deadline) }
    var place by remember(task.id) { mutableStateOf(task.place ?: "") }
    // Less-used fields stay folded away unless the to-do already uses them.
    var more by remember(task.id) {
        mutableStateOf(task.priority != null || task.deadline != null || !task.place.isNullOrBlank())
    }

    val isNew = task.id == 0L
    EditorDialog(
        title = when {
            !isNew -> "Edit to-do"
            task.goalId != null -> "New step"
            else -> "New to-do"
        },
        saveLabel = if (isNew) "Add" else "Save",
        canSave = title.isNotBlank(),
        onSave = {
            onSave(
                task.copy(
                    title = title.trim(), date = date, time = time, minutes = minutes?.takeIf { it > 0 },
                    category = tag, goalId = goalId,
                    remindBefore = if (time != null && remindAt == null) remindBefore ?: -1 else -1,
                    // Follows the to-do's date if you move it.
                    remindAt = remindAt?.let { t -> date?.atTime(t) },
                    priority = Priority.store(priority), deadline = deadline,
                    place = place.trim().ifEmpty { null },
                )
            )
        },
        onDismiss = onDismiss,
        onDelete = if (isNew) null else ({ onDelete(task) }),
    ) {
        TitleField(title) { title = it }
        DateField("Date", date, { d ->
            date = d
            if (d == null) time = null // someday: no time either
        })
        if (date != null) TimeField("Time", time, { time = it })
        if (date != null) ReminderChips(remindBefore, remindAt, time, { remindBefore = it }, { remindAt = it })
        DurationField(minutes, { minutes = it })
        TagPicker(tag, tagActions) { tag = it }
        if (!more) {
            TextButton(onClick = { more = true }, contentPadding = PaddingValues(0.dp)) {
                Text("Priority, deadline, place ▾")
            }
        } else {
            FieldLabel("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW).forEach { p ->
                    FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) })
                }
            }
            DateField("Deadline", deadline, { deadline = it }, noneLabel = "No deadline")
            if (deadline?.let { dl -> date?.isAfter(dl) } == true) {
                Text(
                    "The date is after the deadline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FieldLabel("Place")
            OutlinedTextField(
                value = place, onValueChange = { place = it }, singleLine = true,
                placeholder = { Text("Address or saved place") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (places.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { sp ->
                        FilterChip(
                            selected = place.equals(sp.name, ignoreCase = true),
                            onClick = { place = if (place.equals(sp.name, ignoreCase = true)) "" else sp.name },
                            label = { Text(sp.name) },
                        )
                    }
                }
            }
        }
        if (task.plannedEvents != null && !isNew) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Time set by Plan my day. Changing the date or time removes its calendar blocks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (goals.isNotEmpty()) {
            FieldLabel("Counts towards")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("Nothing") })
                goals.forEach { g ->
                    FilterChip(selected = goalId == g.id, onClick = { goalId = g.id }, label = { Text(g.title) })
                }
            }
        }
        if (isNew && task.goalId == null && onMakeRepeating != null) {
            TextButton(onClick = {
                onMakeRepeating(task.copy(title = title.trim(), time = time, minutes = minutes?.takeIf { it > 0 }, category = tag))
            }) { Text("Make it repeat…") }
        }
        val seriesId = task.routineId
        if (seriesId != null && onEditSeries != null) {
            TextButton(onClick = { onEditSeries(seriesId) }) { Text("Edit the repeating to-do (all days)") }
        }
        if (task.genKey != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Made by a repeating to-do or a goal. Edits change this one only.",
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
    /** Prefill for a new task, from text typed into the quick-add line. */
    prefill: dev.nathanxu.dayplanner.domain.GoalDraft? = null,
    prefillTitle: String? = null,
    onSave: (Goal) -> Unit,
    onDelete: (Goal) -> Unit,
    onDismiss: () -> Unit,
    /** Ends it today, keeping its progress (shown for active tasks being edited). */
    onFinish: ((Goal) -> Unit)? = null,
) {
    val key = goal?.id ?: -1L
    var title by remember(key) { mutableStateOf(goal?.title ?: prefill?.title ?: prefillTitle ?: "") }
    var tag by remember(key) { mutableStateOf(goal?.category ?: Tags.OTHER) }
    // "@week"/"@month" typed before expanding sets the range to this week/month.
    val prefillEnd = prefill?.period?.let { p -> p.lastDay(p.startOf(today)) }
    var start by remember(key) { mutableStateOf(goal?.start ?: today) }
    var noEnd by remember(key) { mutableStateOf(goal?.let { Pacing.isOngoing(it.end) } ?: false) }
    var end by remember(key) {
        mutableStateOf(goal?.end?.takeIf { !Pacing.isOngoing(it) } ?: prefillEnd ?: today.plusDays(6))
    }
    var startTime by remember(key) { mutableStateOf<LocalTime?>(null) }
    var endTime by remember(key) { mutableStateOf<LocalTime?>(null) }
    var target by remember(key) { mutableIntStateOf(goal?.target ?: prefill?.target ?: 1) }
    var cadence by remember(key) { mutableStateOf(goal?.effectiveCadence ?: Cadence.DAILY) }
    var recurring by remember(key) { mutableStateOf(goal?.recurring ?: prefill?.recurring ?: false) }
    var priority by remember(key) { mutableStateOf(Priority.of(goal?.priority)) }

    val singleDay = !noEnd && start == end
    val valid = title.isNotBlank() && (noEnd || !end.isBefore(start)) && target >= 1
    val effectiveEnd = if (noEnd) Pacing.ONGOING_END else end
    // Ongoing tasks always break down; "No breakdown" only makes sense with an end date.
    val effectiveCadence = when {
        noEnd && cadence == Cadence.NONE -> Cadence.DAILY
        !noEnd && target <= 1 -> Cadence.NONE
        else -> cadence
    }

    EditorDialog(
        title = if (goal == null) "New goal" else "Edit goal",
        canSave = valid,
        saveLabel = if (goal == null) "Create" else "Save",
        onSave = {
            if (goal == null) {
                onCreate(
                    PlanDraft(
                        title = title.trim(), tag = tag, start = start, end = effectiveEnd,
                        startTime = if (singleDay) startTime else null, endTime = if (singleDay) endTime else null,
                        target = target, cadence = effectiveCadence, recurring = recurring && !noEnd,
                        priority = Priority.store(priority),
                    )
                )
            } else {
                val datesChanged = start != goal.start || effectiveEnd != goal.end
                onSave(
                    goal.copy(
                        title = title.trim(), category = tag, target = target, recurring = recurring && !noEnd,
                        periodStart = start,
                        period = if (datesChanged) Period.CUSTOM else goal.period,
                        endDate = if (datesChanged || goal.period == Period.CUSTOM) effectiveEnd else goal.endDate,
                        cadence = effectiveCadence, split = effectiveCadence != Cadence.NONE,
                        priority = Priority.store(priority),
                    )
                )
            }
        },
        onDismiss = onDismiss,
        onDelete = goal?.let { g -> { onDelete(g) } },
    ) {
        TitleField(title) { title = it }
        TagPicker(tag, tagActions) { tag = it }
        FieldLabel("Priority")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW).forEach { p ->
                FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) })
            }
        }

        FieldLabel("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { start = today; end = today; noEnd = false }, label = { Text("Today") })
            AssistChip(onClick = {
                start = today; end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)); noEnd = false
            }, label = { Text("This week") })
            AssistChip(onClick = { start = today; end = today.plusWeeks(2).minusDays(1); noEnd = false }, label = { Text("2 weeks") })
            AssistChip(onClick = {
                start = today; end = today.withDayOfMonth(today.lengthOfMonth()); noEnd = false
            }, label = { Text("This month") })
            AssistChip(onClick = { noEnd = true }, label = { Text("Ongoing") })
        }
        DateField("Start", start, { d -> d?.let { start = it; if (end.isBefore(it)) end = it } }, noneLabel = null)
        DateField("End", if (noEnd) null else end, { d ->
            if (d == null) {
                noEnd = true
            } else {
                noEnd = false
                end = d
            }
        }, noneLabel = "No end")
        if (!noEnd && end.isBefore(start)) {
            Text("End is before start", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (singleDay && goal == null && target <= 1) {
            Row {
                Column(Modifier.weight(1f)) { TimeField("Start time", startTime, { startTime = it }) }
                Column(Modifier.weight(1f)) { TimeField("End time", endTime, { endTime = it }) }
            }
        }

        val per = when (effectiveCadence) {
            Cadence.DAILY -> "per day"
            Cadence.THREE_DAY -> "per 3 days"
            Cadence.WEEKLY -> "per week"
            Cadence.MONTHLY -> "per month"
            Cadence.NONE -> "per day"
        }
        NumberField("How many times", target, { target = it ?: 0 }, suffix = if (noEnd) per else "in total")
        Text(
            when {
                noEnd -> "Ongoing: a to-do appears every ${per.removePrefix("per ")} until you delete it."
                target <= 1 -> "1 = do it once (add steps on its card to track progress)."
                else -> "Split into to-dos so you stay on pace:"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (target > 1 || noEnd) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cadence.entries.filter { !noEnd || it != Cadence.NONE }.forEach { c ->
                    FilterChip(selected = effectiveCadence == c, onClick = { cadence = c }, label = { Text(c.label) })
                }
            }
        }
        if (!singleDay && !noEnd) SwitchRow("Repeat when it ends", recurring) { recurring = it }
        if (goal != null && onFinish != null && !goal.archived) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onFinish(goal) }) { Text("Finish now (moves it to Finished)") }
        }
    }
}

// ---------- routine ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineEditor(
    routine: Routine,
    tagActions: TagActions,
    checkClashes: suspend (repeat: Repeat, time: LocalTime, minutes: Int, routineId: Long?) -> List<String>,
    onSave: (Routine) -> Unit,
    onDelete: (Routine) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(routine.id) { mutableStateOf(routine.title) }
    var mask by remember(routine.id) { mutableIntStateOf(routine.daysMask) }
    var unit by remember(routine.id) { mutableStateOf(routine.repeat.unit) }
    var every by remember(routine.id) { mutableIntStateOf(routine.repeat.every) }
    var from by remember(routine.id) { mutableStateOf(routine.repeat.from ?: LocalDate.now()) }
    // Plain weekly needs no start date (it's how every routine worked before v1.9).
    val repeat = if (unit == Repeat.Unit.WEEK && every == 1) Repeat.weekly(mask) else Repeat(unit, every, mask, from)
    var time by remember(routine.id) { mutableStateOf(routine.time) }
    var minutes by remember(routine.id) { mutableStateOf(routine.minutes) }
    var tag by remember(routine.id) { mutableStateOf(routine.category) }
    var sync by remember(routine.id) { mutableStateOf(routine.syncToCalendar) }
    var active by remember(routine.id) { mutableStateOf(routine.active) }
    var remind by remember(routine.id) { mutableStateOf(routine.remind == true) }
    var clashes by remember(routine.id) { mutableStateOf<List<String>>(emptyList()) }
    val isNew = routine.id == 0L

    // Re-check whenever the slot changes (debounced so typing a duration doesn't spam queries).
    LaunchedEffect(repeat, time, minutes) {
        val t = time
        clashes = if (t == null || !repeat.isValid) emptyList() else {
            delay(300)
            checkClashes(repeat, t, minutes ?: 30, if (isNew) null else routine.id)
        }
    }

    EditorDialog(
        title = if (isNew) "New repeating to-do" else "Edit repeating to-do",
        canSave = title.isNotBlank() && repeat.isValid,
        saveLabel = if (isNew) "Create" else "Save",
        onSave = {
            onSave(
                routine.withRepeat(repeat).copy(
                    title = title.trim(), time = time, minutes = minutes?.takeIf { it > 0 },
                    category = tag, syncToCalendar = sync, active = active, remind = remind,
                )
            )
        },
        onDismiss = onDismiss,
        onDelete = if (isNew) null else ({ onDelete(routine) }),
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Repeats")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Every")
            TextButton(onClick = { every = (every - 1).coerceAtLeast(1) }, enabled = every > 1) { Text("−") }
            Text("$every", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { every = (every + 1).coerceAtMost(52) }) { Text("+") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Repeat.Unit.DAY to "day", Repeat.Unit.WEEK to "week", Repeat.Unit.MONTH to "month").forEach { (u, label) ->
                FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(if (every == 1) label else label + "s") })
            }
        }
        if (unit == Repeat.Unit.WEEK) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(selected = Days.has(mask, d), onClick = { mask = Days.toggle(mask, d) }, label = { Text(d.name.take(1)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { mask = Days.WEEKDAYS }, label = { Text("Weekdays") })
                AssistChip(onClick = { mask = Days.ALL }, label = { Text("Every day") })
                AssistChip(onClick = { mask = 0b1100000 }, label = { Text("Weekends") })
            }
        }
        if (unit != Repeat.Unit.WEEK || every > 1) {
            DateField(
                when (unit) {
                    Repeat.Unit.MONTH -> "First one (sets the day of the month)"
                    Repeat.Unit.WEEK -> "Starting the week of"
                    Repeat.Unit.DAY -> "First one"
                },
                from, { d -> d?.let { from = it } }, noneLabel = null,
            )
        }
        Text(
            if (!repeat.isValid) "Pick at least one day."
            else repeat.describe() + (repeat.first(LocalDate.now())?.let { " · next " + friendlyDate(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (repeat.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row {
            Column(Modifier.weight(1f)) {
                TimeField("Start time", time, { t ->
                    time = t
                    if (t != null && minutes == null) minutes = 30
                })
            }
            Column(Modifier.weight(1f)) {
                val end = time?.let { t -> minutes?.let { t.plusMinutes(it.toLong()) } }
                TimeField("End time", end, noneLabel = null, onChange = { e ->
                    val t = time
                    minutes = when {
                        e == null -> null
                        t == null -> {
                            // End picked first: assume an hour before it.
                            time = e.minusHours(1)
                            60
                        }
                        e.isAfter(t) -> java.time.Duration.between(t, e).toMinutes().toInt()
                        else -> minutes
                    }
                })
            }
        }
        DurationField(minutes, { minutes = it }, label = "or duration")
        if (clashes.isNotEmpty()) {
            Text(
                "⚠ Clashes in the next 4 weeks:\n" + clashes.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (time != null && repeat.isValid) {
            Text(
                "✓ No clashes in the next 4 weeks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        TagPicker(tag, tagActions) { tag = it }
        SwitchRow("Add to Google Calendar", sync) { sync = it }
        if (time != null) SwitchRow("Remind me at the start time", remind) { remind = it }
        if (sync && time == null) {
            Text(
                "Needs a start time to appear in the calendar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        if (!isNew) SwitchRow("Active", active) { active = it }
    }
}
