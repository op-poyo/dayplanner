package dev.nathanxu.dayplanner.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import dev.nathanxu.dayplanner.repository
import dev.nathanxu.dayplanner.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val TaskIdKey = ActionParameters.Key<Long>("task_id")
val QuickAddKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_QUICK_ADD)

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Home-screen widget, 4 wide: today's calendar on the left, today's to-dos (tickable) on the
 * right, with a header showing tasks left and plan percentages.
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.repository
        val today = LocalDate.now()
        repo.ensureFor(today)
        val events = runCatching { repo.eventsBetween(today, today.plusDays(1)) }.getOrDefault(emptyList())
            .sortedWith(compareByDescending<CalEvent> { it.allDay }.thenBy { it.start })

        provideContent {
            val tasks by repo.observeToday(today).collectAsState(initial = emptyList())
            val goals by repo.observeGoals(today).collectAsState(initial = emptyList())
            val tags by repo.tags.tags.collectAsState()
            GlanceTheme { Content(today, LocalDateTime.now(), events, tasks, goals, tags) }
        }
    }

    @Composable
    private fun Content(
        today: LocalDate,
        now: LocalDateTime,
        events: List<CalEvent>,
        tasks: List<Task>,
        goals: List<GoalProgress>,
        tags: List<Tag>,
    ) {
        val c = GlanceTheme.colors
        val remaining = tasks.count { !it.done }
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(c.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp),
        ) {
            // ---------- header ----------
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                    Text(
                        text = "Today · " + today.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        style = TextStyle(color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                    val goalText = goals.filter { !it.goal.periodStart.isAfter(today) }.take(3)
                        .joinToString(" · ") { "${it.goal.title.take(12)} ${it.status.percent}%" }
                    Text(
                        text = (if (remaining == 0) "All done ✓" else "$remaining to do") +
                            if (goalText.isNotEmpty()) " · $goalText" else "",
                        maxLines = 1,
                        style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp),
                    )
                }
                Text(
                    text = "＋",
                    modifier = GlanceModifier
                        .background(c.primaryContainer)
                        .cornerRadius(16.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable(actionStartActivity<MainActivity>(actionParametersOf(QuickAddKey to true))),
                    style = TextStyle(color = c.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(GlanceModifier.height(8.dp))

            // ---------- two panes ----------
            Row(modifier = GlanceModifier.fillMaxSize()) {
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    PaneLabel("Schedule")
                    if (events.isEmpty()) {
                        Text("Nothing scheduled", style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp))
                    } else {
                        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(events, itemId = { it.id * 31 + it.start.hashCode() }) { e -> EventRow(e, now) }
                        }
                    }
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    PaneLabel("To-do")
                    if (tasks.isEmpty()) {
                        Text("Nothing to do", style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp))
                    } else {
                        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(tasks, itemId = { it.id }) { task -> TaskRow(task, today, Tags.find(task.category, tags)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PaneLabel(text: String) {
        Text(
            text = text.uppercase(),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.padding(bottom = 4.dp),
        )
    }

    @Composable
    private fun EventRow(e: CalEvent, now: LocalDateTime) {
        val c = GlanceTheme.colors
        val past = !e.allDay && e.end.isBefore(now)
        val bar = e.color?.let { ColorProvider(Color(it)) } ?: c.primary
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = GlanceModifier.width(3.dp).height(30.dp).background(bar)) {}
            Spacer(GlanceModifier.width(6.dp))
            Column {
                Text(
                    text = if (e.allDay) "All day" else "${e.start.format(hm)}–${e.end.format(hm)}",
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp),
                )
                Text(
                    text = e.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (past) c.onSurfaceVariant else c.onSurface,
                        fontSize = 13.sp,
                        textDecoration = if (past) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                )
            }
        }
    }

    @Composable
    private fun TaskRow(task: Task, today: LocalDate, tag: Tag) {
        val c = GlanceTheme.colors
        val overdue = !task.done && task.date != null && task.date.isBefore(today)
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(TaskIdKey to task.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (task.done) "☑" else "☐",
                style = TextStyle(color = if (task.done) c.primary else c.onSurface, fontSize = 16.sp),
            )
            Spacer(GlanceModifier.width(5.dp))
            Box(modifier = GlanceModifier.size(7.dp).cornerRadius(4.dp).background(ColorProvider(Color(tag.color)))) {}
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = (if (overdue) "⚠ " else "") +
                    (task.time?.let { it.format(hm) + " " } ?: "") +
                    task.title + (if (task.units > 1) " ×${task.units}" else ""),
                maxLines = 1,
                style = TextStyle(
                    color = if (task.done) c.onSurfaceVariant else c.onSurface,
                    fontSize = 13.sp,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
        }
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        context.repository.toggle(id)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
