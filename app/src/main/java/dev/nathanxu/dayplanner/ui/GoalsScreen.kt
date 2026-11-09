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
import dev.nathanxu.dayplanner.data.effectiveCadence
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.start
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.GoalMode
import dev.nathanxu.dayplanner.domain.GoalStatus
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.TodoGroups
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class GoalActions(
    val onLog: (Goal) -> Unit,
    /** Takes back the latest unit of progress. */
    val onUnlog: (Goal) -> Unit,
    val onSetRecurring: (Goal, Boolean) -> Unit,
    val onSetSplit: (Goal, Boolean) -> Unit,
    val onDelete: (Goal) -> Unit,
    val onAddStep: (Goal, String) -> Unit,
    /** Opens the full step form, prefilled from the typed text. */
    val onExpandStep: (Goal, String) -> Unit,
    val onToggleStep: (Task) -> Unit,
    val onEdit: (Goal) -> Unit,
    val onEditStep: (Task) -> Unit,
    val onExtend: (Goal, LocalDate) -> Unit,
)

class TodoActions(
    val onToggle: (Task) -> Unit,
    val onEdit: (Task) -> Unit,
    val onDelete: (Task) -> Unit,
)

/** Tasks tab: long-term tasks, or every one-off to-do in one place. */
@Composable
fun GoalsScreen(
    today: LocalDate,
    segment: Int,
    onSegment: (Int) -> Unit,
    goals: List<GoalProgress>,
    finished: List<GoalProgress>,
    todos: List<Task>,
    tags: List<Tag>,
    onQuickAdd: (String) -> Unit,
    onExpandPlan: (String) -> Unit,
    onExpandTodo: (String) -> Unit,
    actions: GoalActions,
    todoActions: TodoActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp, // room for the FAB
        ),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = segment == 0, onClick = { onSegment(0) }, label = { Text("Long-term") })
                FilterChip(selected = segment == 1, onClick = { onSegment(1) }, label = { Text("All to-dos") })
            }
            Spacer(Modifier.height(8.dp))
        }
        if (segment == 0) longTerm(today, goals, finished, tags, onQuickAdd, onExpandPlan, actions)
        else allTodos(today, todos, tags, onQuickAdd, onExpandTodo, todoActions)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.longTerm(
    today: LocalDate,
    goals: List<GoalProgress>,
    finished: List<GoalProgress>,
    tags: List<Tag>,
    onQuickAdd: (String) -> Unit,
    onExpandPlan: (String) -> Unit,
    actions: GoalActions,
) {
    val now = goals.filter { !it.goal.start.isAfter(today) }
    val later = goals.filter { it.goal.start.isAfter(today) }.sortedBy { it.goal.start }
    item {
        QuickAddBar(placeholder = "LeetCode x5 @week !r", onSubmit = onQuickAdd, onExpand = onExpandPlan)
        Text(
            "Type it, or tap ✎ (or New task) for the full form with dates and breakdowns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
    item(key = "now") { SectionHeader("Now" + overallPercent(now.map { it.status })) }
    if (now.isEmpty()) item(key = "now-empty") {
        EmptyNote("Nothing on yet. A long-term task turns something big — “15 LeetCode problems in 3 weeks” — into a small to-do each day.")
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
        item(key = "finished") { SectionHeader("Finished") }
        items(finished, key = { "f${it.goal.id}" }) { gp ->
            FinishedRow(gp, Tags.find(gp.goal.category, tags), actions)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.allTodos(
    today: LocalDate,
    todos: List<Task>,
    tags: List<Tag>,
    onQuickAdd: (String) -> Unit,
    onExpandTodo: (String) -> Unit,
    a: TodoActions,
) {
    val open = todos.filter { !it.done }
    val done = todos.filter { it.done }
    item {
        QuickAddBar(placeholder = "Problem sheet fri #study 1h", onSubmit = onQuickAdd, onExpand = onExpandTodo)
        Text(
            "Swipe right to tick, left to delete.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
    if (open.isEmpty()) item { EmptyNote("All clear. Nothing waiting — enjoy it.") }
    TodoGroups.group(open, today) { it.date }.forEach { (label, list) ->
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
                Text(
                    t.title + if (t.units > 1) " ×${t.units}" else "",
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val meta = listOfNotNull(
                    t.time?.format(DateTimeFormatter.ofPattern("HH:mm")),
                    t.minutes?.let { formatMinutes(it.toLong()) },
                    tag.name,
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
        if (extending) ExtendChips(gp.goal) { g, d -> extending = false; a.onExtend(g, d) }
    }
}

@Composable
private fun GoalCard(gp: GoalProgress, today: LocalDate, tag: Tag, a: GoalActions) {
    val g = gp.goal
    val s = gp.status
    val haptic = rememberHaptic()
    var showSteps by rememberSaveable(g.id) { mutableStateOf(s.mode == GoalMode.STEPS) }
    var extending by rememberSaveable("ext${g.id}") { mutableStateOf(false) }

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
                        Text(
                            g.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(rangeLine(g, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(detailLine(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    paceLine(s, g)?.let { (text, behind) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (behind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // Progress ring: the one number that matters, at a glance.
                Box(Modifier.padding(end = 8.dp).size(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { s.fraction },
                        modifier = Modifier.size(60.dp),
                        strokeWidth = 6.dp,
                        color = androidx.compose.ui.graphics.Color(tag.color),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("${s.percent}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            AnimatedVisibility(showSteps) {
                Column(Modifier.padding(top = 6.dp)) {
                    gp.steps.forEach { step -> StepRow(step, today, { a.onToggleStep(step) }, { a.onEditStep(step) }) }
                    QuickAddBar(
                        placeholder = "Add step, e.g. Draft intro tue",
                        onSubmit = { a.onAddStep(g, it) },
                        onExpand = { a.onExpandStep(g, it) },
                        modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                    )
                }
            }
            AnimatedVisibility(extending) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        "Extend from ${g.end.format(DateTimeFormatter.ofPattern("EEE d MMM"))}:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ExtendChips(g) { goal, d -> extending = false; a.onExtend(goal, d) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when (s.mode) {
                    GoalMode.COUNT -> {
                        OutlinedButton(onClick = { haptic(); a.onUnlog(g) }, enabled = s.done > 0) { Text("−1") }
                        Spacer(Modifier.width(6.dp))
                        FilledTonalButton(onClick = { haptic(); a.onLog(g) }) { Text("+1") }
                    }
                    GoalMode.SINGLE -> if (!s.complete) {
                        FilledTonalButton(onClick = { haptic(); a.onLog(g) }) { Text("Done") }
                    } else {
                        OutlinedButton(onClick = { a.onUnlog(g) }) { Text("Undo") }
                    }
                    GoalMode.STEPS -> Unit
                }
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(if (showSteps) "Hide" else if (gp.steps.isEmpty()) "Steps" else "Steps ${gp.steps.size}")
                }
                TextButton(onClick = { extending = !extending }) { Text("Extend") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { a.onDelete(g) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun overallPercent(statuses: List<GoalStatus>): String =
    if (statuses.isEmpty()) "" else " · ${statuses.map { it.fraction }.average().times(100).roundToInt()}%"

private val dMMM = DateTimeFormatter.ofPattern("d MMM")

private fun rangeLine(g: Goal, today: LocalDate): String {
    val range = "${g.start.format(dMMM)} – ${g.end.format(dMMM)}"
    val left = ChronoUnit.DAYS.between(today, g.end) + 1
    val timing = when {
        g.start.isAfter(today) -> "starts ${g.start.format(DateTimeFormatter.ofPattern("EEE d MMM"))}"
        left == 1L -> "ends today"
        else -> "$left days left"
    }
    val cadence = g.effectiveCadence.takeIf { g.target > 1 && it != Cadence.NONE }?.let { " · ${it.label.lowercase()}" } ?: ""
    return "$range · $timing$cadence" + if (g.recurring) " · repeats" else ""
}


private fun detailLine(s: GoalStatus): String = when (s.mode) {
    GoalMode.COUNT -> "${s.done} of ${s.total} done"
    GoalMode.STEPS -> "${s.done} of ${s.total} steps done"
    GoalMode.SINGLE -> if (s.complete) "Done" else "Not done yet"
}

/** (text, isBehind) or null. */
private fun paceLine(s: GoalStatus, g: Goal): Pair<String, Boolean>? {
    if (s.mode != GoalMode.COUNT || s.complete) return null
    val cadence = g.effectiveCadence
    val today = when {
        cadence == Cadence.NONE -> ""
        s.todayPlanned == 0 -> if (cadence == Cadence.DAILY) " · rest day" else " · nothing due ${cadence.short}"
        cadence == Cadence.DAILY -> " · today ${s.todayDone}/${s.todayPlanned}"
        else -> " · ${s.todayPlanned - s.todayDone} left ${cadence.short}"
    }
    val d = s.paceDelta ?: 0
    return when {
        d < 0 -> "${-d} behind pace$today" to true
        d > 0 -> "$d ahead of pace$today" to false
        else -> "On pace$today" to false
    }
}

@Composable
private fun StepRow(task: Task, today: LocalDate, onToggle: () -> Unit, onEdit: () -> Unit) {
    val overdue = !task.done && task.date != null && task.date.isBefore(today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() }, modifier = Modifier.size(36.dp))
        Text(
            task.title,
            modifier = Modifier.weight(1f),
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                task.date == null -> "someday"
                task.date == today -> "today"
                else -> task.date.format(DateTimeFormatter.ofPattern("EEE d"))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}
