package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.data.repeat
import dev.nathanxu.dayplanner.data.SyncStatus
import dev.nathanxu.dayplanner.domain.Days
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun RoutinesScreen(
    routines: List<Routine>,
    tags: List<Tag>,
    canWriteCalendar: Boolean,
    onRequestCalendar: () -> Unit,
    onSetActive: (Routine, Boolean) -> Unit,
    onSetSync: (Routine, Boolean) -> Unit,
    onDelete: (Routine) -> Unit,
    onEdit: (Routine) -> Unit,
    onNew: () -> Unit,
    syncStatus: SyncStatus?,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    /** Shown first (the To-dos tab's All / Repeating chips). */
    header: @Composable () -> Unit = {},
) {
    val needsPermission = !canWriteCalendar && routines.any { it.active && it.syncToCalendar && it.time != null }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        if (needsPermission) item {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Allow calendar access so repeating to-dos appear in Google Calendar.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestCalendar) { Text("Allow") }
                }
            }
        }
        item {
            header()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    "Each makes a to-do on its days; with a time, also a repeating Google Calendar event. " +
                        "Or type one in the box below: “Gym 18:00 1h every mon,wed”.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onNew, modifier = Modifier.padding(top = 8.dp)) { Text("New repeating to-do") }
        }
        item { SectionHeader("Repeating · ${routines.size}") }
        if (syncStatus != null && routines.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (syncStatus.ok) "✓ " else "⚠ ") + syncStatus.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncStatus.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Text("Settings ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (routines.isEmpty()) item {
            Text(
                "None yet. Ideas: “Lecture review 19:00 30m weekdays #study”, “Zetamac 8:30 10m daily #quant”, “Run 7am every tue,thu,sat”.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(routines, key = { it.id }) { r -> RoutineRow(r, Tags.find(r.category, tags), onSetActive, onSetSync, onDelete, onEdit) }
    }
}

@Composable
private fun RoutineRow(
    r: Routine,
    tag: Tag,
    onSetActive: (Routine, Boolean) -> Unit,
    onSetSync: (Routine, Boolean) -> Unit,
    onDelete: (Routine) -> Unit,
    onEdit: (Routine) -> Unit,
) {
    val synced = r.calendarEventId != null
    Row(
        Modifier.fillMaxWidth().clickable { onEdit(r) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagDot(tag.color)
                Spacer(Modifier.width(6.dp))
                Text(r.title, fontWeight = FontWeight.Medium)
            }
            val meta = buildList {
                add(r.repeat.describe())
                r.time?.let { add(hm.format(it)) }
                r.minutes?.let { add(formatMinutes(it.toLong())) }
                add(
                    when {
                        synced -> "in calendar"
                        !r.syncToCalendar -> "app only"
                        r.time == null -> "no time, not synced"
                        !r.active -> "paused"
                        else -> "syncing…"
                    }
                )
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onSetSync(r, !r.syncToCalendar) }) {
            Icon(
                Icons.Default.DateRange,
                contentDescription = if (r.syncToCalendar) "Stop syncing to calendar" else "Sync to calendar",
                tint = if (r.syncToCalendar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = r.active, onCheckedChange = { onSetActive(r, it) })
        IconButton(onClick = { onDelete(r) }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete routine", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
