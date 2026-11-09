package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.domain.Block
import dev.nathanxu.dayplanner.domain.Busy
import dev.nathanxu.dayplanner.domain.Durations
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayHead = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Review a plan before anything changes: where each to-do would go, what didn't fit and why.
 * Untick a to-do to leave it out. Accept writes it (and offers Undo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSheet(
    state: MainViewModel.PlannerState,
    tags: List<Tag>,
    onDays: (Int) -> Unit,
    onSomeday: (Boolean) -> Unit,
    onToggle: (Long) -> Unit,
    onAccept: (addToCalendar: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preview = state.preview
    val blocks = preview?.plan?.blocks.orEmpty()
    val chosen = blocks.filter { it.itemId !in state.excluded }.map { it.itemId }.toSet()
    var addToCalendar by remember { mutableStateOf(true) }
    val today = LocalDate.now()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Text(
                if (state.days == 1) "Plan my day" else "Plan ${state.days} days",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Button(onClick = { onAccept(addToCalendar && preview?.calendarName != null) }, enabled = !state.busy && chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "Accept" else "Accept ${chosen.size}")
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Today", 3 to "3 days", 7 to "Week").forEach { (d, label) ->
                    FilterChip(selected = state.days == d, onClick = { onDays(d) }, label = { Text(label) })
                }
            }
            ToggleLine("Include someday to-dos", state.includeSomeday, onSomeday)
            if (preview?.calendarName != null) {
                ToggleLine("Add blocks to ${preview.calendarName}", addToCalendar) { addToCalendar = it }
            }
            preview?.notes?.forEach { Hint(it) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))

            if (preview != null && !state.busy) {
                val plan = preview.plan
                if (blocks.isEmpty() && plan.unplaced.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Nothing to plan.", style = MaterialTheme.typography.titleSmall)
                    Hint(
                        "The planner places to-dos that have no time yet: ones dated in these days (or overdue), " +
                            "ones with a deadline, and high-priority ones. Give a to-do a duration so it knows how long to keep."
                    )
                }
                preview.days.forEach { day ->
                    val dayBlocks = blocks.filter { it.date == day }
                    val kept = plan.reserved.filter { it.date == day }
                    if (dayBlocks.isEmpty() && kept.isEmpty()) return@forEach
                    SectionHeader(if (day == today) "Today · ${day.format(dayHead)}" else day.format(dayHead))
                    val rows = (dayBlocks.map { it.startMin to it } + kept.map { it.startMin to it }).sortedBy { it.first }
                    rows.forEach { (_, r) ->
                        when (r) {
                            is Block -> BlockRow(r, Tags.find(r.tag ?: Tags.OTHER, tags), r.itemId !in state.excluded) { onToggle(r.itemId) }
                            is Busy -> KeptRow(r)
                            else -> Unit
                        }
                    }
                }
                if (plan.unplaced.isNotEmpty()) {
                    SectionHeader("Didn't fit")
                    plan.unplaced.forEach { u ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(u.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Hint("Try more days, shorter durations, or longer working hours in Settings → Planning.")
                }
            }
        }
    }
}

@Composable
private fun BlockRow(b: Block, tag: Tag, chosen: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = chosen, onCheckedChange = { onToggle() })
        Text(
            "%02d:%02d–%02d:%02d".format(b.startMin / 60, b.startMin % 60, b.endMin / 60, b.endMin % 60),
            Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium,
        )
        if (tag.key != Tags.OTHER) {
            TagDot(tag.color, 8.dp)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                b.title + if (b.parts > 1) " (${b.part}/${b.parts})" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val meta = listOfNotNull(
                Durations.short(b.minutes),
                if (b.travelBefore > 0) "${b.travelBefore} min travel before" else null,
                if (b.protected) "protected time" else null,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KeptRow(b: Busy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(48.dp))
        Text(
            "%02d:%02d–%02d:%02d".format(b.startMin / 60, b.startMin % 60, b.endMin / 60, b.endMin % 60),
            Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${b.label} · kept free", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
