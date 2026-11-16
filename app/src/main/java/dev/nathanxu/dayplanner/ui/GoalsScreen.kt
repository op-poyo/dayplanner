package dev.nathanxu.dayplanner.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.per
import dev.nathanxu.dayplanner.data.repeat
import dev.nathanxu.dayplanner.data.spec
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.isOngoing
import dev.nathanxu.dayplanner.data.start
import dev.nathanxu.dayplanner.domain.Labels
import dev.nathanxu.dayplanner.domain.TodoSort
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.GoalKind
import dev.nathanxu.dayplanner.domain.GoalMath
import dev.nathanxu.dayplanner.domain.GoalState
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.TodoGroups
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class GoalActions(
    /** A done session for today ("Log one"). */
    val onLog: (Goal) -> Unit,
    /** An open to-do for today, linked to the goal ("+ To-do"). */
    val onAddTodo: (Goal) -> Unit,
    val onDelete: (Goal) -> Unit,
    val onAddStep: (Goal, String) -> Unit,
    /** Opens the full step form, prefilled from the typed text. */
    val onExpandStep: (Goal, String) -> Unit,
    val onToggleStep: (Task) -> Unit,
    val onEdit: (Goal) -> Unit,
    val onEditStep: (Task) -> Unit,
    /** Give a step a day (null = none). */
    val onScheduleStep: (Task, LocalDate?) -> Unit,
    val onExtend: (Goal, LocalDate) -> Unit,
    val onClearFinished: () -> Unit = {},
)

class TodoActions(
    val onToggle: (Task) -> Unit,
    val onEdit: (Task) -> Unit,
    val onDelete: (Task) -> Unit,
)

/** Goals tab: goals now, coming up, and finished. Add them with the box at the bottom (or ✎). */
@Composable
fun GoalsScreen(
    today: LocalDate,
    goals: List<GoalProgress>,
    finished: List<GoalProgress>,
    tags: List<Tag>,
    onNewGoal: () -> Unit,
    onReview: () -> Unit,
    actions: GoalActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNewGoal) { Text("New goal") }
                TextButton(onClick = onReview) { Text("Weekly review") }
            }
        }
        longTerm(today, goals, finished, tags, actions)
    }
}

/** To-dos tab: every one-off to-do, or the repeating ones. */
@Composable
fun TodosScreen(
    today: LocalDate,
    segment: Int,
    onSegment: (Int) -> Unit,
    todos: List<Task>,
    tags: List<Tag>,
    todoActions: TodoActions,
    contentPadding: PaddingValues,
    sort: TodoSort,
    onSort: (TodoSort) -> Unit,
    /** The Repeating list (shown for segment 1), given the chips to put at its top. */
    repeating: @Composable (header: @Composable () -> Unit) -> Unit,
    showSwipeTip: Boolean = false,
    onDismissSwipeTip: () -> Unit = {},
) {
    val chips: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = segment == 0, onClick = { onSegment(0) }, label = { Text("All") })
            FilterChip(selected = segment == 1, onClick = { onSegment(1) }, label = { Text("Repeating") })
        }
    }
    if (segment == 1) {
        repeating(chips)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { chips() }
                SortMenu(sort, onSort)
            }
            if (showSwipeTip) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tip: swipe right to tick, left to delete.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismissSwipeTip) { Text("Got it") }
                }
            }
        }
        allTodos(today, todos, tags, todoActions, sort)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.longTerm(
    today: LocalDate,
    goals: List<GoalProgress>,
    finished: List<GoalProgress>,
    tags: List<Tag>,
    actions: GoalActions,
) {
    val now = goals.filter { !it.goal.start.isAfter(today) }
    val later = goals.filter { it.goal.start.isAfter(today) }.sortedBy { it.goal.start }
    item(key = "now") { SectionHeader("Now" + overallPercent(now.map { it.status })) }
    if (now.isEmpty()) item(key = "now-empty") {
        EmptyNote("No goals yet. A goal keeps score (“gym 3 a week”, “15 LeetCode by 30/11”, or a checklist of steps); your to-dos do the work. Type one below, e.g. “Gym x3 @week”, then link to-dos with ^gym.")
    }
    items(now, key = { it.goal.id }) { gp ->
        GoalCard(gp, today, Tags.find(gp.goal.category, tags), actions)
        Spacer(Modifier.height(10.dp))
    }
    if (later.isNotEmpty()) {
        item(key = "later") { SectionHeader("Coming up") }
        items(later, key = { it.goal.id }) { gp ->
            GoalCard(gp, today, Tags.find(gp.goal.category, tags), actions)
            Spacer(Modifier.height(10.dp))
        }
    }
    if (finished.isNotEmpty()) {
        item(key = "finished") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Finished · ${finished.size}", Modifier.weight(1f))
                TextButton(onClick = actions.onClearFinished) { Text("Clear all") }
            }
        }
        items(finished, key = { "f${it.goal.id}" }) { gp ->
            FinishedRow(gp, Tags.find(gp.goal.category, tags), actions)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.allTodos(
    today: LocalDate,
    todos: List<Task>,
    tags: List<Tag>,
    a: TodoActions,
    sort: TodoSort,
) {
    val open = todos.filter { !it.done }
    val tagName = { t: Task -> Tags.find(t.category, tags).takeIf { it.key != Tags.OTHER }?.name }
    val done = todos.filter { it.done }
    if (open.isEmpty()) item { EmptyNote("All clear. Nothing waiting — enjoy it.") }
    TodoSort.sections(open, sort, today) { sortFields(it, tagName(it)) }.forEach { (label, list) ->
        item(key = "g-$label") { SectionHeader("$label · ${list.size}") }
        items(list, key = { "t${it.id}" }) { t -> TodoLine(t, Tags.find(t.category, tags), a, Modifier.animateItem()) }
    }
    if (done.isNotEmpty()) {
        item(key = "done-h") { SectionHeader("Done this week · ${done.size}") }
        items(done, key = { "d${it.id}" }) { t -> TodoLine(t, Tags.find(t.category, tags), a, Modifier.animateItem()) }
    }
}

@Composable
private fun TodoLine(t: Task, tag: Tag, a: TodoActions, modifier: Modifier) {
    val haptic = rememberHaptic()
    SwipeRow(done = t.done, onToggle = { a.onToggle(t) }, onDelete = { a.onDelete(t) }, modifier = modifier) {
        Row(Modifier.fillMaxWidth().clickable { a.onEdit(t) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = t.done, onCheckedChange = { haptic(); a.onToggle(t) })
            TagDot(tag.color, 8.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityMark(t.priority.takeIf { !t.done })
                    Text(
                        t.title + if (t.units > 1) " ×${t.units}" else "",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                        color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                val due = t.deadline?.takeIf { !t.done }?.let { Labels.deadline(it, LocalDate.now()) }
                val meta = listOfNotNull(
                    t.time?.format(DateTimeFormatter.ofPattern("HH:mm")),
                    t.minutes?.let { formatMinutes(it.toLong()) },
                    due?.first,
                    tag.name,
                ).joinToString(" · ")
                Text(
                    meta, style = MaterialTheme.typography.bodySmall,
                    color = if (due?.second == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtendChips(g: Goal, onExtend: (Goal, LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val base = g.end
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { onExtend(g, base.plusWeeks(1)) }, label = { Text("+1 week") })
        AssistChip(onClick = { onExtend(g, base.plusWeeks(2)) }, label = { Text("+2 weeks") })
        AssistChip(onClick = { onExtend(g, base.plusMonths(1)) }, label = { Text("+1 month") })
        AssistChip(onClick = {
            DatePickerDialog(ctx, { _, y, m, d -> onExtend(g, LocalDate.of(y, m + 1, d)) }, base.year, base.monthValue - 1, base.dayOfMonth).show()
        }, label = { Text("Pick date") })
    }
}

@Composable
private fun FinishedRow(gp: GoalProgress, tag: Tag, a: GoalActions) {
    var extending by rememberSaveable(gp.goal.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagDot(tag.color, 8.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(gp.goal.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Ended ${gp.goal.end.format(DateTimeFormatter.ofPattern("d MMM"))} · ${gp.status.percent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { extending = !extending }) { Text(if (extending) "Close" else "Extend") }
            IconButton(onClick = { a.onDelete(gp.goal) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
        if (extending) ExtendChips(gp.goal) { g, d -> extending = false; a.onExtend(g, d) }
    }
}

@Composable
private fun GoalCard(gp: GoalProgress, today: LocalDate, tag: Tag, a: GoalActions) {
    val g = gp.goal
    val st = gp.status
    val checklist = st.kind == GoalKind.CHECKLIST
    val haptic = rememberHaptic()
    val openSteps = gp.steps.filter { !it.done }
    var showSteps by rememberSaveable(g.id) { mutableStateOf(checklist) }
    var extending by rememberSaveable("ext${g.id}") { mutableStateOf(false) }
    val warn = st.needsAttention

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(Modifier.clickable { a.onEdit(g) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagDot(tag.color)
                        Spacer(Modifier.width(8.dp))
                        Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(2.dp))
                    // What it is, then where it stands: the two lines that matter.
                    Text(GoalMath.describe(g.spec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        statusLine(st),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                        color = when {
                            st.complete -> MaterialTheme.colorScheme.primary
                            warn -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (gp.routines.isNotEmpty()) {
                        Text(
                            "Counts: " + gp.routines.joinToString(", ") { it.title + " (" + it.repeat.describe().lowercase() + ")" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(Modifier.padding(end = 8.dp).size(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { st.fraction },
                        modifier = Modifier.size(60.dp),
                        strokeWidth = 6.dp,
                        color = if (warn) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color(tag.color),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(if (st.target == 0) "–" else "${st.done}/${st.target}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            if (st.history.size > 1) HistoryBars(st.history, st.target, g.per)

            AnimatedVisibility(showSteps) {
                Column(Modifier.padding(top = 6.dp)) {
                    if (gp.steps.isEmpty()) {
                        Text(
                            if (checklist) "No steps yet. Add the first one below." else "No planned sessions. Use + To-do, or Plan my day.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // A count goal lists only what's still planned; a checklist shows all its steps.
                    (if (checklist) gp.steps else openSteps).forEach { step ->
                        StepRow(step, today, { a.onToggleStep(step) }, { a.onEditStep(step) }, { d -> a.onScheduleStep(step, d) })
                    }
                    if (checklist) {
                        QuickAddBar(
                            placeholder = "Add step, e.g. Draft intro 1h",
                            onSubmit = { a.onAddStep(g, it) },
                            onExpand = { a.onExpandStep(g, it) },
                            modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(extending) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text("Extend from ${g.end.format(DateTimeFormatter.ofPattern("EEE d MMM"))}:", style = MaterialTheme.typography.bodySmall)
                    ExtendChips(g) { goal, d -> extending = false; a.onExtend(goal, d) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!checklist) {
                    FilledTonalButton(onClick = { haptic(); a.onLog(g) }) { Text("Log one") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { a.onAddTodo(g) }) { Text("+ To-do") }
                }
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(
                        when {
                            showSteps -> "Hide"
                            checklist -> "Steps ${gp.steps.count { it.done }}/${gp.steps.size}"
                            openSteps.isEmpty() -> "Planned"
                            else -> "Planned ${openSteps.size}"
                        }
                    )
                }
                if (!g.isOngoing) TextButton(onClick = { extending = !extending }) { Text("Extend") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { a.onDelete(g) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete goal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "1 of 3 this week · 4 days left · 1 behind", "Done this week ✓", "2 of 5 steps · by 30 Nov". */
private fun statusLine(st: GoalState): String {
    if (st.window == null) return st.label.replaceFirstChar(Char::uppercase)
    if (st.kind == GoalKind.CHECKLIST) {
        if (st.target == 0) return "No steps yet"
        if (st.complete) return "All ${st.target} steps done ✓"
        return listOfNotNull(
            "${st.done} of ${st.target} steps",
            st.label.takeIf { it != "steps" },
            "${st.behindBy} behind".takeIf { st.behindBy > 0 },
        ).joinToString(" · ")
    }
    if (st.complete) return "Done ${st.label} ✓" + if (st.done > st.target) " (${st.done})" else ""
    val left = when (st.daysLeft) {
        0 -> null
        1 -> "last day"
        else -> "${st.daysLeft} days left"
    }
    return listOfNotNull(
        "${st.done} of ${st.target} ${st.label}",
        left.takeIf { st.label != "today" },
        if (st.behindBy > 0) "${st.behindBy} behind" else "on pace",
    ).joinToString(" · ")
}

/** Small bars for recent periods (oldest first; the last is now), so you see the trend. */
@Composable
private fun HistoryBars(history: List<Int>, target: Int, per: Cadence?) {
    val max = maxOf(target, history.maxOrNull() ?: 0, 1)
    val unit = when (per) {
        Cadence.WEEKLY -> "weeks"
        Cadence.MONTHLY -> "months"
        Cadence.DAILY -> "days"
        else -> "periods"
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text("Last $unit ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        history.forEachIndexed { i, n ->
            val now = i == history.lastIndex
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
                Box(
                    Modifier.width(14.dp).height((3 + 21 * n / max).dp).background(
                        when {
                            n >= target -> MaterialTheme.colorScheme.primary
                            now -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                    ),
                )
                Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val dMMM = DateTimeFormatter.ofPattern("d MMM")

private fun overallPercent(statuses: List<GoalState>): String =
    statuses.filter { it.window != null && it.target > 0 }.takeIf { it.isNotEmpty() }
        ?.let { l -> " · ${l.map { it.fraction }.average().times(100).roundToInt()}%" } ?: ""

/** A step (or planned session): tick, open, or give it a day. */
@Composable
private fun StepRow(task: Task, today: LocalDate, onToggle: () -> Unit, onEdit: () -> Unit, onSchedule: (LocalDate?) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val overdue = !task.done && task.date != null && task.date.isBefore(today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() }, modifier = Modifier.size(36.dp))
        Text(
            task.title, modifier = Modifier.weight(1f),
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box {
            TextButton(onClick = { menu = true }, enabled = !task.done) {
                Text(
                    when {
                        task.date == null -> "Pick day ▾"
                        task.date == today -> "Today ▾"
                        task.date == today.plusDays(1) -> "Tomorrow ▾"
                        else -> task.date.format(DateTimeFormatter.ofPattern("EEE d")) + " ▾"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Today") }, onClick = { menu = false; onSchedule(today) })
                DropdownMenuItem(text = { Text("Tomorrow") }, onClick = { menu = false; onSchedule(today.plusDays(1)) })
                DropdownMenuItem(text = { Text("Pick a day…") }, onClick = {
                    menu = false
                    val d = task.date ?: today
                    android.app.DatePickerDialog(ctx, { _, y, m, day -> onSchedule(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                })
                if (task.date != null) DropdownMenuItem(text = { Text("No day") }, onClick = { menu = false; onSchedule(null) })
            }
        }
    }
}
