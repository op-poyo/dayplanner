package dev.nathanxu.dayplanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.TodayPlan
import dev.nathanxu.dayplanner.domain.TimelineItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun TodayScreen(
    today: LocalDate,
    tasks: List<Task>,
    upcoming: List<Task>,
    plan: TodayPlan?,
    loading: Boolean,
    quickAddFocus: FocusRequester,
    onQuickAdd: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onConnectCalendar: () -> Unit,
    contentPadding: PaddingValues,
) {
    val ctx = LocalContext.current
    var showSyntax by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            QuickAddBar(
                placeholder = "Problem sheet fri #study 1h",
                onSubmit = onQuickAdd,
                focusRequester = quickAddFocus,
            )
            TextButton(onClick = { showSyntax = !showSyntax }) {
                Text(if (showSyntax) "Hide syntax" else "Quick-add syntax")
            }
            if (showSyntax) SyntaxCard()
        }

        // ---------- schedule ----------
        item { SectionHeader("Schedule") }
        if (loading && plan == null) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        when {
            plan == null -> Unit
            !plan.calendarConnected -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("See your Google Calendar here, with travel time between places.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onConnectCalendar) { Text("Connect calendar") }
                    }
                }
            }
            plan.items.isEmpty() -> item {
                Text("Nothing in your calendar today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                items(plan.items) { item ->
                    when (item) {
                        is TimelineItem.Event -> EventRow(item) {
                            ctx.safeStart { startActivity(CalendarSource.openDayIntent(today)) }
                        }
                        is TimelineItem.Travel -> TravelRow(item, plan.travelEnabled)
                        is TimelineItem.Free -> FreeRow(item)
                    }
                }
            }
        }

        // ---------- tasks ----------
        val open = tasks.count { !it.done }
        item { SectionHeader(if (tasks.isEmpty()) "Tasks" else "Tasks · $open left") }
        if (tasks.isEmpty()) item {
            Text("No tasks. Add one above, or set up routines.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                today = today,
                onToggle = { onToggle(task) },
                onDelete = { onDelete(task) },
                onBlock = { ctx.blockTime(task, plan) },
            )
        }

        if (upcoming.isNotEmpty()) {
            item { SectionHeader("Upcoming") }
            items(upcoming.take(15), key = { "u${it.id}" }) { task ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        task.date?.format(dayFmt) ?: "",
                        modifier = Modifier.width(92.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${task.category.emoji} ${task.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun EventRow(item: TimelineItem.Event, onClick: () -> Unit) {
    val e = item.event
    val bar = e.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (e.allDay) "All day" else "${e.start.format(hm)}\n${e.end.format(hm)}",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.width(4.dp).height(40.dp).background(bar, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.location?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TravelRow(item: TimelineItem.Travel, travelEnabled: Boolean) {
    val color = if (item.tight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val m = item.minutes?.toLong()
    val text = when {
        m == null && !travelEnabled -> "🚆 Travel to ${item.to} — add MAPS_API_KEY for times"
        m == null -> "🚆 Travel to ${item.to} — time unavailable"
        item.tight -> "⚠ Need ${formatMinutes(m)} to get to ${item.to}, " +
            "only ${formatMinutes(item.availableMinutes)} gap"
        else -> "🚆 ${formatMinutes(m)} to ${item.to}"
    }
    Text(
        text, color = color, style = MaterialTheme.typography.bodySmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 70.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun FreeRow(item: TimelineItem.Free) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            item.start.format(hm), modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.weight(1f)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "Free until ${item.end.format(hm)} · ${formatMinutes(item.minutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun TaskRow(task: Task, today: LocalDate, onToggle: () -> Unit, onDelete: () -> Unit, onBlock: () -> Unit) {
    val overdue = !task.done && task.date != null && task.date.isBefore(today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add("${task.category.emoji} ${task.category.label}")
                task.minutes?.let { add(formatMinutes(it.toLong())) }
                when {
                    overdue -> add("overdue · ${task.date?.format(dayFmt)}")
                    task.date == null -> add("someday")
                }
                if (task.routineId != null) add("↻")
            }.joinToString(" · ")
            Text(
                meta, style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!task.done) {
            IconButton(onClick = onBlock) { Icon(Icons.Default.DateRange, contentDescription = "Block time in calendar") }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SyntaxCard() {
    val rows = listOf(
        "fri / tmr / someday" to "date (default today)",
        "#study #run #cook #shop #proj #quant" to "category",
        "45m / 1h30" to "duration",
        "^leetcode" to "count towards a goal",
        "@week / @month" to "make it a goal",
        "x5" to "goal target",
        "!r" to "goal repeats every period",
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (k, v) ->
                Row {
                    Text(k, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "e.g. “LeetCode mediums x5 @week !r”, then “Two-sum tmr 30m ^leetcode”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sends the task to the Calendar app, starting at the first free slot long enough for it. */
private fun Context.blockTime(task: Task, plan: TodayPlan?) {
    val minutes = task.minutes ?: 30
    val slot = plan?.items?.filterIsInstance<TimelineItem.Free>()?.firstOrNull { it.minutes >= minutes }
    val start = slot?.start ?: LocalDateTime.now().let {
        val rounded = it.withSecond(0).withNano(0)
        rounded.plusMinutes(((15 - rounded.minute % 15) % 15).toLong())
    }
    safeStart { startActivity(CalendarSource.insertIntent(task.title, start, minutes, "From DayPlanner")) }
}

private fun Context.safeStart(block: Context.() -> Unit) {
    try {
        block()
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}
