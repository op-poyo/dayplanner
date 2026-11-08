package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.calendar.CalInfo
import dev.nathanxu.dayplanner.data.SettingsState
import dev.nathanxu.dayplanner.data.SyncStatus
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    settings: SettingsState,
    calendars: List<CalInfo>,
    syncStatus: SyncStatus?,
    canWriteCalendar: Boolean,
    onRequestCalendar: () -> Unit,
    onChooseAccount: (String) -> Unit,
    onChooseCalendar: (Long) -> Unit,
    onSyncNow: () -> Unit,
    contentPadding: PaddingValues,
) {
    val accounts = calendars.filter { it.isGoogle }.map { it.accountName }.distinct()
    val account = settings.accountName
    val writable = calendars.filter { it.accountName == account && it.writable }
    val routineCal = writable.firstOrNull { it.id == settings.routineCalendarId }
        ?: writable.firstOrNull { it.primary } ?: writable.firstOrNull()

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        if (!canWriteCalendar) item {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Calendar access is off. It's needed to show your schedule and add routines to Google Calendar.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestCalendar) { Text("Allow calendar access") }
                }
            }
        }

        item {
            SectionHeader("Google account")
            Text(
                "Only this account's calendars are shown, and routines are added to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (accounts.isEmpty()) item {
            Text(
                if (canWriteCalendar) "No Google accounts found. Add one in Android Settings → Accounts, with Calendar sync on."
                else "Allow calendar access to see your accounts.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(accounts, key = { "acc-$it" }) { acc ->
            RadioRow(
                selected = acc == account,
                title = acc,
                subtitle = "${calendars.count { it.accountName == acc }} calendars",
                onClick = { onChooseAccount(acc) },
            )
        }

        if (account != null) {
            item {
                SectionHeader("Calendar for routines")
                Text(
                    "Changing this moves existing routine events to the new calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(writable, key = { "cal-${it.id}" }) { cal ->
                RadioRow(
                    selected = cal.id == routineCal?.id,
                    title = cal.name,
                    subtitle = if (cal.primary) "Main calendar" else null,
                    color = cal.color,
                    onClick = { onChooseCalendar(cal.id) },
                )
            }
        }

        item {
            SectionHeader("Routine sync")
            Text(
                syncStatus?.let {
                    (if (it.ok) "✓ " else "⚠ ") + it.message + " · " + it.at.format(DateTimeFormatter.ofPattern("HH:mm"))
                } ?: "Not synced yet",
                color = if (syncStatus?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSyncNow) { Text("Sync routines now") }
            Text(
                "Events reach Google Calendar on the web after Android syncs — usually within a minute. " +
                    "Check Android Settings → Accounts → Google → Calendar sync is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit, color: Int? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (color != null) {
            Box(Modifier.size(12.dp).background(Color(color), CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
