package dev.nathanxu.dayplanner.data

import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.ProtectedBlock
import dev.nathanxu.dayplanner.domain.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BackupCodecTest {
    private val d = LocalDate.of(2026, 11, 9)
    private val full = BackupContents(
        tasks = listOf(
            Task(
                id = 5, title = "Essay “draft”\nline 2", date = d, category = "study", minutes = 90, time = LocalTime.of(14, 30),
                goalId = 2, routineId = null, units = 3, remindBefore = -1, remindAt = d.atTime(13, 45), priority = "HIGH", deadline = d.plusDays(4),
                place = "UCL", plannedEvents = "11,12", sittings = "2026-11-10T14:00/60;2026-11-11T09:00/30", skipped = true, genKey = "g:2:123", done = true, doneAt = 1_762_000_000_000, createdAt = 1_761_000_000_000,
            ),
            Task(id = 6, title = "Someday thing", date = null, createdAt = 1),
        ),
        goals = listOf(
            Goal(id = 2, title = "LeetCode", period = Period.CUSTOM, periodStart = d, target = 20, recurring = true, archived = false,
                category = "study", split = true, endDate = d.plusDays(27), cadence = Cadence.WEEKLY, priority = "LOW"),
            Goal(id = 3, title = "Old", period = Period.WEEK, periodStart = d, cadence = null),
        ),
        routines = listOf(
            Routine(id = 1, title = "Gym", category = "gym", minutes = 60, daysMask = 0b0010101, active = true,
                time = LocalTime.of(7, 0), syncToCalendar = true, calendarEventId = 999, remind = false),
            Routine(id = 2, title = "Read", daysMask = 127, time = null, remind = null, repeatUnit = "MONTH", repeatEvery = 2, repeatFrom = d),
        ),
        tags = listOf(Tag("study", "Study", 0xFF3366CC.toInt()), Tag("gym", "Gym", 0xFF22AA55.toInt())),
        settings = SettingsState(
            accounts = setOf("b@gmail.com", "a@ucl.ac.uk"), home = "WC1E 6BT", travelMode = "WALK", bufferMinutes = 5,
            leaveAlerts = false, morningSummary = true, morningTime = LocalTime.of(7, 15), remindTodos = true, reminderLead = 15,
            workStart = LocalTime.of(9, 0), workEnd = LocalTime.MAX, breakMinutes = 15, defaultMinutes = 45,
            protectedBlocks = listOf(ProtectedBlock(31, LocalTime.of(19, 0), LocalTime.of(21, 0), "quant", "Quant prep")),
            places = listOf(SavedPlace("Gym", "PureGym Holborn")),
            weeklyReview = false, reviewDay = DayOfWeek.SATURDAY, reviewTime = LocalTime.of(10, 0),
        ),
        exportedAt = LocalDateTime.of(2026, 11, 9, 3, 0),
    )

    @Test fun roundTripsEverything() {
        val text = BackupCodec.encode(full, LocalDateTime.of(2026, 11, 9, 3, 0, 5, 123))
        val back = BackupCodec.decode(text)
        assertEquals(full.tasks, back.tasks)
        assertEquals(full.goals, back.goals)
        assertEquals(full.routines, back.routines)
        assertEquals(full.tags, back.tags)
        // Calendar ids and onboarding stay with the phone.
        assertEquals(full.settings.copy(hiddenCalendars = emptySet(), routineCalendarId = null), back.settings)
        assertEquals(LocalDateTime.of(2026, 11, 9, 3, 0, 5), back.exportedAt)
    }

    @Test fun deviceSpecificSettingsNotExported() {
        val text = BackupCodec.encode(full.copy(settings = full.settings.copy(routineCalendarId = 42, hiddenCalendars = setOf(7))), LocalDateTime.now())
        assertTrue(!text.contains("routineCalendarId") && !text.contains("hiddenCalendars"))
    }

    @Test fun oldBackupMissingFieldsGetsDefaults() {
        val minimal = """{"app":"DayPlanner","format":1,"tasks":[{"id":1,"title":"x"}],"routines":[{"id":1,"title":"r","daysMask":3}]}"""
        val b = BackupCodec.decode(minimal)
        assertEquals(Task(id = 1, title = "x", date = null, createdAt = 0), b.tasks.single())
        assertEquals(Routine(id = 1, title = "r", daysMask = 3), b.routines.single())
        assertEquals(SettingsState(), b.settings)
    }

    @Test fun rejectsWithReadableMessages() {
        fun msg(s: String) = assertThrows(BackupCodec.BadBackup::class.java) { BackupCodec.decode(s) }.message!!
        assertTrue(msg("not json").contains("not valid JSON"))
        assertTrue(msg("""{"hello":1}""").contains("isn't a DayPlanner backup"))
        assertTrue(msg("""{"app":"DayPlanner","format":99}""").contains("newer"))
        assertTrue(msg("""{"app":"DayPlanner","format":1,"tasks":[{"id":1}]}""").contains("to-do 1"))
        assertTrue(msg("""{"app":"DayPlanner","format":1,"goals":[{"id":1,"title":"g","period":"YEAR","periodStart":"2026-01-01"}]}""").contains("unknown period"))
        assertTrue(msg("""{"app":"DayPlanner","format":1,"tasks":[{"id":1,"title":"t","date":"tomorrow"}]}""").contains("bad date"))
    }
}
