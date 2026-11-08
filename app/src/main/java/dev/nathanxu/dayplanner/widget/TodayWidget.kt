package dev.nathanxu.dayplanner.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import dev.nathanxu.dayplanner.data.GoalProgress
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.domain.CalEvent
import dev.nathanxu.dayplanner.repository
import dev.nathanxu.dayplanner.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val TaskIdKey = ActionParameters.Key<Long>("task_id")
val QuickAddKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_QUICK_ADD)

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.repository
        val today = LocalDate.now()
        repo.ensureFor(today)
        val now = LocalDateTime.now()
        val next = repo.calendar.eventsOn(today)
            .filter { !it.allDay && it.end.isAfter(now) }
            .minByOrNull { it.start }

        provideContent {
            val tasks by repo.observeToday(today).collectAsState(initial = emptyList())
            val goals by repo.observeGoals(today).collectAsState(initial = emptyList())
            GlanceTheme { Content(today, tasks, next, goals) }
        }
    }

    @Composable
    private fun Content(today: LocalDate, tasks: List<Task>, next: CalEvent?, goals: List<GoalProgress>) {
        val c = GlanceTheme.colors
        val remaining = tasks.count { !it.done }
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(c.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                    Text(
                        text = "Today · " + today.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        style = TextStyle(color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = if (remaining == 0) "All done ✓" else "$remaining to do",
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

            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = next?.let {
                    "▸ ${it.start.format(DateTimeFormatter.ofPattern("HH:mm"))} ${it.title}" +
                        (it.location?.let { l -> " · $l" } ?: "")
                } ?: "No more events today",
                maxLines = 1,
                style = TextStyle(color = c.primary, fontSize = 12.sp),
            )
            if (goals.isNotEmpty()) {
                Text(
                    text = "◎ " + goals.take(3).joinToString(" · ") { "${it.goal.title.take(14)} ${it.status.percent}%" },
                    maxLines = 1,
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            Spacer(GlanceModifier.height(6.dp))

            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id }) { task -> TaskRow(task, today) }
            }
        }
    }

    @Composable
    private fun TaskRow(task: Task, today: LocalDate) {
        val c = GlanceTheme.colors
        val overdue = !task.done && task.date != null && task.date.isBefore(today)
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(TaskIdKey to task.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (task.done) "☑" else "☐",
                style = TextStyle(color = if (task.done) c.primary else c.onSurface, fontSize = 17.sp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = (if (overdue) "⚠ " else "") +
                    (task.time?.let { it.format(DateTimeFormatter.ofPattern("HH:mm")) + " " } ?: "") +
                    task.title + (task.minutes?.let { " · ${it}m" } ?: ""),
                maxLines = 1,
                style = TextStyle(
                    color = if (task.done) c.onSurfaceVariant else c.onSurface,
                    fontSize = 14.sp,
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
