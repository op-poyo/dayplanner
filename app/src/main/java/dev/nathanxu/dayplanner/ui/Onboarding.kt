package dev.nathanxu.dayplanner.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nathanxu.dayplanner.data.SettingsState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the setup screens need from the app. */
class OnboardingActions(
    val requestCalendar: () -> Unit,
    val setAccountLinked: (String, Boolean) -> Unit,
    val requestNotifications: () -> Unit,
    val update: ((SettingsState) -> SettingsState) -> Unit,
    val quickAdd: (String) -> Unit,
    val restore: () -> Unit,
    val finish: () -> Unit,
)

/**
 * First launch: five short steps, each skippable. Everything here can be changed later in
 * Settings, so nothing is required to get going.
 */
@Composable
fun Onboarding(
    settings: SettingsState,
    calendarConnected: Boolean,
    accounts: List<String>,
    notificationsOn: Boolean,
    a: OnboardingActions,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 4
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val ctx = LocalContext.current
    fun pickTime(t: LocalTime, set: (LocalTime) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> set(LocalTime.of(h, m)) }, t.hour, t.minute, true).show()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp)) {
            // Progress dots + skip.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..last).forEach { i ->
                        Box(
                            Modifier.size(8.dp).background(
                                if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            )
                        )
                    }
                }
                TextButton(onClick = a.finish) { Text("Skip setup") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
                when (step) {
                    0 -> {
                        Title("Your day in one place")
                        Body("Calendar, to-dos, long-term tasks and routines together, with a plan for when to do things and reminders when it's time.")
                        Body("Setup takes about a minute. Every step can be skipped and changed later in Settings.")
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = a.restore) { Text("Restore from a backup instead") }
                    }
                    1 -> {
                        Title("Connect your calendar")
                        Body("Shows your lectures and events, avoids them when planning, and puts routines into Google Calendar. Nothing leaves your phone.")
                        if (!calendarConnected) {
                            Button(onClick = a.requestCalendar) { Text("Allow calendar access") }
                        } else {
                            Body("Connected ✓")
                            if (accounts.size > 1) {
                                Body("You have more than one Google account. Tick the ones DayPlanner should use:")
                                accounts.forEach { acc ->
                                    val on = settings.accounts?.contains(acc) == true
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = on, onCheckedChange = { a.setAccountLinked(acc, it) })
                                        Text(acc)
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        Title("Reminders")
                        Body("For to-dos with a time, when to leave for events, a morning summary and a Sunday review. You can turn each off in Settings.")
                        if (notificationsOn) Body("Notifications are on ✓")
                        else Button(onClick = a.requestNotifications) { Text("Allow notifications") }
                    }
                    3 -> {
                        Title("Your day")
                        Body("The planner only places to-dos between these times.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("From", Modifier.weight(1f))
                            OutlinedButton(onClick = { pickTime(settings.workStart) { t -> a.update { it.copy(workStart = t) } } }) {
                                Text(settings.workStart.format(hm))
                            }
                            Text("  to  ")
                            OutlinedButton(onClick = { pickTime(settings.workEnd) { t -> a.update { it.copy(workEnd = t) } } }) {
                                Text(if (settings.workEnd == LocalTime.MAX) "24:00" else settings.workEnd.format(hm))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Body("Home is where your first journey of the day starts (for travel times). Optional.")
                        var home by remember { mutableStateOf(settings.home ?: "") }
                        OutlinedTextField(
                            home, { home = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Home address or postcode") },
                        )
                        TextButton(
                            onClick = { a.update { it.copy(home = home.trim().ifEmpty { null }) } },
                            enabled = home.trim() != (settings.home ?: ""),
                        ) { Text("Save home") }
                    }
                    else -> {
                        Title("Add things fast")
                        Body("Type a to-do in one line. Words after the title set the details:")
                        listOf(
                            "Problem sheet fri 2h #study" to "Friday, 2 hours, tag study",
                            "Gym 18:30 tmr" to "tomorrow at 18:30",
                            "Essay 3h by fri !high" to "due Friday, high priority — the planner picks when",
                            "LeetCode x5 @week" to "a weekly goal, broken into daily to-dos",
                        ).forEach { (ex, what) ->
                            Text(ex, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                            Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Body("Try one (it's added for real):")
                        var added by remember { mutableStateOf<String?>(null) }
                        QuickAddBar(placeholder = "Problem sheet fri #study 1h", onSubmit = { a.quickAdd(it); added = it })
                        added?.let { Body("Added “$it”. You'll find it on Today.") }
                        Body("In the app, ✎ in the box opens the full form. All the shortcuts are in Settings → Syntax.")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step < last) Button(onClick = { step++ }) { Text(if (step == 0) "Get started" else "Next") }
                else Button(onClick = a.finish) { Text("Start using DayPlanner") }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
}
