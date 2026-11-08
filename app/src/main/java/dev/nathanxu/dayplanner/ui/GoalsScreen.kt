package dev.nathanxu.dayplanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.domain.GoalMode
import dev.nathanxu.dayplanner.domain.GoalStatus
import dev.nathanxu.dayplanner.data.effectiveCadence
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.start
import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class GoalActions(
    val onLog: (Goal) -> Unit,
    val onSetRecurring: (Goal, Boolean) -> Unit,
    val onSetSplit: (Goal, Boolean) -> Unit,
    val onDelete: (Goal) -> Unit,
    val onAddStep: (Goal, String) -> Unit,
    val onToggleStep: (Task) -> Unit,
    val onEdit: (Goal) -> Unit,
    val onEditStep: (Task) -> Unit,
)

@Composable
fun GoalsScreen(
    today: LocalDate,
    goals: List<GoalProgress>,
    tags: List<Tag>,
    onQuickAdd: (String) -> Unit,
    onNew: () -> Unit,
    actions: GoalActions,
    contentPadding: PaddingValues,
) {
    val now = goals.filter { !it.goal.start.isAfter(today) }
    val later = goals.filter { it.goal.start.isAfter(today) }.sortedBy { it.goal.start }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New task") }
            Text(
                "Pick dates and how many times, and it splits into daily, 3-day, weekly or monthly to-dos. " +
                    "Or type it: “LeetCode x5 @week !r”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 6.dp),
            )
            QuickAddBar(placeholder = "LeetCode x5 @week !r", onSubmit = onQuickAdd)
        }
        item(key = "now") { SectionHeader("Now" + overallPercent(now.map { it.status })) }
        if (now.isEmpty()) item(key = "now-empty") {
            Text("Nothing on. Tap New task to set one up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun GoalCard(gp: GoalProgress, today: LocalDate, tag: Tag, a: GoalActions) {
    val g = gp.goal
    val s = gp.status
    var showSteps by rememberSaveable(g.id) { mutableStateOf(s.mode == GoalMode.STEPS) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.clickable { a.onEdit(g) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagDot(tag.color)
                        Spacer(Modifier.width(6.dp))
                        Text(g.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        rangeLine(g, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        detailLine(s),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${s.percent}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (s.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))

            paceLine(s, g)?.let { (text, behind) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (behind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            AnimatedVisibility(showSteps) {
                Column(Modifier.padding(top = 6.dp)) {
                    gp.steps.forEach { step -> StepRow(step, today, { a.onToggleStep(step) }, { a.onEditStep(step) }) }
                    QuickAddBar(
                        placeholder = "Add step, e.g. Draft intro tue",
                        onSubmit = { a.onAddStep(g, it) },
                        modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                when (s.mode) {
                    GoalMode.COUNT -> {
                        FilledTonalButton(onClick = { a.onLog(g) }) { Text("+1") }
                    }
                    GoalMode.SINGLE -> if (!s.complete) {
                        FilledTonalButton(onClick = { a.onLog(g) }) { Text("Done") }
                    }
                    GoalMode.STEPS -> Unit
                }
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(if (showSteps) "Hide" else if (gp.steps.isEmpty()) "Steps" else "Steps ${gp.steps.size}")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { a.onSetRecurring(g, !g.recurring) }) {
                    Text(if (g.recurring) "↻" else "1×", color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { a.onDelete(g) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
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
