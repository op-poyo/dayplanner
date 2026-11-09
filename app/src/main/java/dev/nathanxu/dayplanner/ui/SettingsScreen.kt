package dev.nathanxu.dayplanner.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.calendar.CalInfo
import dev.nathanxu.dayplanner.data.SettingsState
import dev.nathanxu.dayplanner.data.SyncStatus
import dev.nathanxu.dayplanner.domain.Tag
import dev.nathanxu.dayplanner.domain.Tags
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SettingsActions(
    val requestCalendar: () -> Unit,
    val setAccountLinked: (String, Boolean) -> Unit,
    val setCalendarShown: (Long, Boolean) -> Unit,
    val enableSync: (CalInfo) -> Unit,
    val chooseRoutineCalendar: (Long) -> Unit,
    val syncNow: () -> Unit,
    val addTag: (String, Int) -> Unit,
    val updateTag: (Tag) -> Unit,
    val deleteTag: (String) -> Unit,
    val updateTravel: ((SettingsState) -> SettingsState) -> Unit,
    val requestNotifications: () -> Unit,
    val openExactAlarmSettings: () -> Unit,
    val testNotification: () -> Unit,
)

@Composable
fun SettingsScreen(
    settings: SettingsState,
    calendars: List<CalInfo>,
    syncStatus: SyncStatus?,
    canWriteCalendar: Boolean,
    tags: List<Tag>,
    travelEnabled: Boolean,
    alertState: AlertState,
    actions: SettingsActions,
    contentPadding: PaddingValues,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().padding(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        TabRow(selectedTabIndex = tab) {
            listOf("Calendars", "Travel", "Tags", "Syntax").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> CalendarsTab(settings, calendars, syncStatus, canWriteCalendar, actions)
            1 -> TravelTab(settings, travelEnabled, alertState, actions)
            2 -> TagsTab(tags, actions)
            else -> SyntaxTab()
        }
    }
}

// ---------- calendars ----------

@Composable
private fun CalendarsTab(
    settings: SettingsState,
    calendars: List<CalInfo>,
    syncStatus: SyncStatus?,
    canWriteCalendar: Boolean,
    a: SettingsActions,
) {
    val byAccount = calendars.groupBy { it.accountName }
    val accountNames = byAccount.keys.sortedWith(compareByDescending<String> { acc -> byAccount.getValue(acc).any { it.isGoogle } }.thenBy { it })
    val linked = settings.accounts ?: emptySet()
    val writable = calendars.filter { it.accountName in linked && it.writable }
    val routineCal = writable.firstOrNull { it.id == settings.routineCalendarId }
        ?: writable.firstOrNull { it.primary && it.isGoogle } ?: writable.firstOrNull()

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        if (!canWriteCalendar) item {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Calendar access is off. It's needed to show your schedule and add routines to Google Calendar.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = a.requestCalendar) { Text("Allow calendar access") }
                }
            }
        }

        item {
            SectionHeader("Accounts")
            Text(
                if (settings.accounts == null) "Not set up yet, so every calendar is shown. Switch on the accounts you want."
                else "Only switched-on accounts are shown. Any number, or none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (accountNames.isEmpty()) item {
            Text(
                if (canWriteCalendar) "No accounts found. Add one in Android Settings → Accounts, with Calendar sync on."
                else "Allow calendar access to see your accounts.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        accountNames.forEach { acc ->
            val cals = byAccount.getValue(acc)
            val on = acc in linked
            item(key = "acc-$acc") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(acc, fontWeight = FontWeight.Medium)
                        Text(
                            (if (cals.any { it.isGoogle }) "Google" else "On this phone") + " · ${cals.size} calendars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = { a.setAccountLinked(acc, it) })
                }
            }
            if (on) items(cals, key = { "cal-${it.id}" }) { cal ->
                CalendarRow(cal, shown = cal.id !in settings.hiddenCalendars, a)
            }
        }

        if (writable.isNotEmpty()) {
            item {
                SectionHeader("Calendar for routines")
                Text(
                    "Routines with a time become repeating events here. Changing it moves them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(writable, key = { "rc-${it.id}" }) { cal ->
                Row(
                    Modifier.fillMaxWidth().clickable { a.chooseRoutineCalendar(cal.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = cal.id == routineCal?.id, onClick = { a.chooseRoutineCalendar(cal.id) })
                    cal.color?.let { TagDot(it) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cal.name)
                        Text(cal.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
            OutlinedButton(onClick = a.syncNow) { Text("Sync routines now") }
            Text(
                "Missing a calendar you subscribed to by link (e.g. a uni timetable)? Google often adds those " +
                    "with phone sync off — it shows as “Not synced to this phone” above; tap Turn on sync. " +
                    "Google itself refreshes link calendars only every few hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun CalendarRow(cal: CalInfo, shown: Boolean, a: SettingsActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp).clickable { a.setCalendarShown(cal.id, !shown) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = shown, onCheckedChange = { a.setCalendarShown(cal.id, it) })
        cal.color?.let { TagDot(it) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(cal.name)
            if (!cal.syncEvents) {
                Text(
                    "Not synced to this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!cal.syncEvents) TextButton(onClick = { a.enableSync(cal) }) { Text("Turn on sync") }
    }
}

// ---------- travel & alerts ----------

private val modes = listOf("TRANSIT" to "Public transport", "WALK" to "Walk", "BICYCLE" to "Cycle", "DRIVE" to "Drive")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TravelTab(settings: SettingsState, travelEnabled: Boolean, alertState: AlertState, a: SettingsActions) {
    val ctx = LocalContext.current
    var home by remember(settings.home) { mutableStateOf(settings.home ?: "") }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        item {
            SectionHeader("Travel")
            if (!travelEnabled) {
                Text(
                    "Travel times are off: this build has no Maps API key, so journeys show without minutes " +
                        "and leave-now alerts can't be timed. See the README, “Travel times”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            OutlinedTextField(
                value = home, onValueChange = { home = it },
                label = { Text("Home address") },
                supportingText = { Text("Start of your first journey each day") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { a.updateTravel { it.copy(home = home.trim().ifEmpty { null }) } },
                enabled = home.trim() != (settings.home ?: ""),
            ) { Text("Save address") }

            Text("Travel by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (key, label) ->
                    FilterChip(
                        selected = settings.travelMode == key,
                        onClick = { a.updateTravel { it.copy(travelMode = key) } },
                        label = { Text(label) },
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buffer before leaving", Modifier.weight(1f))
                TextButton(onClick = { a.updateTravel { it.copy(bufferMinutes = (it.bufferMinutes - 5).coerceAtLeast(0)) } }) { Text("−") }
                Text("${settings.bufferMinutes} min")
                TextButton(onClick = { a.updateTravel { it.copy(bufferMinutes = (it.bufferMinutes + 5).coerceAtMost(60)) } }) { Text("+") }
            }
        }

        item {
            SectionHeader("Notifications")
            if (!alertState.notificationsOn) {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Notifications are off for DayPlanner.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = a.requestNotifications) { Text("Allow notifications") }
                    }
                }
            }
            if (!alertState.exactAlarms) {
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Exact alarms are off, so alerts may arrive a few minutes late.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = a.openExactAlarmSettings) { Text("Allow exact alarms") }
                    }
                }
            }
            ToggleRow(
                "Leave-now alerts",
                "Fires at start − travel time − buffer, for events with a location.",
                settings.leaveAlerts,
            ) { on -> a.updateTravel { it.copy(leaveAlerts = on) } }
            ToggleRow("Morning summary", "Your events and to-dos for the day.", settings.morningSummary) { on ->
                a.updateTravel { it.copy(morningSummary = on) }
            }
            if (settings.morningSummary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Summary time", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val t = settings.morningTime
                        TimePickerDialog(ctx, { _, h, m -> a.updateTravel { it.copy(morningTime = LocalTime.of(h, m)) } }, t.hour, t.minute, true).show()
                    }) { Text(settings.morningTime.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                }
            }
            OutlinedButton(onClick = a.testNotification, modifier = Modifier.padding(top = 12.dp)) { Text("Send test notification") }
        }

        item {
            SectionHeader("Scheduled")
            if (alertState.upcoming.isEmpty()) {
                Text(
                    "Nothing scheduled. Leave-now alerts need events with a location, a travel time, and a leave time still ahead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(alertState.upcoming, key = { "al-${it.key}" }) { al ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    al.at.format(DateTimeFormatter.ofPattern("EEE HH:mm")),
                    Modifier.width(84.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(al.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- tags ----------

@Composable
private fun TagsTab(tags: List<Tag>, a: SettingsActions) {
    var editing by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        item {
            Text(
                "Tap a tag to rename it or change its colour. Typing #anything in quick-add also creates a tag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(tags, key = { it.key }) { tag ->
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { editing = if (editing == tag.key) null else tag.key }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagDot(tag.color, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(tag.name, Modifier.weight(1f))
                    if (tag.key != Tags.OTHER) {
                        IconButton(onClick = { a.deleteTag(tag.key) }) {
                            Icon(Icons.Default.Delete, "Delete tag", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (editing == tag.key) {
                    var name by remember(tag.key) { mutableStateOf(tag.name) }
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(20)
                            if (Tags.isValidName(name)) a.updateTag(tag.copy(name = name.trim()))
                        },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    ColorPalette(selected = tag.color) { a.updateTag(tag.copy(color = it)) }
                }
            }
        }
        item {
            SectionHeader("New tag")
            NewTagForm(tags) { name, color -> a.addTag(name, color) }
            Text(
                "Deleting a tag doesn't delete anything; those items show as Other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------- syntax ----------

@Composable
private fun SyntaxTab() {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        item {
            SectionHeader("Quick-add box")
            Text("Type a title plus any of these, in any order:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SyntaxCard()
        }
        item {
            SectionHeader("Examples")
            listOf(
                "Problem sheet 4 fri 1h30 #study" to "To-do on Friday, 90 min, Study",
                "Buy milk 6pm #shop" to "To-do today at 18:00",
                "Read Hull ch.3 someday" to "Undated to-do that stays on your list",
                "LeetCode x5 @week !r #quant" to "5 this week, split into daily to-dos, repeats weekly",
                "Two-sum tmr 30m ^leetcode" to "To-do tomorrow that counts towards LeetCode",
                "Report @month" to "One thing this month; add steps on its card for a %",
            ).forEach { (ex, what) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(ex, fontWeight = FontWeight.Medium)
                    Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionHeader("Rules")
            listOf(
                "The first word is always part of the title, so “Sun cream” stays a title.",
                "A weekday means the next one, including today.",
                "Routines use the same box: add a time (18:00) to put them in Google Calendar.",
                "For start/end dates and weekly/monthly breakdowns, use New task on the Tasks tab.",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}
