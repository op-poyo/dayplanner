package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.domain.Period
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun GoalsScreen(
    today: LocalDate,
    goals: List<GoalProgress>,
    onQuickAdd: (String) -> Unit,
    onLog: (Goal) -> Unit,
    onSetRecurring: (Goal, Boolean) -> Unit,
    onDelete: (Goal) -> Unit,
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
                "Add “^word” to any task to count it towards a goal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
        for (period in Period.entries) {
            val inPeriod = goals.filter { it.goal.period == period }
            item(key = period.name) { SectionHeader(periodTitle(period, today)) }
            if (inPeriod.isEmpty()) item(key = "${period.name}-empty") {
                Text(
                    if (period == Period.WEEK) "No weekly goals. Try “Run x3 @week !r”."
                    else "No monthly goals. Try “Finish COMP0205 prototype @month”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(inPeriod, key = { it.goal.id }) { gp ->
                GoalCard(gp, onLog, onSetRecurring, onDelete)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun periodTitle(period: Period, today: LocalDate): String = when (period) {
    Period.WEEK -> {
        val start = period.startOf(today)
        "This week · " + DateTimeFormatter.ofPattern("d MMM").format(start) + "–" +
            DateTimeFormatter.ofPattern("d MMM").format(start.plusDays(6))
    }
    Period.MONTH -> "This month · " + DateTimeFormatter.ofPattern("MMMM").format(today)
}

@Composable
private fun GoalCard(
    gp: GoalProgress,
    onLog: (Goal) -> Unit,
    onSetRecurring: (Goal, Boolean) -> Unit,
    onDelete: (Goal) -> Unit,
) {
    val g = gp.goal
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (gp.complete) "✓ " else "") + g.title,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${gp.done}/${g.target}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (gp.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { gp.fraction },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { onLog(g) }) { Text("+1") }
                TextButton(onClick = { onSetRecurring(g, !g.recurring) }) {
                    Text(if (g.recurring) "↻ Repeats" else "One-off")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(g) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete goal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
