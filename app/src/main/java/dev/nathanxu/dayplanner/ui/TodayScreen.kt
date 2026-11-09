package dev.nathanxu.dayplanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.TodayPlan
import dev.nathanxu.dayplanner.domain.Labels
import dev.nathanxu.dayplanner.domain.TodoSort
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.domain.TimelineItem
import dev.nathanxu.dayplanner.domain.DayRow
import dev.nathanxu.dayplanner.domain.NextUp
import dev.nathanxu.dayplanner.domain.TodayView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Today, in the order you need it: one banner (slipped plans or the weekly review), Next up, one
 * timeline (events, travel and timed to-dos together), the to-dos with no time, a collapsed
 * Later line, and a thin strip of goals. Adding things is the box at the bottom of the screen.
 */
@Composable
fun TodayScreen(
    today: LocalDate,
    tasks: List<Task>,
    upcoming: List<Task>,
    plan: TodayPlan?,
    goals: List<GoalProgress>,
    tags: List<Tag>,
    loading: Boolean,
    /** Repeating to-dos, so their own calendar events don't show twice. */
    routines: List<Routine>,
    /** "3 tomorrow · 5 someday". */
    later: String,
    onOpenGoals: () -> Unit,
    onOpenTodos: () -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onEditTask: (Task) -> Unit,
    onConnectCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    slipped: List<Task> = emptyList(),
    onPlan: () -> Unit = {},
    reviewDue: Boolean = false,
    onReview: () -> Unit = {},
    sort: TodoSort = TodoSort.DATE,
    onSort: (TodoSort) -> Unit = {},
    /** Tick an upcoming to-do (it then leaves the list, so the caller offers Undo). */
    onTickUpcoming: (Task) -> Unit = onToggle,
    showSwipeTip: Boolean = false,
    onDismissSwipeTip: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var showLater by rememberSaveable { mutableStateOf(false) }
    val goalsById = goals.associateBy { it.goal.id }
    val tagOf = { t: Task -> Tags.find(t.category, tags) }

    // Timed to-dos for today go on the timeline; the rest (and overdue ones) are "Anytime".
    val timed = tasks.filter { it.date == today && it.time != null }
    val anytimeAll = tasks.filter { it !in timed }
    val anytime = if (sort == TodoSort.DATE) anytimeAll else {
        val tagName = { t: Task -> tagOf(t).takeIf { it.key != Tags.OTHER }?.name }
        TodoSort.sorted(anytimeAll.filter { !it.done }, sort) { sortFields(it, tagName(it)) } + anytimeAll.filter { it.done }
    }
    // Their calendar events would repeat the to-do rows: planned blocks, and a repeating to-do's event.
    val routineEvents = routines.associate { it.id to it.calendarEventId }
    val hidden = timed.flatMap { t ->
        t.plannedEvents.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() } + listOfNotNull(t.routineId?.let { routineEvents[it] })
    }.toSet()
    val rows = plan?.takeIf { it.calendarConnected }?.let { p ->
        TodayView.merge(p.items, timed, { it.time!! }, { it.minutes }, today, hidden)
    } ?: timed.sortedBy { it.time }.map { DayRow.Todo(it, it.time!!) }
    val next = TodayView.nextUp(
        plan?.items.orEmpty().filterIsInstance<TimelineItem.Event>().map { it.event }.filter { it.id !in hidden },
        timed.filter { !it.done }.map { Triple(it.title, today.atTime(it.time!!), it.minutes) },
        LocalDateTime.now(),
    )
    val openCount = tasks.count { !it.done }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        if (plan?.needsAccountChoice == true) item {
            Banner("You have more than one Google account. Pick the one this app should use.", "Choose", MaterialTheme.colorScheme.tertiaryContainer, onOpenSettings)
        }
        // One banner at most: slipped plans matter more than the review.
        when {
            slipped.isNotEmpty() -> item {
                Banner(
                    if (slipped.size == 1) "“${slipped.first().title}” slipped past its planned time."
                    else "${slipped.size} planned to-dos slipped past their time.",
                    "Re-plan", MaterialTheme.colorScheme.secondaryContainer, onPlan,
                )
            }
            reviewDue -> item { Banner("Weekly review: how the week went, and the next one.", "Open", MaterialTheme.colorScheme.primaryContainer, onReview) }
        }
        if (showSwipeTip && tasks.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tip: swipe a to-do right to tick it, left to delete.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismissSwipeTip) { Text("Got it") }
            }
        }

        // ---------- next up ----------
        next?.let { n -> item { NextUpCard(n) } }

        // ---------- timeline ----------
        item { SectionHeader("Today") }
        if (loading && plan == null) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (plan != null && !plan.calendarConnected) item {
            Banner("See your Google Calendar here, with travel time between places.", "Connect", MaterialTheme.colorScheme.surfaceVariant, onConnectCalendar)
        }
        if (rows.isEmpty() && plan?.calendarConnected == true) item {
            Text("Nothing at a set time today.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        itemsIndexed(rows, key = { i, r ->
            when (r) {
                is DayRow.Todo<*> -> "t${(r.todo as Task).id}"
                is DayRow.Cal -> when (val x = r.item) {
                    is TimelineItem.Event -> "e${x.event.id}-${x.event.start}"
                    is TimelineItem.Travel -> "v$i"
                    is TimelineItem.Free -> "f$i"
                }
            }
        }) { _, r ->
            when (r) {
                is DayRow.Todo<*> -> {
                    val t = r.todo as Task
                    TaskRow(
                        modifier = Modifier.animateItem(), task = t, today = today, tag = tagOf(t),
                        goal = t.goalId?.let { goalsById[it] },
                        onToggle = { onToggle(t) }, onEdit = { onEditTask(t) }, onDelete = { onDelete(t) },
                        onBlock = { ctx.blockTime(t, plan) },
                    )
                }
                is DayRow.Cal -> when (val item = r.item) {
                    is TimelineItem.Event -> EventRow(item) { ctx.safeStart { startActivity(CalendarSource.openDayIntent(today)) } }
                    is TimelineItem.Travel -> TravelRow(item, plan?.travelEnabled == true)
                    is TimelineItem.Free -> FreeRow(item)
                }
            }
        }

        // ---------- anytime ----------
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val left = anytime.count { !it.done }
                SectionHeader(if (anytime.isEmpty()) "Anytime" else "Anytime · $left left", Modifier.weight(1f))
                if (left > 1) SortMenu(sort, onSort)
                if (openCount > 0) TextButton(onClick = onPlan) { Text("Plan my day") }
            }
        }
        if (anytime.isEmpty()) item {
            EmptyNote(if (tasks.isEmpty()) "Nothing for today. Type one in the box below, or ✎ for the full form." else "Everything else has a time.")
        }
        items(anytime, key = { "a${it.id}" }) { task ->
            TaskRow(
                modifier = Modifier.animateItem(), task = task, today = today, tag = tagOf(task),
                goal = task.goalId?.let { goalsById[it] },
                onToggle = { onToggle(task) }, onEdit = { onEditTask(task) }, onDelete = { onDelete(task) },
                onBlock = { ctx.blockTime(task, plan) },
            )
        }

        // ---------- later (collapsed) ----------
        if (later.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { showLater = !showLater }.padding(top = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Later", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(later, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (showLater) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showLater) {
                items(upcoming.take(15), key = { "u${it.id}" }) { task ->
                    SwipeRow(done = false, onToggle = { onTickUpcoming(task) }, onDelete = { onDelete(task) }, modifier = Modifier.animateItem()) {
                        Row(Modifier.fillMaxWidth().clickable { onEditTask(task) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = false, onCheckedChange = { onTickUpcoming(task) })
                            Text(
                                task.date?.format(dayFmt) ?: "", modifier = Modifier.width(92.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TagDot(tagOf(task).color, 8.dp)
                            Spacer(Modifier.width(6.dp))
                            PriorityMark(task.priority)
                            Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    TextButton(onClick = onOpenTodos) { Text("All to-dos, including someday ›") }
                }
            }
        }

        // ---------- goals strip ----------
        if (goals.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                goals.take(8).forEach { gp ->
                    AssistChip(
                        onClick = onOpenGoals,
                        label = { Text("${gp.goal.title} ${gp.status.percent}%", maxLines = 1) },
                        leadingIcon = { TagDot(Tags.find(gp.goal.category, tags).color, 8.dp) },
                    )
                }
            }
        }
    }
}

/** The one banner Today shows: a message and an action. */
@Composable
private fun Banner(text: String, action: String, color: Color, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** What's on now, or next: "In 25 min · 14:00–15:00 · Roberts 106". */
@Composable
private fun NextUpCard(n: NextUp) {
    val now = LocalDateTime.now()
    val mins = java.time.Duration.between(now, n.start).toMinutes()
    val whenText = when {
        n.ongoing -> "Now" + (n.end?.let { " · until ${it.format(hm)}" } ?: "")
        mins < 60 -> "In ${mins.coerceAtLeast(1)} min · ${n.start.format(hm)}"
        else -> "At ${n.start.format(hm)}"
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(if (n.ongoing) "Now" else "Next up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(whenText, n.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** The quick-add syntax as a sheet (from the ? in the quick-add box). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SyntaxSheet(onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Text("Quick-add", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Text(
                "Type a title, then any of these in any order:",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SyntaxCard()
        }
    }
}

@Composable
private fun EventRow(item: TimelineItem.Event, onClick: () -> Unit) {
    val e = item.event
    // Things-style: once an event is over it fades, so what's still ahead stands out.
    val past = !e.allDay && e.end.isBefore(java.time.LocalDateTime.now())
    val bar = if (past) MaterialTheme.colorScheme.outlineVariant else e.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp)
            .alpha(if (past) 0.55f else 1f).clickable(onClick = onClick),
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
private fun TaskRow(
    modifier: Modifier = Modifier,
    task: Task,
    today: LocalDate,
    tag: Tag,
    goal: GoalProgress?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    val overdue = !task.done && task.date != null && task.date.isBefore(today)
    val due = task.deadline?.takeIf { !task.done }?.let { Labels.deadline(it, today) }
    val haptic = rememberHaptic()
    // Swipe right to tick, left to delete; tap to edit; the checkbox ticks too.
    SwipeRow(done = task.done, onToggle = onToggle, onDelete = onDelete, modifier = modifier) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { haptic(); onToggle() })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            PriorityMark(task.priority.takeIf { !task.done })
            Text(
                task.title + if (task.units > 1) " ×${task.units}" else "",
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            }
            val meta = buildList {
                task.time?.let { add(it.format(hm)) }
                due?.let { add(it.first) }
                if (tag.key != Tags.OTHER) add(tag.name)
                task.minutes?.let { add(formatMinutes(it.toLong())) }
                goal?.let { add("→ ${it.goal.title} ${it.status.percent}%") }
                when {
                    overdue -> add("overdue · ${task.date?.format(dayFmt)}")
                    task.date == null -> add("someday")
                }
                if (task.routineId != null) add("↻")
            }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tag.key != Tags.OTHER) {
                    TagDot(tag.color, 8.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                meta, style = MaterialTheme.typography.bodySmall,
                color = if (overdue || due?.second == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!task.done) {
            IconButton(onClick = onBlock) { Icon(Icons.Default.DateRange, contentDescription = "Block time in calendar") }
        }
    }
    }
}

/** Quick-add syntax reference; also shown in Settings. */
val SYNTAX_ROWS = listOf(
    "fri / tmr / someday" to "date (default today; a weekday means the next one)",
    "18:30 / 6pm" to "time",
    "45m / 1h30" to "duration",
    "#study #gym #piano" to "tag (a new name creates a new tag)",
    "^leetcode" to "counts towards a goal",
    "@week / @month" to "make it a goal for this week/month",
    "x5" to "how many times in total",
    "daily / weekdays / every mon,wed" to "repeats (a repeating to-do)",
    "every 2 weeks tue / every 3 days / monthly" to "other repeats",
    "!r" to "goal: start again when it ends",
    "!high / !low  (or !! / !1 / !3)" to "priority",
    "by fri / due 14/11" to "deadline (with no date, the planner picks a day before it)",
    "14/11" to "a date (day/month)",
)

@Composable
fun SyntaxCard() {
    val rows = SYNTAX_ROWS
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
    val fixed = task.time?.let { (task.date ?: LocalDate.now()).atTime(it) }
    val slot = plan?.items?.filterIsInstance<TimelineItem.Free>()?.firstOrNull { it.minutes >= minutes }
    val start = fixed ?: slot?.start ?: LocalDateTime.now().let {
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
