package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.domain.Durations
import dev.nathanxu.dayplanner.domain.Labels
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.WeekSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val dMMM = DateTimeFormatter.ofPattern("d MMM")

/** Sunday look-back: what got done, what slipped (with one-tap fixes), and what's coming. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewSheet(
    s: WeekSummary,
    goals: List<GoalProgress>,
    tags: List<Tag>,
    onWeek: (LocalDate) -> Unit,
    onMove: (Long, LocalDate?) -> Unit,
    onDone: (Long) -> Unit,
    onPlanNextWeek: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now()
    val thisWeek = s.weekStart == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onWeek(s.weekStart.minusWeeks(1)) }) { Text("‹ Earlier") }
            Text(
                "Week of ${s.weekStart.format(dMMM)}", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            TextButton(onClick = { onWeek(s.weekStart.plusWeeks(1)) }, enabled = !thisWeek) { Text("Later ›") }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            // ---------- headline + per-day bars ----------
            Text(
                when (s.done) {
                    0 -> "Nothing ticked off yet"
                    1 -> "1 to-do done"
                    else -> "${s.done} to-dos done"
                } + if (s.doneMinutes > 0) " · ${Durations.short(s.doneMinutes)}" else "",
                style = MaterialTheme.typography.headlineSmall,
            )
            s.busiestDay?.let {
                Text(
                    "Busiest: " + it.name.lowercase().replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DayBars(s.perDay, s.weekStart, today)

            if (s.byTag.isNotEmpty()) {
                SectionHeader("By tag")
                s.byTag.forEach { t ->
                    val tag = Tags.find(t.tag, tags)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagDot(tag.color, 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tag.name, Modifier.weight(1f))
                        Text(
                            "${t.done}" + if (t.minutes > 0) " · ${Durations.short(t.minutes)}" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (s.routines.isNotEmpty()) {
                SectionHeader("Routines")
                s.routines.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row {
                            Text(r.title, Modifier.weight(1f))
                            Text("${r.done}/${r.expected}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(
                            progress = { if (r.expected == 0) 0f else r.done.toFloat() / r.expected },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            val active = goals.filter { !it.goal.archived }
            if (active.isNotEmpty()) {
                SectionHeader("Long-term")
                active.take(6).forEach { gp ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(gp.goal.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${gp.status.percent}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---------- slipped ----------
            SectionHeader(if (s.slipped.isEmpty()) "Slipped" else "Slipped · ${s.slipped.size}")
            if (s.slipped.isEmpty()) {
                Text("Nothing slipped.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            s.slipped.forEach { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val why = t.deadline?.takeIf { it.isBefore(today) }?.let { Labels.deadline(it, today).first }
                        ?: t.date?.let { "was for " + it.format(DateTimeFormatter.ofPattern("EEE d MMM")) }
                    why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { onMove(t.id, today) }, label = { Text("Today") })
                        if (nextMonday != today) AssistChip(onClick = { onMove(t.id, nextMonday) }, label = { Text("Next Mon") })
                        AssistChip(onClick = { onMove(t.id, null) }, label = { Text("Someday") })
                        AssistChip(onClick = { onDone(t.id) }, label = { Text("Done") })
                    }
                }
            }

            // ---------- next week ----------
            SectionHeader("Next week")
            Text(
                when (s.nextWeekCount) {
                    0 -> "No to-dos dated next week yet."
                    1 -> "1 to-do dated next week."
                    else -> "${s.nextWeekCount} to-dos dated next week."
                },
            )
            s.nextWeekDeadlines.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.deadline?.let { Text(Labels.deadline(it, today).first, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPlanNextWeek, modifier = Modifier.fillMaxWidth()) { Text("Plan the next 7 days") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done reviewing") }
        }
    }
}

/** Seven small bars, Monday first; today's is highlighted. */
@Composable
private fun DayBars(perDay: List<Int>, weekStart: LocalDate, today: LocalDate) {
    val max = (perDay.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perDay.forEachIndexed { i, n ->
            val day = weekStart.plusDays(i.toLong())
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth()
                        .height((4 + 40 * n / max).dp)
                        .background(
                            if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(4.dp),
                        ),
                )
                Text(
                    day.dayOfWeek.name.take(1),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
