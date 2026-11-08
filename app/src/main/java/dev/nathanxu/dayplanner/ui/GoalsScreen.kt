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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import dev.nathanxu.dayplanner.domain.Period
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
)

@Composable
fun GoalsScreen(
    today: LocalDate,
    goals: List<GoalProgress>,
    onQuickAdd: (String) -> Unit,
    actions: GoalActions,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            QuickAddBar(placeholder = "LeetCode mediums x5 @week !r", onSubmit = onQuickAdd)
            Text(
                "x5 goals split into daily to-dos automatically. Goals without a number track the steps you add.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
        for (period in Period.entries) {
            val inPeriod = goals.filter { it.goal.period == period }
            item(key = period.name) {
                SectionHeader(periodTitle(period, today) + overallPercent(inPeriod.map { it.status }))
            }
            if (inPeriod.isEmpty()) item(key = "${period.name}-empty") {
                Text(
                    if (period == Period.WEEK) "No weekly goals. Try “Run x3 @week #run !r”."
                    else "No monthly goals. Try “COMP0205 prototype @month”, then add steps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(inPeriod, key = { it.goal.id }) { gp ->
                GoalCard(gp, today, actions)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun overallPercent(statuses: List<GoalStatus>): String =
    if (statuses.isEmpty()) "" else " · ${statuses.map { it.fraction }.average().times(100).roundToInt()}%"

private fun periodTitle(period: Period, today: LocalDate): String = when (period) {
    Period.WEEK -> {
        val start = period.startOf(today)
        val f = DateTimeFormatter.ofPattern("d MMM")
        "This week (${f.format(start)}–${f.format(start.plusDays(6))})"
    }
    Period.MONTH -> "This month (${DateTimeFormatter.ofPattern("MMMM").format(today)})"
}

@Composable
private fun GoalCard(gp: GoalProgress, today: LocalDate, a: GoalActions) {
    val g = gp.goal
    val s = gp.status
    var showSteps by rememberSaveable(g.id) { mutableStateOf(s.mode == GoalMode.STEPS) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${g.category.emoji} ${g.title}",
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                    gp.steps.forEach { StepRow(it, today) { a.onToggleStep(it) } }
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
                        TextButton(onClick = { a.onSetSplit(g, !g.split) }) {
                            Text(if (g.split) "Split ✓" else "Split off")
                        }
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
                    Icon(Icons.Default.Delete, contentDescription = "Delete goal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val today = when {
        !g.split -> ""
        s.todayPlanned == 0 -> " · rest day"
        else -> " · today ${s.todayDone}/${s.todayPlanned}"
    }
    val d = s.paceDelta ?: 0
    return when {
        d < 0 -> "${-d} behind pace$today" to true
        d > 0 -> "$d ahead of pace$today" to false
        else -> "On pace$today" to false
    }
}

@Composable
private fun StepRow(task: Task, today: LocalDate, onToggle: () -> Unit) {
    val overdue = !task.done && task.date != null && task.date.isBefore(today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
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
