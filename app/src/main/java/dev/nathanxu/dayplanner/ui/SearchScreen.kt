package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Goal
import dev.nathanxu.dayplanner.data.Repository
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.Task
import dev.nathanxu.dayplanner.data.end
import dev.nathanxu.dayplanner.data.isOngoing
import dev.nathanxu.dayplanner.data.repeat
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dm = DateTimeFormatter.ofPattern("EEE d MMM")

/** One box that finds to-dos (title, place or tag), long-term tasks and routines. */
@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: Repository.SearchResults?,
    tags: List<Tag>,
    today: LocalDate,
    onTask: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onGoal: (Goal) -> Unit,
    onRoutine: (Routine) -> Unit,
    contentPadding: PaddingValues,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Title, place or tag") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        when {
            results == null -> item {
                Hint("Searches every to-do (open and done), goal and repeating to-do.")
            }
            results.isEmpty -> item { Hint("Nothing matches “${query.trim()}”.") }
            else -> {
                if (results.tasks.isNotEmpty()) {
                    item { SectionHeader("To-dos · ${results.tasks.size}") }
                    items(results.tasks, key = { "t${it.id}" }) { t -> TaskHit(t, Tags.find(t.category, tags), today, onTask, onToggle) }
                }
                if (results.goals.isNotEmpty()) {
                    item { SectionHeader("Goals · ${results.goals.size}") }
                    items(results.goals, key = { "g${it.id}" }) { g ->
                        Hit(
                            title = g.title, tag = Tags.find(g.category, tags),
                            meta = when {
                                g.archived -> "Finished " + g.end.format(dm)
                                g.isOngoing -> "Ongoing"
                                else -> "Until " + g.end.format(dm)
                            },
                        ) { onGoal(g) }
                    }
                }
                if (results.routines.isNotEmpty()) {
                    item { SectionHeader("Repeating · ${results.routines.size}") }
                    items(results.routines, key = { "r${it.id}" }) { r ->
                        Hit(
                            title = r.title, tag = Tags.find(r.category, tags),
                            meta = r.repeat.describe() + (r.time?.let { " · " + it.format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "") +
                                if (!r.active) " · paused" else "",
                        ) { onRoutine(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskHit(t: Task, tag: Tag, today: LocalDate, onTask: (Task) -> Unit, onToggle: (Task) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onTask(t) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle(t) })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityMark(t.priority.takeIf { !t.done })
                Text(
                    t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
            }
            val meta = listOfNotNull(
                when (t.date) {
                    null -> "someday"
                    today -> "today"
                    else -> t.date.format(dm)
                },
                t.place,
                tag.name.takeIf { tag.key != Tags.OTHER },
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hit(title: String, tag: Tag, meta: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(12.dp))
        TagDot(tag.color, 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}
