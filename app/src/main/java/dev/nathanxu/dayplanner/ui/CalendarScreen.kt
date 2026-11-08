package dev.nathanxu.dayplanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.domain.CalendarLayout
import dev.nathanxu.dayplanner.domain.CalendarMode
import dev.nathanxu.dayplanner.domain.DaySegment
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dayHeader = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val dMMM = DateTimeFormatter.ofPattern("d MMM")
private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val HOUR = 48.dp
private val GUTTER = 40.dp

@Composable
fun CalendarScreen(
    view: CalendarView,
    today: LocalDate,
    events: Map<LocalDate, List<DaySegment>>,
    tasks: List<Task>,
    tags: List<Tag>,
    onMode: (CalendarMode) -> Unit,
    onStep: (Boolean) -> Unit,
    onToday: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Task) -> Unit,
    onEditTask: (Task) -> Unit,
    contentPadding: PaddingValues,
) {
    val ctx = LocalContext.current
    val tasksByDay = tasks.filter { it.date != null }.groupBy { it.date!! }
    val tagColor: (Task) -> Int = { Tags.find(it.category, tags).color }
    val openEvent: (DaySegment) -> Unit = { seg ->
        ctx.safeStart { startActivity(CalendarSource.viewEventIntent(seg.event.id, seg.event.start, seg.event.end)) }
    }

    Column(
        Modifier.fillMaxSize().padding(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CalendarMode.entries.forEach { m ->
                FilterChip(selected = view.mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(false) }) { Text("‹", fontSize = 24.sp) }
            Text(title(view), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            IconButton(onClick = { onStep(true) }) { Text("›", fontSize = 24.sp) }
            TextButton(onClick = onToday) { Text("Today") }
        }
        HorizontalDivider()

        when (view.mode) {
            CalendarMode.SCHEDULE -> ScheduleList(view, today, events, tasksByDay, tagColor, openEvent, onToggleTask, onEditTask, onLoadMore)
            CalendarMode.THREE_DAY, CalendarMode.WEEK ->
                TimeGrid(CalendarLayout.gridDays(view.mode, view.anchor), today, events, openEvent, onOpenDay)
            CalendarMode.MONTH -> MonthView(view, today, events, tasksByDay, tagColor, onSelectDay, onOpenDay, openEvent, onToggleTask, onEditTask)
        }
    }
}

private fun title(v: CalendarView): String = when (v.mode) {
    CalendarMode.SCHEDULE -> "From " + v.anchor.format(dMMM)
    CalendarMode.THREE_DAY, CalendarMode.WEEK -> CalendarLayout.gridDays(v.mode, v.anchor)
        .let { "${it.first().format(dMMM)} – ${it.last().format(dMMM)}" }
    CalendarMode.MONTH -> v.anchor.format(monthTitle)
}

// ---------- schedule (scrolling agenda) ----------

@Composable
private fun ScheduleList(
    view: CalendarView,
    today: LocalDate,
    events: Map<LocalDate, List<DaySegment>>,
    tasksByDay: Map<LocalDate, List<Task>>,
    tagColor: (Task) -> Int,
    onEvent: (DaySegment) -> Unit,
    onToggleTask: (Task) -> Unit,
    onEditTask: (Task) -> Unit,
    onLoadMore: () -> Unit,
) {
    val days = (0 until view.scheduleDays).map { view.anchor.plusDays(it.toLong()) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        for (d in days) {
            item(key = "h$d") {
                Text(
                    d.format(dayHeader) + if (d == today) " · Today" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (d == today) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
                        .background(
                            if (d == today) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = if (d == today) 8.dp else 0.dp, vertical = 4.dp),
                )
            }
            val segs = events[d].orEmpty()
            val dayTasks = tasksByDay[d].orEmpty()
            if (segs.isEmpty() && dayTasks.isEmpty()) {
                item(key = "e$d") {
                    Text("Nothing planned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            items(segs, key = { "s$d-${it.event.id}-${it.event.start}" }) { seg -> EventLine(seg, onEvent) }
            items(dayTasks, key = { "t${it.id}" }) { t -> TaskLine(t, tagColor(t), onToggleTask, onEditTask) }
        }
        item(key = "more") {
            OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Show 4 more weeks")
            }
        }
    }
}

@Composable
private fun EventLine(seg: DaySegment, onClick: (DaySegment) -> Unit) {
    val e = seg.event
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onClick(seg) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                seg.allDay -> "All day"
                else -> "${minutesToTime(seg.startMin)}\n${minutesToTime(seg.endMin)}"
            },
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.width(4.dp).height(34.dp).background(eventColor(e.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            e.location?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TaskLine(t: Task, color: Int, onToggle: (Task) -> Unit, onEdit: (Task) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onEdit(t) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle(t) }, modifier = Modifier.size(40.dp))
        TagDot(color, 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            (t.time?.let { it.format(hm) + "  " } ?: "") + t.title + if (t.units > 1) " ×${t.units}" else "",
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (t.done) TextDecoration.LineThrough else null,
            color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------- 3-day / week time grid ----------

@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    events: Map<LocalDate, List<DaySegment>>,
    onEvent: (DaySegment) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val nowColor = MaterialTheme.colorScheme.error
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    LaunchedEffect(days.first()) { scroll.scrollTo(with(density) { (HOUR * 7).roundToPx() }) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(GUTTER))
            days.forEach { d ->
                Column(
                    Modifier.weight(1f)
                        .background(if (d == today) todayTint else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onDay(d) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.size(28.dp).background(
                            if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${d.dayOfMonth}", fontWeight = FontWeight.SemiBold,
                            color = if (d == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (days.any { d -> events[d].orEmpty().any { it.allDay } }) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(GUTTER))
                days.forEach { d ->
                    val allDay = events[d].orEmpty().filter { it.allDay }
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        allDay.take(2).forEach { seg ->
                            Text(
                                seg.event.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                    .background(eventColor(seg.event.color), RoundedCornerShape(3.dp))
                                    .clickable { onEvent(seg) }.padding(horizontal = 3.dp),
                            )
                        }
                        if (allDay.size > 2) Text("+${allDay.size - 2}", fontSize = 10.sp)
                    }
                }
            }
        }
        HorizontalDivider()

        Row(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            Column(Modifier.width(GUTTER)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR)) {
                        if (h > 0) Text("%02d".format(h), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            days.forEach { d ->
                BoxWithConstraints(
                    Modifier.weight(1f).height(HOUR * 24)
                        .background(if (d == today) todayTint else Color.Transparent)
                        .drawBehind {
                        val hourPx = HOUR.toPx()
                        drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), 1f)
                        for (h in 1 until 24) {
                            drawLine(lineColor, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), 1f)
                        }
                    },
                ) {
                    val colW = maxWidth
                    CalendarLayout.lanes(events[d].orEmpty()).forEach { p ->
                        val laneW = colW / p.lanes
                        val seg = p.segment
                        Box(
                            Modifier
                                .offset(x = laneW * p.lane, y = HOUR * (seg.startMin / 60f))
                                .width(laneW)
                                .height(HOUR * ((seg.endMin - seg.startMin) / 60f))
                                .padding(1.dp)
                                .background(eventColor(seg.event.color), RoundedCornerShape(4.dp))
                                .clickable { onEvent(seg) }
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            Text(
                                seg.event.title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (d == today) {
                        val now = LocalTime.now()
                        Box(
                            Modifier.offset(y = HOUR * ((now.hour * 60 + now.minute) / 60f))
                                .fillMaxWidth().height(2.dp).background(nowColor),
                        )
                    }
                }
            }
        }
    }
}

// ---------- month ----------

@Composable
private fun MonthView(
    view: CalendarView,
    today: LocalDate,
    events: Map<LocalDate, List<DaySegment>>,
    tasksByDay: Map<LocalDate, List<Task>>,
    tagColor: (Task) -> Int,
    onSelect: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onEvent: (DaySegment) -> Unit,
    onToggleTask: (Task) -> Unit,
    onEditTask: (Task) -> Unit,
) {
    val month = YearMonth.from(view.anchor)
    val grid = CalendarLayout.monthGrid(month)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                week.forEach { d ->
                    val inMonth = YearMonth.from(d) == month
                    val segs = events[d].orEmpty()
                    val taskCount = tasksByDay[d].orEmpty().count { !it.done }
                    Column(
                        Modifier.weight(1f).height(64.dp).padding(1.dp)
                            .background(
                                if (d == view.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .border(
                                if (d == today) 2.dp else 0.dp,
                                if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { onSelect(d) }
                            .padding(2.dp),
                    ) {
                        Text(
                            "${d.dayOfMonth}" + if (taskCount > 0) " ·$taskCount" else "",
                            fontSize = 12.sp,
                            fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                d == today -> MaterialTheme.colorScheme.primary
                                inMonth -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                        segs.take(2).forEach { seg ->
                            Text(
                                seg.event.title, maxLines = 1, overflow = TextOverflow.Clip, fontSize = 9.sp,
                                color = Color.White, lineHeight = 11.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                                    .background(eventColor(seg.event.color), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 2.dp),
                            )
                        }
                        if (segs.size > 2) Text("+${segs.size - 2}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    view.selected.format(dayHeader), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { onOpenDay(view.selected) }) { Text("Day view") }
            }
            val segs = events[view.selected].orEmpty()
            val dayTasks = tasksByDay[view.selected].orEmpty()
            if (segs.isEmpty() && dayTasks.isEmpty()) {
                Text("Nothing planned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            segs.forEach { EventLine(it, onEvent) }
            dayTasks.forEach { TaskLine(it, tagColor(it), onToggleTask, onEditTask) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- helpers ----------

@Composable
private fun eventColor(argb: Int?): Color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

private fun minutesToTime(m: Int): String = if (m >= 24 * 60) "24:00" else "%02d:%02d".format(m / 60, m % 60)

private fun Context.safeStart(block: Context.() -> Unit) {
    try {
        block()
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}
