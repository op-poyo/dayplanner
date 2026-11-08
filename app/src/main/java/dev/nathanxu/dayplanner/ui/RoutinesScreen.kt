package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.Routine
import dev.nathanxu.dayplanner.domain.Days
import java.time.DayOfWeek

@Composable
fun RoutinesScreen(
    routines: List<Routine>,
    onAdd: (String, Int) -> Unit,
    onSetActive: (Routine, Boolean) -> Unit,
    onDelete: (Routine) -> Unit,
    contentPadding: PaddingValues,
) {
    var mask by rememberSaveable { mutableIntStateOf(Days.WEEKDAYS) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            Text(
                "Routines create a task on the chosen days automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = Days.has(mask, day),
                        onClick = { mask = Days.toggle(mask, day) },
                        label = { Text(day.name.take(1)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { mask = Days.WEEKDAYS }, label = { Text("Weekdays") })
                AssistChip(onClick = { mask = Days.ALL }, label = { Text("Every day") })
                AssistChip(onClick = { mask = 0b1100000 }, label = { Text("Weekends") })
            }
            QuickAddBar(
                placeholder = "Cook dinner #cook 45m",
                onSubmit = { onAdd(it, mask) },
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                Days.describe(mask),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
        item { SectionHeader("Your routines") }
        if (routines.isEmpty()) item {
            Text(
                "None yet. Ideas: “Lecture review #study 30m”, “Zetamac #quant 10m”, “Run #run” on Tue/Thu/Sat.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(routines, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.category.emoji} ${r.title}", fontWeight = FontWeight.Medium)
                    Text(
                        Days.describe(r.daysMask) + (r.minutes?.let { " · ${formatMinutes(it.toLong())}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = r.active, onCheckedChange = { onSetActive(r, it) })
                IconButton(onClick = { onDelete(r) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete routine", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
